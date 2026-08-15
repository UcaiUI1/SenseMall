package com.macro.mall.portal.ai;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 智能导购服务
 */
public interface AiChatService {

    /**
     * 与 AI 导购进行一轮对话
     */
    AiChatResponse chat(AiChatRequest request);

    /**
     * 与 AI 导购进行流式对话，推荐语以 SSE 事件推送给客户端
     */
    void chatStream(AiChatRequest request, SseEmitter emitter);
}
