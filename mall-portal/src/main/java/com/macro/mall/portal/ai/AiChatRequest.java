package com.macro.mall.portal.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * AI 导购对话请求
 */
@Data
public class AiChatRequest {

    @Schema(description = "会话ID，首次对话可传空，由服务端生成并返回")
    private String sessionId;

    @Schema(description = "用户输入的消息", requiredMode = Schema.RequiredMode.REQUIRED)
    private String message;
}
