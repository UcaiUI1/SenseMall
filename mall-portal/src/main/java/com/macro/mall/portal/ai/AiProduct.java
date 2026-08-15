package com.macro.mall.portal.ai;

import lombok.Data;

import java.math.BigDecimal;

/**
 * AI 导购返回的商品卡片信息
 */
@Data
public class AiProduct {

    private Long id;

    private String name;

    private String pic;

    private BigDecimal price;

    private String brandName;

    private String subTitle;
}
