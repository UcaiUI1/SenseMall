import { http } from '@/utils/http'

/** AI 导购推荐的商品卡片 */
export interface AiProduct {
  id: number
  name: string
  pic: string
  price: number
  brandName: string
  subTitle: string
}

/** AI 导购对话响应 */
export interface AiChatResponse {
  sessionId: string
  reply: string
  products: AiProduct[]
}

/**
 * AI 导购对话
 * @param sessionId 会话ID，首次传空由服务端生成
 * @param message 用户消息
 */
export const sendAiChatAPI = (sessionId: string, message: string) => {
  return http<AiChatResponse>({
    method: 'POST',
    url: '/ai/chat',
    timeout: 60000,
    data: { sessionId, message },
  })
}

/**
 * AI 导购流式对话（SSE）
 * H5 环境用 XHR 逐字接收推荐语（打字机效果）；其他端自动回退到非流式接口。
 * @param onDelta 每收到一段文本时的回调，用于实时更新界面
 */
export const streamAiChatAPI = (
  sessionId: string,
  message: string,
  onDelta: (chunk: string) => void,
): Promise<{ sessionId: string; reply: string; products: AiProduct[] }> => {
  return new Promise((resolve, reject) => {
    // 非 H5 环境（小程序/App）回退到普通接口
    if (typeof window === 'undefined' || typeof window.XMLHttpRequest === 'undefined') {
      sendAiChatAPI(sessionId, message)
        .then((res) => resolve({ sessionId: res.data.sessionId, reply: res.data.reply, products: res.data.products }))
        .catch(reject)
      return
    }

    const baseURL = import.meta.env.VITE_API_BASE_URL || ''
    const xhr = new XMLHttpRequest()
    xhr.open('POST', `${baseURL}/ai/chat/stream`)
    xhr.setRequestHeader('Content-Type', 'application/json')
    const token = uni.getStorageSync('token')
    if (token) {
    xhr.setRequestHeader('Authorization', token)
    }
    xhr.responseType = 'text'
    xhr.timeout = 60000

    let reply = ''
    let products: AiProduct[] = []
    let resolvedSessionId = sessionId
    let errorMsg = ''
    let buffer = ''
    let processed = 0

    const flush = () => {
      try {
        const text = xhr.responseText || ''
        if (text.length <= processed) {
          return
        }
        buffer += text.slice(processed)
        processed = text.length
        let idx = buffer.indexOf('\n\n')
        while (idx >= 0) {
          const rawEvent = buffer.slice(0, idx)
          buffer = buffer.slice(idx + 2)
          const dataLine = rawEvent.split('\n').find((line) => line.startsWith('data:'))
          if (dataLine) {
            try {
              const event = JSON.parse(dataLine.slice(5).trim())
              if (event.type === 'session' && event.sessionId) {
                resolvedSessionId = event.sessionId
              } else if (event.type === 'delta' && event.content) {
                reply += event.content
                try {
                  onDelta(event.content)
                } catch (e) {
                  // onDelta 回调异常不应中断流式解析
                }
              } else if (event.type === 'products') {
                products = event.products || []
              } else if (event.type === 'error') {
                errorMsg = event.message || 'AI 服务异常'
              }
            } catch (e) {
              // 忽略无法解析的事件片段
            }
          }
          idx = buffer.indexOf('\n\n')
        }
      } catch (e) {
        // 单次解析异常不应导致流中断
      }
    }

    xhr.onprogress = flush
    xhr.onload = () => {
      try {
        flush()
      } catch (e) {
        errorMsg = errorMsg || '解析响应失败'
      }
      if (errorMsg) {
        reject(new Error(errorMsg))
      } else {
        resolve({ sessionId: resolvedSessionId, reply, products })
      }
    }
    xhr.onerror = () => reject(new Error('网络错误，请稍后再试'))
    xhr.ontimeout = () => reject(new Error('响应超时，请稍后再试'))
    xhr.send(JSON.stringify({ sessionId, message }))
  })
}
