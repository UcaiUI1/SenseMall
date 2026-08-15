package com.macro.mall.portal.ai;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * mall-search 服务客户端：用于 AI 导购的语义（混合）检索
 */
@Component
public class MallSearchClient {

    @Value("${mall.search.base-url:http://localhost:8081}")
    private String baseUrl;

    private final RestClient restClient = RestClient.create();

    /**
     * 调用 mall-search 语义检索接口，返回商品卡片；服务不可用时返回空列表（由调用方回退）
     */
    public List<AiProduct> semanticSearch(String keyword, Long brandId, Long categoryId,
                                          BigDecimal priceMin, BigDecimal priceMax, int pageSize) {
        try {
            JsonNode response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/esProduct/search/semantic")
                            .queryParam("keyword", StrUtil.nullToEmpty(keyword))
                            .queryParam("brandId", brandId == null ? "" : brandId)
                            .queryParam("productCategoryId", categoryId == null ? "" : categoryId)
                            .queryParam("priceMin", priceMin == null ? "" : priceMin)
                            .queryParam("priceMax", priceMax == null ? "" : priceMax)
                            .queryParam("pageNum", 1)
                            .queryParam("pageSize", pageSize)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode list = response.path("data").path("list");
            List<AiProduct> products = new ArrayList<>();
            if (list.isArray()) {
                list.forEach(node -> products.add(convert(node)));
            }
            return products;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private AiProduct convert(JsonNode node) {
        AiProduct product = new AiProduct();
        product.setId(node.path("id").asLong());
        product.setName(node.path("name").asText());
        product.setPic(node.path("pic").asText());
        product.setBrandName(node.path("brandName").asText());
        product.setSubTitle(node.path("subTitle").asText());
        if (node.path("price").isNumber()) {
            product.setPrice(node.path("price").decimalValue());
        }
        return product;
    }
}
