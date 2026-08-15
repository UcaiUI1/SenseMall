package com.macro.mall.portal.service;

import com.macro.mall.model.UmsIntegrationChangeHistory;
import com.macro.mall.portal.domain.IntegrationCheckinStatus;

import java.util.List;

/**
 * 积分服务：积分获取/消费、签到、明细台账
 */
public interface IntegrationService {

    /**
     * 积分入账（增加）
     * @param sourceType 0购物 2签到 3注册
     */
    void earn(Long memberId, Integer count, Integer sourceType, String note, Long orderId);

    /**
     * 积分扣减（减少）
     * @param sourceType 4订单抵扣 5订单取消退回（退回走 earn）
     */
    void spend(Long memberId, Integer count, Integer sourceType, String note, Long orderId);

    /**
     * 每日签到，返回本次获得积分
     */
    Integer checkin(Long memberId);

    /**
     * 签到状态（今日是否已签、连续天数）
     */
    IntegrationCheckinStatus checkinStatus(Long memberId);

    /**
     * 积分明细（分页）
     */
    List<UmsIntegrationChangeHistory> history(Long memberId, Integer pageNum, Integer pageSize);
}
