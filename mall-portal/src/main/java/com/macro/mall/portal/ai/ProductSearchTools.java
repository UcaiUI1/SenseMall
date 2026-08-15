package com.macro.mall.portal.ai;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.mapper.PmsBrandMapper;
import com.macro.mall.mapper.PmsProductCategoryMapper;
import com.macro.mall.model.PmsBrand;
import com.macro.mall.model.PmsBrandExample;
import com.macro.mall.model.PmsProduct;
import com.macro.mall.model.PmsProductCategory;
import com.macro.mall.model.PmsProductCategoryExample;
import com.macro.mall.portal.service.PmsPortalProductService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * AI 导购可调用的商品搜索工具。
 * 模型通过 Function Calling 调用本类中的方法，将自然语言转换为结构化搜索条件。
 */
@Component
public class ProductSearchTools {

    /**
     * 工具执行结果暂存区：按请求ID保存本次搜索到的商品，供流式接口在推荐前读取
     */
    private final Map<String, List<AiProduct>> searchResultHolder = new ConcurrentHashMap<>();

    @Autowired
    private PmsPortalProductService portalProductService;
    @Autowired
    private PmsBrandMapper brandMapper;
    @Autowired
    private PmsProductCategoryMapper productCategoryMapper;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MallSearchClient mallSearchClient;

    @Tool(description = "根据关键词、品牌、分类、价格区间和排序方式搜索商城在售商品，返回商品列表（最多8个）")
    public String searchProducts(
            @ToolParam(description = "商品关键词，如：手机、耳机；用户没有明确提及时传空字符串") String keyword,
            @ToolParam(description = "品牌名称，如：小米、华为；不确定时传空字符串") String brandName,
            @ToolParam(description = "商品分类名称，如：手机通讯、家用电器；不确定时传空字符串") String categoryName,
            @ToolParam(description = "最低价格（元），没有限制时传 0") BigDecimal priceMin,
            @ToolParam(description = "最高价格（元），没有限制时传 0") BigDecimal priceMax,
            @ToolParam(description = "排序方式：0综合、1新品、2销量、3价格从低到高、4价格从高到低，默认0") Integer sort,
            ToolContext toolContext) {
        Long brandId = resolveBrandId(brandName);
        Long categoryId = resolveCategoryId(categoryName);
        // 优先使用语义（混合）检索，mall-search 不可用或结果为空时回退数据库检索
        List<AiProduct> aiProducts = mallSearchClient.semanticSearch(
                StrUtil.trimToNull(keyword),
                brandId,
                categoryId,
                normalizePrice(priceMin),
                normalizePrice(priceMax),
                8);
        if (aiProducts.isEmpty()) {
            List<PmsProduct> productList = portalProductService.search(
                    StrUtil.trimToNull(keyword),
                    brandId,
                    categoryId,
                    normalizePrice(priceMin),
                    normalizePrice(priceMax),
                    1,
                    8,
                    sort == null ? 0 : sort);
            aiProducts = productList.stream().map(this::convert).collect(Collectors.toList());
        }
        if (toolContext != null) {
            String requestId = (String) toolContext.getContext().get(AiChatServiceImpl.REQUEST_ID_KEY);
            if (requestId != null) {
                searchResultHolder.put(requestId, aiProducts);
            }
        }
        try {
            return objectMapper.writeValueAsString(aiProducts);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    /**
     * 模型未指定价格区间时会传 0，此时视为无限制
     */
    private BigDecimal normalizePrice(BigDecimal price) {
        return (price != null && price.compareTo(BigDecimal.ZERO) > 0) ? price : null;
    }

    public List<AiProduct> takeSearchResult(String requestId) {
        return searchResultHolder.remove(requestId);
    }

    /**
     * 根据品牌名称模糊匹配品牌ID，匹配不到返回 null（不限制品牌）
     */
    private Long resolveBrandId(String brandName) {
        if (StrUtil.isBlank(brandName)) {
            return null;
        }
        PmsBrandExample example = new PmsBrandExample();
        example.createCriteria().andNameLike("%" + brandName.trim() + "%");
        List<PmsBrand> brands = brandMapper.selectByExample(example);
        return brands.isEmpty() ? null : brands.get(0).getId();
    }

    /**
     * 根据分类名称模糊匹配分类ID，匹配不到返回 null（不限制分类）
     */
    private Long resolveCategoryId(String categoryName) {
        if (StrUtil.isBlank(categoryName)) {
            return null;
        }
        PmsProductCategoryExample example = new PmsProductCategoryExample();
        example.createCriteria().andNameLike("%" + categoryName.trim() + "%");
        List<PmsProductCategory> categories = productCategoryMapper.selectByExample(example);
        return categories.isEmpty() ? null : categories.get(0).getId();
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
}
