package com.macro.mall.search.component;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文本向量化服务：调用通义千问 text-embedding-v3（DashScope OpenAI 兼容接口）
 */
@Component
public class EmbeddingService {
    private static final Logger LOGGER = LoggerFactory.getLogger(EmbeddingService.class);

    @Value("${mall.search.embedding.api-key:}")
    private String apiKey;
    @Value("${mall.search.embedding.model:text-embedding-v3}")
    private String model;
    @Value("${mall.search.embedding.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String baseUrl;

    private RestClient restClient;

    @PostConstruct
    public void init() {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * 将一段文本转为向量
     */
    public List<Float> embed(String text) {
        if (StrUtil.isBlank(apiKey)) {
            throw new IllegalStateException("未配置 mall.search.embedding.api-key（DASHSCOPE_API_KEY）");
        }
        JsonNode response = restClient.post()
                .uri("/embeddings")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("model", model, "input", List.of(text)))
                .retrieve()
                .body(JsonNode.class);
        JsonNode embedding = response.path("data").get(0).path("embedding");
        List<Float> vector = new ArrayList<>();
        if (embedding.isArray()) {
            embedding.forEach(node -> vector.add(node.floatValue()));
        }
        if (vector.isEmpty()) {
            LOGGER.warn("向量生成结果为空: {}", response);
        }
        return vector;
    }
}
