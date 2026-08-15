package com.macro.mall.portal.domain;

import lombok.Data;

/**
 * 签到状态
 */
@Data
public class IntegrationCheckinStatus {

    /** 今日是否已签到 */
    private Boolean checked;

    /** 连续签到天数 */
    private Integer streak;

    /** 连续签到获得的积分 */
    private Integer points;
}
