# mall AI 智能导购：开发记录与问题排查笔记

> 本文档记录 mall 项目接入 AI 智能导购（DeepSeek + Spring AI）过程中的架构设计、踩坑经历、
> 排查思路与最终解决方案，作为二次开发的工程留痕。所有问题均为真实发生并已解决。

## 目录

1. [功能与架构](#一功能与架构)
2. [问题 1：非流式接口突然全部 401（假象）](#二问题-1非流式接口突然全部-401假象)
3. [问题 2：模型偶发生成非法工具参数导致流中断](#三问题-2模型偶发生成非法工具参数导致流中断)
4. [问题 3：前端永久卡在"正在思考"（最耗时）](#四问题-3前端永久卡在正在思考最耗时)
5. [问题 4：环境与工具链问题合集](#五问题-4环境与工具链问题合集)
6. [性能优化实测](#六性能优化实测)
7. [调试工具与方法沉淀](#七调试工具与方法沉淀)
8. [简历/面试可讲的点](#八简历面试可讲的点)

---

## 一、功能与架构

### 功能

用户用自然语言描述购物需求（如"500 以内的运动鞋""3000 以内的小米手机"），AI 导购将其
解析为结构化搜索条件，调用商品搜索，并逐字流式输出推荐语 + 商品卡片，支持多轮对话。

### 技术选型

- **Spring AI 1.1.8**（`spring-ai-bom` + `spring-ai-starter-model-deepseek`，官方 DeepSeek starter，兼容 Spring Boot 3.5）
- **模型**：`deepseek-v4-flash`（OpenAI 兼容协议，支持 Function Calling 与流式输出）
- **流式协议**：SSE（`SseEmitter` + Reactor `Flux`），事件序列 `session → delta×N → products → done / error`
- **会话历史**：Redis（key `ai:session:{sessionId}`，保留最近 12 条消息控制成本）
- **工具结果关联**：`ToolContext` + `ConcurrentHashMap<requestId, products>`，支持并发安全
- **前端**：uni-app H5 用 XHR 增量解析 SSE 实现打字机效果；小程序/App 自动回退到非流式接口

### 接口

| 接口 | 说明 |
| --- | --- |
| `POST /ai/chat` | 非流式对话（回退路径） |
| `POST /ai/chat/stream` | 流式对话（SSE） |
| `GET/POST /product/search` | 商品搜索（增加价格区间参数，向后兼容） |

### 演进：为什么最终采用"两阶段"架构

最初实现为**单次流式调用**（模型直接调工具后流式输出），首字更快，但遇到问题 2 后
发现其错误路径不可控（见下文）。最终采用**两阶段**：

1. 第一阶段（非流式）：意图解析 + 商品搜索，失败自动重试 1 次；
2. 第二阶段（流式）：基于候选商品生成推荐语（无工具调用，稳定）。

---

## 二、问题 1：非流式接口突然全部 401（假象）

### 现象

给工具方法 `searchProducts` 增加 `ToolContext` 参数（流式功能需要）后，**非流式接口
`/ai/chat` 突然全部返回 401**："暂未登录或 token 已经过期"。

### 排查过程

1. 对照测试白名单接口：`/product/search`、`/home/content` 均正常放行 → 排除"安全白名单整体失效"。
2. 检查 `application.yml`：`/ai/**` 确实在白名单内，jar 内配置也正确。
3. 开启 Spring Security TRACE 日志（`LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_SECURITY=TRACE`）后真相大白：

```text
ERROR ... Request processing failed: java.lang.IllegalArgumentException:
ToolContext is required by the method as an argument
```

### 根因

工具方法声明了 `ToolContext` 参数，Spring AI 要求调用方必须通过 `.toolContext(...)` 传入；
非流式路径漏传导致**工具执行抛异常**。异常转发到 `/error`，而 `/error` 不在安全白名单内，
Spring Security 把真实的 500 错误拦截成了 **401**——排查方向被严重误导。

### 修复

非流式路径同样传入 `.toolContext(Map.of(REQUEST_ID_KEY, requestId))`。

### 教训

- 401/403 不一定是鉴权问题，可能是内部异常被安全层"包装"（尤其 `/error` 未白名单时）。
- 排查 API 权限类问题，先开安全层 TRACE 日志看真实决策。

---

## 三、问题 2：模型偶发生成非法工具参数导致流中断

### 现象

浏览器中点击快捷提问后，页面永久停留在"正在思考"，AI 回复始终不出现。

### 排查过程

1. 用 curl 直接调用流式接口：正常返回 `delta` + `done` → 排除后端整体故障。
2. 查看后端日志，发现完整堆栈：

```text
Caused by: com.fasterxml.jackson.core.JsonParseException:
Unexpected character (',' (code 44)): expected a valid value ...
at [Source: REDACTED ...; line: 1, column: 70]
	at org.springframework.ai.tool.method.MethodToolCallback.extractToolArguments(...)
```

3. 同时发现 Reactor 关键日志：

```text
Operator called default onErrorDropped
reactor.core.Exceptions$ErrorCallbackNotImplemented:
org.springframework.ai.tool.execution.ToolExecutionException: ...
```

### 根因（两层）

1. **模型侧**：DeepSeek V4 默认开启思考模式，小概率生成畸形工具参数 JSON（如多余的逗号），
   Jackson 在 `column 70` 处解析失败。
2. **框架侧**：Spring AI 在**流式 + 工具调用**路径中，工具执行异常被 Reactor 以
   `onErrorDropped` 丢弃，上层的 `doOnError` 收不到错误 → SSE 连接不关闭 → 前端永久挂起。

### 修复

- 改为**两阶段架构**：第一阶段用非流式调用（错误可正常同步捕获，且失败自动重试 1 次），
  第二阶段流式生成推荐语（无工具调用，不会触发该路径）。
- 前端增加兜底：XHR 超时（60s）保证 promise 必定落定。

### 教训

- 流式 + Function Calling 的错误传播在不同实现下可能不可靠，生产环境要有超时与兜底。
- 对 LLM 生成的 JSON 永远要假设"可能不合法"，做容错与重试。

---

## 四、问题 3：前端永久卡在"正在思考"（最耗时）

### 现象

后端正常（curl 正常返回、后端日志显示搜索已执行、XHR 状态 200），但浏览器 UI 永远不更新：
AI 气泡一直显示打字动画，promise 永不落定。

### 排查过程（CDP 逐层定位）

该问题排查最久，以下是完整的排除链路：

| 步骤 | 实验 | 结论 |
| --- | --- | --- |
| 1 | curl 直测后端 `/ai/chat/stream` | 正常（deltas + done）→ 排除后端 |
| 2 | 页面内 `fetch` 读 SSE 流 | 收到分片（session + delta）→ 排除 CORS/网络 |
| 3 | 页面内直接调用同一个 `streamAiChatAPI` | 正常（28 个 delta、2 个商品）→ 排除函数本身 |
| 4 | 在 `ai.ts` 内部打日志 | XHR 创建、open、send 全部执行 → 排除"请求未发出" |
| 5 | 监听 `xhr.onreadystatechange` | `readyState=4, status=200` → 排除"响应未收到" |
| 6 | 汇总：请求成功、响应成功、函数本身可用 | 问题锁定在 **promise 不落定 + UI 不更新** |

### 根因（两层叠加）

**根因 A：Vue 响应式失效。**

`handleSend` 中创建 AI 消息时用的是普通对象：

```ts
const aiMessage: ChatMessage = { role: 'ai', content: '' }
messages.value.push(aiMessage)
```

`messages` 是 `ref` 数组，`push` 后数组内保存的是**响应式代理**，而 `onDelta` 回调里
`aiMessage.content += chunk` 修改的是**原始对象**——数据变了但 Vue 不会触发重渲染，
界面永远停留在空内容。

修复：

```ts
const aiMessage = reactive<ChatMessage>({ role: 'ai', content: '' })
```

**根因 B：promise 永不落定。**

`xhr.onload` 中先执行 `flush()` 再 `resolve/reject`；若 `flush()` 抛异常，
`resolve/reject` 不会执行，promise 永久 pending。且原代码未设置 XHR 超时。

修复：

```ts
xhr.onload = () => {
  try {
    flush()
  } catch (e) {
    errorMsg = errorMsg || '解析响应失败'
  }
  if (errorMsg) reject(new Error(errorMsg))
  else resolve({ sessionId, reply, products })
}
xhr.timeout = 60000
```

### 教训

- 对象 push 进 `ref`/`reactive` 容器后，必须通过响应式对象修改（用 `reactive()` 创建），
  否则"数据变了但界面不动"这类 bug 极难肉眼发现。
- 任何异步流程都要保证"必定落定"：要么 resolve，要么 reject，要么超时。
- 浏览器端流式问题排查，CDP（Console / Network / Runtime.evaluate / 内部日志）是决定性工具。

---

## 五、问题 4：环境与工具链问题合集

### 5.1 MongoDB wire version 冲突

- **现象**：portal 健康检查 503，日志报 `Server at localhost:27017 reports wire version 7,
  but this version of the driver requires at least 8 (MongoDB 4.2)`。
- **根因**：本机 27017 端口被一个**旧版 MongoDB 服务**占用，Docker 里的 mongo:5.0 容器
  被"挡"在端口映射后面。
- **修复**：`SPRING_DATA_MONGODB_HOST` 改用宿主机局域网 IP（如 192.168.1.7）绕过本机服务。
- **启发**：Docker 端口映射不生效时，先确认宿主机是否已有同名端口服务。

### 5.2 前端端口冲突

- **现象**：商城 H5 dev server 莫名"消失"，5173 端口变成另一个项目（TBForum，React）。
- **根因**：两个 dev server 抢占 5173。
- **修复**：在 `src/manifest.json` 的 `h5.devServer.port` 固定为 **5176**。

### 5.3 截图截错窗口

- **现象**：`-ActiveWindow` 截图截到的是 Codex 窗口而非浏览器页面。
- **修复**：改用无头 Chrome（`--headless=new --screenshot`）+ CDP `Emulation` 手机视口截图，
  不依赖前台窗口状态。

### 5.4 视觉模型误判

- **现象**：qwen3.7-plus 连续两版截图都说"欢迎语文字被截断"。
- **真相**：用 CDP 实测元素几何数据，`clientWidth == scrollWidth`（344px，无溢出），
  是静态截图渲染比例造成的误读。
- **教训**：视觉模型适合做 UI 初审，**最终以 DOM/几何数据为准**。

### 5.5 项目原有工具链问题（非本次引入）

- `vue-tsc 1.8.27` 与 `TypeScript 5.9.3` 不兼容（报
  `Search string not found: "/supportedTSExtensions"`），前端类型检查不可用。
- ESLint 无配置文件，`npx eslint` 无法运行。
- Maven Docker 打包插件连不上远程 Docker（`192.168.3.101:2375`），构建需
  `-Ddocker.skip=true`（启动脚本已内置）。

### 5.6 其他小坑

- PowerShell `Start-Process` 偶发 `Path/PATH` 字典冲突：改用 `cmd /c` 重定向或 .NET `Process`。
- `setx` 设置的环境变量对 Codex 子进程不生效：每次调用需显式设置。
- Spring AI `DeepSeekChatOptions` 未暴露 `thinking` 参数（`javap` 确认），
  DeepSeek V4 思考模式无法通过框架关闭，只能接受其延迟。

---

## 六、性能优化实测

### 关键指标定义

- **TTFB**：首个字节（session 事件，实际几乎即时）；
- **首字时间**：第一个 `delta` 到达时间（用户真实感知等待）；
- **总耗时**：`done` 事件时间。

### 实测数据（真实调用 DeepSeek）

| 方案 | 首字时间 | 总耗时 | 说明 |
| --- | --- | --- | --- |
| 两阶段（初始，推荐语较长） | ~3s | ~4.9s | 两条消息各一次完整往返 |
| 单次流式调用 | 1.5–2.6s | 2.3–3.9s | 快，但错误路径不可控（问题 2） |
| 两阶段 + 重试（最终） | ~2–3s | ~4–6s | 稳健优先，支持失败重试 |

### 结论

- 工具调用类 LLM 的"首字时间" = 模型思考 + 工具执行 + 第二轮首 token，属于**固有延迟**；
  想继续压缩只能靠关闭思考模式（框架未开放）或换更快的模型。
- 对用户体验而言，等待动画 + 尽早出首字比压缩总耗时更重要。

---

## 七、调试工具与方法沉淀

- **Spring Security TRACE 日志**：`LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_SECURITY=TRACE`，
  看清授权决策与真实异常。
- **CDP（Chrome DevTools Protocol）**：Node + WebSocket 直连无头 Chrome，
  用 `Network`（抓请求/响应体）、`Runtime.evaluate`（页面内执行任意 JS）、
  `Runtime.consoleAPICalled`（控制台）、`Emulation`（手机视口）、`Page.captureScreenshot`
  （像素级截图）。这是定位浏览器侧诡异 bug 的决定性工具。
- **无头 Chrome 截图**：`chrome --headless=new --screenshot --window-size=390,844 URL`，
  不依赖窗口状态，可重复。
- **视觉模型辅助 UI 验证**：qwen3.7-plus（vision skill）初审布局与文案，
  与 CDP 几何数据交叉验证。
- **`javap` 检查三方库**：确认 `DeepSeekChatOptions`、`ChatCompletionRequest` 等
  API 是否暴露所需能力，避免在文档上做无用功。

---

## 八、简历/面试可讲的点

1. **架构设计**：自然语言 → Function Calling 结构化意图 → 商品搜索 → 流式推荐，
   两阶段拆分权衡了延迟与错误可控性。
2. **健壮性**：LLM 输出不可信（畸形 JSON）→ 重试 + 兜底；前端 promise 必定落定 + 超时。
3. **并发**：工具结果通过 `ToolContext` + 按请求 ID 关联，支持并发会话。
4. **成本控制**：Redis 会话只保留最近 N 条；系统提示词稳定可命中缓存。
5. **排查能力**：能讲清楚 401 假象、流式错误被吞、Vue 响应式失效三个真实案例的
   定位过程（安全日志 / CDP / 内部日志逐层排除）。

---

## 九、UI 改造：仿淘宝首页

### 目标

把商城首页从"内容型"改造成"交易型"淘宝风格，补齐缺失的电商首页元素。

### 主要改动（`mall-app-web/src/pages/index/index.vue`）

1. **金刚区**：5 个图标 → 2 行 × 5 列共 10 个宫格入口（分类/限时抢购/品牌闪购/领券中心/
   新品首发/人气推荐/会员中心/AI 导购/消息通知/全部商品），彩色圆角图标 + emoji，
   每个入口都有真实跳转。
2. **限时秒杀横幅**：改为淘宝式红色渐变通栏（左标题右倒计时黑底白字数字块）。
3. **品牌区**：改为 3 列宫格，统一 Logo 尺寸 + 名称 + 商品数，修复底部品牌显示不全。
4. **猜你喜欢**：改为淘宝式瀑布流双列卡片（图片 + 双行标题 + 红色价格 + 灰色销量），
   保留上拉分页加载。
5. **轮播广告点击**：原为 TODO 空实现，现按 `item.url` 跳转（tab 页用 switchTab）。

### 视觉验证

接入 qwen3.7-plus 视觉模型对截图做 UI 初审，再用 CDP 元素几何数据确认（如文字是否溢出），
两者交叉验证。

---

## 十、第二轮踩坑记录（商品数据与搜索）

### 问题 A：PageHelper 默认 pageNum=0 导致搜索结果为空

- **现象**：`/product/search` 不带 pageNum 时返回 `total=2, list=[]`，搜索页永远空白。
- **根因**：Controller 默认 `pageNum=0`，PageHelper 按第 0 页计算 offset 为负，
  MySQL `LIMIT -5,5` 返回空。
- **修复**：默认值改为 `1`。
- **教训**：分页插件的"第 1 页"约定不统一时，默认值要显式验证，不能想当然。

### 问题 B：PowerShell 管道传输 SQL 导致中文全部变成 `?`

- **现象**：用 `Get-Content | docker exec -i mysql` 导入含中文的 SQL 后，
  数据库里中文全部变成 `?`（0x3F）。
- **根因**：PowerShell 向原生进程管道传字符串时按控制台编码（GBK/ANSI）编码，
  无法表示的字符被替换为 `?`，与 `--default-character-set=utf8mb4` 叠加后彻底损坏。
- **修复**：改用 `cmd /c "docker exec -i ... < file.sql"` 原样字节重定向，
  并写入 UTF-8 无 BOM 文件。
- **教训**：跨进程传中文数据，永远用"文件 + 字节重定向"而非 PowerShell 管道；
  导入后用 `HEX(name)` 抽查编码（正确的中文应是 `E4`/`E5` 开头的 UTF-8 字节）。

### 问题 C：uni-app 同 hash 导航不刷新页面

- **现象**：数据库已更新，浏览器里首页仍显示旧商品。
- **根因**：CDP 导航到与当前页相同的 hash，uni-app 路由复用旧页面实例，`onLoad` 不触发。
- **修复**：测试时用 `Page.reload` 强制整页刷新。
- **教训**：验证数据更新类改动，先确认页面真的重新加载了。

### 问题 D：LLM 工具参数 `priceMin/priceMax=0` 被当成真实过滤条件

- **现象**：用户没提价格时，模型把价格区间传成 `0~0`，搜索变成 `price >= 0 AND price <= 0`，
  永远搜不到商品（AI 回复"没有找到"）。
- **修复**：工具方法里把 `0` 归一化为"无限制"（`> 0` 才作为过滤条件），
  并在 `@ToolParam` 描述中明确"没有限制时传 0"。
- **教训**：LLM 生成的工具参数默认值要和业务语义对齐，服务端要做防御性归一化。

### 问题 E：关键词搜索只匹配商品名称字段

- **现象**：用户/模型用 "Mate70"（无空格）搜不到名称含 "Mate 70"（有空格）的商品。
- **修复**：新增自定义 DAO 查询，关键词同时匹配 `name / sub_title / keywords` 三个字段
  （OR 语义）；商品数据更新时同步维护 keywords 字段。
- **教训**：模糊搜索的召回率要覆盖商品的多信息字段，不能只依赖名称。

### 商品数据更新说明（合规方式）

原演示数据为 2018 年前后机型（小米 8、iPhone 8 Plus、华为 P20），已整体更新为
2025-2026 年在售型号（iPhone 16 / 16 Pro Max、小米 15 / 15 Pro、Redmi K80 / K80 Pro、
华为 Mate 70 / Mate 70 Pro、OPPO Find X8 Pro、三星 990 EVO SSD 等），
同步更新价格、副标题、销量、库存与 SKU 价格。

**关于爬虫的说明**：淘宝/天猫等平台有明确的反爬机制与 robots 协议限制，直接爬取既不
合规也基本不可行。本项目的商品数据采用"人工整理 + 常识校准"的方式更新（价格参考各品牌
官方渠道 2025-2026 年发布价）；如需自动化获取，应接入京东/淘宝/天猫开放平台的正规 API
（需开发者资质），或使用平台授权的数据服务，这是合规且稳定的做法。

---

## 十一、Phase 2：ES 8 升级 + 语义搜索（已完成）

### 目标

把"认字"的 LIKE 关键词搜索升级为"懂意思"的向量语义检索，解决模糊需求
（如"拍照好的手机""适合送女生的礼物"）和跨词匹配问题。

### 已完成的技术改造

1. **ES 7.17 → ES 8.17.3**：mall-search 使用 Spring Boot 3.5 自带的
   Spring Data Elasticsearch 5.5（ELC 客户端），本身兼容 ES 8；
   启动脚本升级镜像与 IK 插件，并关闭 `xpack.security`（本地开发保持明文 HTTP）。
2. **商品向量化**：`EsProduct` 新增 `dense_vector`（1024 维、cosine 相似度）字段；
   `importAll`/`create` 导入时调用**通义 text-embedding-v3**（DashScope OpenAI 兼容接口，
   复用 `DASHSCOPE_API_KEY`）为"名称+副标题+关键词+品牌+分类"文本生成向量，
   单商品失败不阻断导入。
3. **混合检索**：新增 `/esProduct/search/semantic` 接口，先向量 kNN（top K×3）再关键词
   BM25（name^10 / subTitle^5 / keywords^2），语义优先交错合并去重，支持品牌/分类/价格过滤。
4. **AI 导购接入**：`ProductSearchTools` 优先调用 mall-search 语义接口（新增
   `MallSearchClient`，通过 `mall.search.base-url` 配置），服务不可用或结果为空时
   自动回退原数据库检索，保证可用性。

### 遇到的坑

1. **ELC 版本差异**：本机依赖是 `elasticsearch-java 8.18.8`，`RangeQuery` 已改为变体 API，
   必须用 `range.number(...)` 而非 `range.field(...)`（javap 排查）。
2. **Docker 拉取 ES 8 镜像极慢**：直连 Docker Hub 与多个国内镜像源均超过 20 分钟无结果；
   经排查本机 Docker 已配置国内 mirror 列表，但大镜像下载速度仍受限于网络
   （小镜像 alpine 仅需 1 分钟，ES 约 1.2GB）。解决方案：后台续拉（分层可断点续传），
   待镜像就绪后创建容器。**教训**：docker pull 的进度输出是 `\r` 刷新，被管道/重定向后
   几乎看不到，判断进度要看镜像是否出现，而不是日志文本。
3. **镜像源整体不可用**：2026 年多数公共镜像源（USTC/网易/腾讯云/阿里云/华为云/azk8s）
   均已失效或连接超时，仅 daocloud、1panel 可用但速度只有 ~30KB/s（1.2GB 需 8 小时+）。
   **最终绕过方案**：直接从 Elastic 官方 CDN（artifacts.elastic.co）下载
   ES 8.17.3 Windows 版（456MB，实测 **10MB/s**，比镜像源快 300 倍），本地原生运行
   （自带 JDK，配置 `xpack.security.enabled=false` + `discovery.type=single-node`），
   完美绕开 Docker 镜像问题。

### 验证结果（全部通过）

- ✅ ES 8.17.3 本地运行 + IK 中文分词，`pms` 索引 20 篇文档、向量字段
  `dense_vector(1024, cosine)` 正确
- ✅ 语义检索召回："拍照好的手机"命中徕卡影像机型（名称无"拍照"字样）、
  "办公用的轻薄笔记本"第一名为 Book Air、混排结果语义合理
- ✅ AI 导购端到端："推荐一款拍照好的手机，预算6000以内" → 推荐小米15 Pro
  （徕卡影像，5799 元），候选全部为影像旗舰且价格过滤正确
- ✅ 数据库回退：mall-search 不可用时 AI 工具自动回退 LIKE 检索

### 部署说明

- 本地开发：ES 8 原生运行于 `C:\Users\yangc\elasticsearch-8.17.3`（bin/elasticsearch.bat -d），
  mall-search 连接 `localhost:9200`。
- 一键脚本：`start-mall.ps1` 已升级为 ES 8.17.3 Docker 镜像 + IK 8.17.3
  （网络条件允许时可用；两者端口冲突时二选一）。

---

## 十二、会员功能核查与个人页改造

### 核查结论（API 实测通过）

注册/登录/验证码/会员信息/购物车/收藏/浏览历史/优惠券/收货地址均正常（HTTP 200）。
两个小坑记录：

1. **加购/收藏接口是 `@RequestBody` JSON**，用 query 参数调用会 415；前端封装本身正确。
2. **购物车列表显示**依赖前端加购时传全量字段（商品名/价格/图片），后端 `add()` 不自动填充，
   属于"前端负责组装"的既有设计，非 bug。

### 发现的问题（个人页原本的样子）

- 残留旧品牌"mall移动端商城"；"黄金会员/立即开通"是无后端支撑的假按钮；
- 游客点击头像无登录引导；"退款/售后"和"我的评价"两个入口是死的；
- 积分/成长值只有展示字段，无消费/获取闭环（属于原项目遗留，暂不实现）。

### 本次改造

1. **品牌统一**：会员卡片改为 "SenseMall 会员"，文案对齐新品牌；
2. **游客引导**：未登录点头像 → 登录页，并显示"点击登录，开启好物之旅"；
3. **申请售后（新功能页）**：`/pages/order/returnApply`，拉取已发货(2)/已完成(3)订单，
   选商品 + 填联系人与原因，提交 `/returnApply/create`（后端接口原已存在，前端补齐了 UI）；
4. **联系客服**：替换无后端支撑的"我的评价"入口（评价体系需新增表与接口，列为后续）；
5. **会员卡片**："立即开通"改为"了解会员"占位（会员等级体系待实现）。

### 实测结果

- 游客态个人页：SenseMall 会员卡片、点击登录提示、申请售后/联系客服入口均渲染正确；
- 浏览器真实登录（flowuser01）后：显示昵称与"个人资料"，退货页正常加载（空状态提示正确）。

---

## 十三、积分体系（已完成）

### 设计

复用原项目预留的积分骨架，补全闭环：

- **获取**：注册送 100、每日签到（基础 5 分，连续第 7 天 +20）、购物每 1 元送 1 积分（支付成功入账）；
- **消费**：下单抵扣（100 积分 = 1 元，单笔最高 50%，可与优惠券叠加）——原项目已有计算逻辑；
- **台账**：每次增减写入 `ums_integration_change_history`（扩展了 `order_id`、`balance_after` 字段），前端可查明细。

### 新增/改动

- 表：`ums_integration_change_history` 加 `order_id`、`balance_after` 两列（ALTER + MBG 模型/Mapper 手工同步）；
- `IntegrationService`：`earn/spend/checkin/checkinStatus/history`；
- 订单闭环：支付成功赠送积分入账；下单抵扣/取消退回改用统一服务并写台账；
- 注册送积分；签到用 Redis（按"会员+日期"防重复，连续天数存 Redis）；
- 接口：`POST /member/integration/checkin`、`GET .../checkin/status`、`GET .../history`；
- 前端：新增"我的积分"页（余额卡片/签到/规则/明细分页），个人页积分入口可点击。

### 踩坑记录

1. **循环依赖**：`IntegrationServiceImpl ↔ UmsMemberServiceImpl` 互相引用，
   Spring Boot 默认禁止循环引用启动失败 → 用 `@Lazy` 打破。
2. **空指针**：新用户 `integration` 字段为 NULL，`earn` 直接相加报 NPE →
   统一按 0 处理。
3. **原项目隐藏 Bug**：`order.setUseIntegration()` 在 `orderMapper.insert()` **之后**执行，
   数据库从未持久化抵扣积分，导致取消订单"退回积分"永远读不到 → 已把赋值移到 insert 之前。
4. **测试陷阱**：mall 全局异常处理器把业务失败包成 HTTP 200 + code 500，
   脚本判断"重复签到未拦截"是误报，看业务码即可。

### 验证结果（端到端）

- 注册 → 积分 100；签到 → 105，重复签到被拦截（code=500"今天已经签到过了"）；
- 明细：注册 +100、签到 +5，余额字段正确；
- 浏览器实测：积分页余额/签到按钮/规则/明细全部正常渲染并联动。

---

## 十四、"我的"右上角功能核查与修复（设置/消息）

### 核查结论

- **设置页**：原为"半成品"——"个人资料/实名认证/清除缓存"三个入口只是 toast 假跳转，
  "关于"还指向原项目 GitHub、"检查更新"为静态文本、消息推送开关无持久化；
- **消息页**：纯静态 2019 年演示数据，无任何接口调用，无未读/分页（原项目即如此）。

### 本次修复（设置页全部真实可用）

1. **个人资料（新功能）**：后端新增 `POST /sso/updateProfile`（昵称/头像/性别/生日/城市/职业/签名），
   前端新增资料编辑页，进入时重新拉取会员信息（避免登录缓存过期）；
2. **修改密码（新页面）**：复用后端已有的短信验证码重置接口
   （`/sso/updatePassword`），补齐前端页面（手机号+验证码+新密码）；
3. **清除缓存**：真实实现（保留登录 token 的前提下清空本地缓存）；
4. **消息推送开关**：持久化到本地存储；
5. **关于/检查更新**：更新为 SenseMall 品牌信息与版本提示；
6. **消息页**：如实说明——仍为静态演示数据，真正的消息中心需要新增后端模块
   （消息表 + 管理端发布 + 未读状态），列为后续。

### 踩坑记录

- **PowerShell 传中文损坏数据**：`Invoke-RestMethod -Body` 传中文 JSON 会被按 ASCII
   编码变成 `?`（与之前 SQL 管道问题同源），改用 curl + UTF-8 文件；
- **uni-app 页面实例复用**：同 hash 导航不重新触发 onLoad，验证时看到旧数据，
   用整页刷新确认真实行为；
- **uni-app H5 选择器**：`<input>` 的 class 落在 `<uni-input>` 包装元素上，
   读值要用 `.form-input input`。

### 验证

- 设置页 8 个入口全部可点且功能真实；资料页刷新后显示最新中文资料；
- 修改密码接口正常（账号不存在/验证码错误均正确报错）。
