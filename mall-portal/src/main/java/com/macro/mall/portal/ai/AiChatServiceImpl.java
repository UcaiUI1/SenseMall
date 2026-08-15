package com.macro.mall.portal.ai;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.common.service.RedisService;
import com.macro.mall.mapper.PmsProductMapper;
import com.macro.mall.model.PmsProduct;
import com.macro.mall.model.PmsProductExample;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * AI 智能导购服务实现。
 * 通过 Spring AI Function Calling 将用户自然语言转为结构化搜索条件，
 * 基于搜索结果生成推荐语，并将会话历史保存在 Redis 中。
 */
@Service
public class AiChatServiceImpl implements AiChatService {

    private static final String SESSION_KEY_PREFIX = "ai:session:";
    public static final String REQUEST_ID_KEY = "requestId";

    @Autowired
    private ChatClient.Builder chatClientBuilder;
    @Autowired
    private ProductSearchTools productSearchTools;
    @Autowired
    private RedisService redisService;
    @Autowired
    private PmsProductMapper productMapper;
    @Autowired
    private ObjectMapper objectMapper;

    @Value("${mall.ai.session-expire:86400}")
    private long sessionExpire;
    @Value("${mall.ai.max-history-messages:12}")
    private int maxHistoryMessages;

    @Override
    public AiChatResponse chat(AiChatRequest request) {
        if (StrUtil.isBlank(request.getMessage())) {
            throw new IllegalArgumentException("消息不能为空");
        }
        String sessionId = StrUtil.blankToDefault(request.getSessionId(), UUID.randomUUID().toString());
        String requestId = UUID.randomUUID().toString();
        List<Message> history = loadHistory(sessionId);

        String content = chatClientBuilder.build()
                .prompt()
                .system(buildIntentPrompt())
                .messages(history)
                .user(request.getMessage())
                .tools(productSearchTools)
                .toolContext(Map.of(REQUEST_ID_KEY, requestId))
                .call()
                .content();

        AiChatResponse response = parseResponse(content, sessionId);
        productSearchTools.takeSearchResult(requestId);
        saveHistory(sessionId, request.getMessage(), response.getReply());
        return response;
    }

    @Override
    public void chatStream(AiChatRequest request, SseEmitter emitter) {
        if (StrUtil.isBlank(request.getMessage())) {
            sendJsonEvent(emitter, "{\"type\":\"error\",\"message\":\"消息不能为空\"}");
            emitter.complete();
            return;
        }
        String sessionId = StrUtil.blankToDefault(request.getSessionId(), UUID.randomUUID().toString());
        sendJsonEvent(emitter, "{\"type\":\"session\",\"sessionId\":" + toJsonString(sessionId) + "}");
        String requestId = UUID.randomUUID().toString();
        List<Message> history = loadHistory(sessionId);
        StringBuilder fullReply = new StringBuilder();
        try {
            // 第一步：意图解析 + 商品搜索（非流式）。失败自动重试一次（模型偶发生成非法工具参数）
            AiChatResponse parsed = null;
            List<AiProduct> candidates = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    String intentContent = chatClientBuilder.build()
                            .prompt()
                            .system(buildIntentPrompt())
                            .messages(history)
                            .user(request.getMessage())
                            .tools(productSearchTools)
                            .toolContext(Map.of(REQUEST_ID_KEY, requestId))
                            .call()
                            .content();
                    parsed = parseResponse(intentContent, sessionId);
                    candidates = productSearchTools.takeSearchResult(requestId);
                    if (candidates == null) {
                        candidates = parsed.getProducts();
                    }
                    break;
                } catch (Exception e) {
                    if (attempt == 1) {
                        throw e;
                    }
                }
            }

            // 第二步：基于候选商品流式生成推荐语（纯文本，无工具调用，稳定）
            final List<AiProduct> resultProducts = (parsed != null && !parsed.getProducts().isEmpty())
                    ? parsed.getProducts() : candidates;
            Flux<String> replyFlux = chatClientBuilder.build()
                    .prompt()
                    .system(buildRecommendPrompt())
                    .messages(history)
                    .user(buildRecommendContext(request.getMessage(), candidates))
                    .stream()
                    .content();
            replyFlux
                    .doOnNext(chunk -> {
                        if (StrUtil.isNotBlank(chunk)) {
                            fullReply.append(chunk);
                            sendJsonEvent(emitter, "{\"type\":\"delta\",\"content\":" + toJsonString(chunk) + "}");
                        }
                    })
                    .doOnComplete(() -> {
                        sendProductsEvent(emitter, resultProducts == null ? Collections.emptyList() : resultProducts);
                        sendJsonEvent(emitter, "{\"type\":\"done\"}");
                        saveHistory(sessionId, request.getMessage(), fullReply.toString());
                        emitter.complete();
                    })
                    .doOnError(error -> {
                        sendJsonEvent(emitter, "{\"type\":\"error\",\"message\":" + toJsonString(error.getMessage()) + "}");
                        emitter.complete();
                    })
                    .subscribe();
        } catch (Exception e) {
            sendJsonEvent(emitter, "{\"type\":\"error\",\"message\":" + toJsonString(e.getMessage()) + "}");
            emitter.complete();
        }
    }

    private void sendProductsEvent(SseEmitter emitter, List<AiProduct> products) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "products");
            payload.put("products", products == null ? Collections.emptyList() : products);
            sendJsonEvent(emitter, objectMapper.writeValueAsString(payload));
        } catch (Exception ignored) {
        }
    }

    private void sendJsonEvent(SseEmitter emitter, String json) {
        try {
            emitter.send(SseEmitter.event().name("message").data(json));
        } catch (Exception ignored) {
        }
    }

    private String toJsonString(String value) {
        try {
            return objectMapper.writeValueAsString(value == null ? "" : value);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    private String buildRecommendContext(String userMessage, List<AiProduct> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户需求：").append(userMessage).append("\n");
        if (candidates != null && !candidates.isEmpty()) {
            sb.append("可推荐的候选商品（来自商品搜索）：\n");
            for (int i = 0; i < candidates.size(); i++) {
                AiProduct product = candidates.get(i);
                sb.append(i + 1).append(". [").append(product.getId()).append("] ")
                        .append(product.getBrandName() == null ? "" : product.getBrandName()).append(" ")
                        .append(product.getName()).append("，")
                        .append(product.getPrice()).append("元\n");
            }
        } else {
            sb.append("（本次没有搜索到匹配的商品）\n");
        }
        return sb.toString();
    }

    /**
     * 解析模型返回内容：优先按 JSON 提取回复与商品ID，解析失败时原样返回文本
     */
    private AiChatResponse parseResponse(String content, String sessionId) {
        AiChatResponse response = new AiChatResponse();
        response.setSessionId(sessionId);
        if (StrUtil.isBlank(content)) {
            response.setReply("抱歉，我暂时没有找到合适的商品，换个说法再试试吧。");
            response.setProducts(Collections.emptyList());
            return response;
        }
        try {
            JsonNode root = objectMapper.readTree(extractJson(content));
            String reply = root.path("reply").asText(null);
            List<Long> productIds = new ArrayList<>();
            JsonNode idNodes = root.path("productIds");
            if (idNodes.isArray()) {
                for (JsonNode idNode : idNodes) {
                    if (idNode.isNumber()) {
                        productIds.add(idNode.asLong());
                    }
                }
            }
            response.setReply(StrUtil.isBlank(reply) ? content : reply);
            response.setProducts(queryProducts(productIds));
        } catch (Exception e) {
            response.setReply(content);
            response.setProducts(Collections.emptyList());
        }
        return response;
    }

    /**
     * 从模型输出中提取 JSON 片段（兼容 markdown 代码块包裹）
     */
    private String extractJson(String content) {
        String text = content.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
            return text.trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    /**
     * 按商品ID查询在售商品，保证返回的商品数据真实、按推荐顺序排列
     */
    private List<AiProduct> queryProducts(List<Long> productIds) {
        if (productIds.isEmpty()) {
            return Collections.emptyList();
        }
        PmsProductExample example = new PmsProductExample();
        example.createCriteria()
                .andDeleteStatusEqualTo(0)
                .andPublishStatusEqualTo(1)
                .andIdIn(productIds);
        List<PmsProduct> products = productMapper.selectByExample(example);
        Map<Long, PmsProduct> productMap = products.stream()
                .collect(Collectors.toMap(PmsProduct::getId, product -> product));
        return productIds.stream()
                .map(productMap::get)
                .filter(Objects::nonNull)
                .map(this::convert)
                .collect(Collectors.toList());
    }

    private AiProduct convert(PmsProduct product) {
        AiProduct aiProduct = new AiProduct();
        aiProduct.setId(product.getId());
        aiProduct.setName(product.getName());
        aiProduct.setPic(product.getPic());
        aiProduct.setPrice(product.getPrice());
        aiProduct.setBrandName(product.getBrandName());
        aiProduct.setSubTitle(product.getSubTitle());
        return aiProduct;
    }

    /**
     * 加载会话历史（最近 N 条消息）
     */
    private List<Message> loadHistory(String sessionId) {
        Object cached = redisService.get(SESSION_KEY_PREFIX + sessionId);
        if (cached == null) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, String>> history = objectMapper.readValue(
                    cached.toString(), new TypeReference<List<Map<String, String>>>() {
                    });
            return history.stream().map(item -> {
                String role = item.get("role");
                String content = item.get("content");
                if ("user".equals(role)) {
                    return (Message) new UserMessage(content);
                }
                return new AssistantMessage(content);
            }).collect(Collectors.toList());
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * 保存本轮对话到会话历史，超出上限时丢弃最旧消息
     */
    private void saveHistory(String sessionId, String userMessage, String assistantMessage) {
        String key = SESSION_KEY_PREFIX + sessionId;
        List<Map<String, String>> history = new ArrayList<>();
        Object cached = redisService.get(key);
        if (cached != null) {
            try {
                history = objectMapper.readValue(cached.toString(), new TypeReference<List<Map<String, String>>>() {
                });
            } catch (Exception ignored) {
            }
        }
        Map<String, String> userTurn = new HashMap<>();
        userTurn.put("role", "user");
        userTurn.put("content", userMessage);
        Map<String, String> assistantTurn = new HashMap<>();
        assistantTurn.put("role", "assistant");
        assistantTurn.put("content", assistantMessage);
        history.add(userTurn);
        history.add(assistantTurn);
        if (history.size() > maxHistoryMessages) {
            history = new ArrayList<>(history.subList(history.size() - maxHistoryMessages, history.size()));
        }
        try {
            redisService.set(key, objectMapper.writeValueAsString(history), sessionExpire);
        } catch (Exception ignored) {
        }
    }

    /**
     * 意图解析阶段的系统提示词：负责把自然语言转为搜索条件，输出 JSON
     */
    private String buildIntentPrompt() {
        return """
                你是「SenseMall 商城」的好物推荐官「小感」，帮助用户快速、精准地找到想买的商品。

                工作流程：
                1. 当用户表达购物需求时，调用 searchProducts 工具搜索商品；
                2. 根据搜索结果推荐最符合需求的商品，并给出简洁友好的推荐语；
                3. 当用户继续追问（比如换品牌、调整价格、换排序）时，结合对话历史再次调用工具搜索。

                输出要求：
                1. 只推荐 searchProducts 返回结果中真实存在的商品，绝不编造商品；
                2. 最终回答必须是严格的 JSON，格式如下：
                {"reply": "给用户的推荐语，50字以内", "productIds": [1,2,3]}
                3. productIds 从搜索结果中挑选最相关的1-5个商品id，没有合适商品时为空数组；
                4. 如果搜索没有结果，reply 说明未找到并给出建议，productIds 为空数组；
                5. 如果用户闲聊或问与购物无关的问题，礼貌地引导回购物主题，productIds 为空数组。
                """;
    }

    /**
     * 推荐语生成阶段的系统提示词：基于候选商品输出纯文本推荐语（便于流式输出）
     */
    private String buildRecommendPrompt() {
        return """
                你是「SenseMall 商城」的好物推荐官「小感」。用户已经通过商品搜索得到了候选商品列表，请根据用户需求输出推荐语。

                要求：
                1. 只推荐候选商品中真实存在的商品，绝不编造；
                2. 直接输出推荐语本身（纯文本，不要JSON、不要markdown标记），80字以内，简洁直接：点明推荐哪款商品、价格和一句理由即可，不要编号、不要分点；
                3. 候选商品为空时，如实说明暂时没有找到合适的商品，并给出调整预算或换关键词的建议；
                4. 用户闲聊或问与购物无关的问题时，礼貌地引导回购物主题。
                """;
    }
}
