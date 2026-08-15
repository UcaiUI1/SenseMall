package com.macro.mall.portal.dao;

import com.macro.mall.model.SmsCoupon;
import com.macro.mall.model.PmsProduct;
import com.macro.mall.portal.domain.CartProduct;
import com.macro.mall.portal.domain.PromotionProduct;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

/**
 * 前台购物车商品管理自定义Dao
 * Created by macro on 2018/8/2.
 */
public interface PortalProductDao {
    /**
     * 获取购物车商品信息
     */
    CartProduct getCartProduct(@Param("id") Long id);

    /**
     * 获取促销商品信息列表
     */
    List<PromotionProduct> getPromotionProductList(@Param("ids") List<Long> ids);

    /**
     * 获取可用优惠券列表
     */
    List<SmsCoupon> getAvailableCouponList(@Param("productId") Long productId, @Param("productCategoryId") Long productCategoryId);

    /**
     * 综合搜索商品（关键词同时匹配名称/副标题/关键词字段）
     */
    List<PmsProduct> searchProducts(@Param("keyword") String keyword,
                                    @Param("brandId") Long brandId,
                                    @Param("productCategoryId") Long productCategoryId,
                                    @Param("priceMin") BigDecimal priceMin,
                                    @Param("priceMax") BigDecimal priceMax,
                                    @Param("sort") Integer sort);
}
