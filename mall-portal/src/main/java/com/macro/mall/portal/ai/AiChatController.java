package com.macro.mall.portal.ai;

import com.macro.mall.common.api.CommonResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 智能导购接口
 */
@RestController
@Tag(name = "AiChatController", description = "AI 好物推荐官")
@RequestMapping("/ai")
public class AiChatController {

    @Autowired
    private AiChatService aiChatService;

    @Operation(summary = "好物推荐官对话")
    @PostMapping("/chat")
    public CommonResult<AiChatResponse> chat(@RequestBody AiChatRequest request) {
        return CommonResult.success(aiChatService.chat(request));
    }

    @Operation(summary = "好物推荐官流式对话（SSE）")
    @PostMapping(value = "/chat/stream", produces = "text/event-stream")
    public SseEmitter chatStream(@RequestBody AiChatRequest request) {
        SseEmitter emitter = new SseEmitter(90_000L);
        aiChatService.chatStream(request, emitter);
        return emitter;
    }
}
