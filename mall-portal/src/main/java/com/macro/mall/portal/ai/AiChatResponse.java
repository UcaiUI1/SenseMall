package com.macro.mall.portal.ai;

import lombok.Data;

import java.util.List;

/**
 * AI 导购对话响应
 */
@Data
public class AiChatResponse {

    private String sessionId;

    /**
     * 导购回复文本
     */
    private String reply;

    /**
     * 推荐的商品卡片列表
     */
    private List<AiProduct> products;
}
