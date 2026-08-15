package com.macro.mall.portal.service.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.github.pagehelper.PageHelper;
import com.macro.mall.common.exception.Asserts;
import com.macro.mall.common.service.RedisService;
import com.macro.mall.mapper.UmsIntegrationChangeHistoryMapper;
import com.macro.mall.mapper.UmsMemberMapper;
import com.macro.mall.model.UmsIntegrationChangeHistory;
import com.macro.mall.model.UmsIntegrationChangeHistoryExample;
import com.macro.mall.model.UmsMember;
import com.macro.mall.portal.domain.IntegrationCheckinStatus;
import com.macro.mall.portal.service.IntegrationService;
import com.macro.mall.portal.service.UmsMemberService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 积分服务实现
 */
@Service
public class IntegrationServiceImpl implements IntegrationService {

    private static final String CHECKIN_KEY_PREFIX = "ums:checkin:";
    private static final String CHECKIN_STREAK_KEY_PREFIX = "ums:checkin:streak:";
    private static final String CHECKIN_LAST_KEY_PREFIX = "ums:checkin:last:";

    /** 签到基础积分 */
    private static final int CHECKIN_BASE_POINTS = 5;
    /** 连续 7 天额外奖励 */
    private static final int CHECKIN_WEEK_BONUS = 20;

    @Autowired
    @Lazy
    private UmsMemberService memberService;
    @Autowired
    private UmsMemberMapper memberMapper;
    @Autowired
    private UmsIntegrationChangeHistoryMapper historyMapper;
    @Autowired
    private RedisService redisService;

    @Override
    public void earn(Long memberId, Integer count, Integer sourceType, String note, Long orderId) {
        UmsMember member = memberMapper.selectByPrimaryKey(memberId);
        if (member == null || count == null || count <= 0) {
            return;
        }
        int current = member.getIntegration() == null ? 0 : member.getIntegration();
        int balance = current + count;
        memberService.updateIntegration(memberId, balance);
        record(memberId, 0, count, sourceType, note, orderId, balance);
    }

    @Override
    public void spend(Long memberId, Integer count, Integer sourceType, String note, Long orderId) {
        UmsMember member = memberMapper.selectByPrimaryKey(memberId);
        if (member == null || count == null || count <= 0) {
            return;
        }
        int current = member.getIntegration() == null ? 0 : member.getIntegration();
        if (current < count) {
            Asserts.fail("积分不足");
        }
        int balance = current - count;
        memberService.updateIntegration(memberId, balance);
        record(memberId, 1, count, sourceType, note, orderId, balance);
    }

    @Override
    public Integer checkin(Long memberId) {
        String today = DateUtil.format(new Date(), "yyyyMMdd");
        String dayKey = CHECKIN_KEY_PREFIX + memberId + ":" + today;
        if (Boolean.TRUE.equals(redisService.hasKey(dayKey))) {
            Asserts.fail("今天已经签到过了");
        }
        // 计算连续签到天数
        int streak = 1;
        Object last = redisService.get(CHECKIN_LAST_KEY_PREFIX + memberId);
        if (last != null && isYesterday(last.toString(), today)) {
            Object cachedStreak = redisService.get(CHECKIN_STREAK_KEY_PREFIX + memberId);
            streak = (cachedStreak instanceof Integer) ? ((Integer) cachedStreak) + 1 : 2;
        }
        int points = CHECKIN_BASE_POINTS + (streak % 7 == 0 ? CHECKIN_WEEK_BONUS : 0);
        redisService.set(dayKey, 1, 2 * 24 * 3600);
        redisService.set(CHECKIN_LAST_KEY_PREFIX + memberId, today, 3 * 24 * 3600);
        redisService.set(CHECKIN_STREAK_KEY_PREFIX + memberId, streak, 3 * 24 * 3600);
        earn(memberId, points, 2, "每日签到（连续" + streak + "天）", null);
        return points;
    }

    @Override
    public IntegrationCheckinStatus checkinStatus(Long memberId) {
        String today = DateUtil.format(new Date(), "yyyyMMdd");
        IntegrationCheckinStatus status = new IntegrationCheckinStatus();
        status.setChecked(Boolean.TRUE.equals(redisService.hasKey(CHECKIN_KEY_PREFIX + memberId + ":" + today)));
        Object cachedStreak = redisService.get(CHECKIN_STREAK_KEY_PREFIX + memberId);
        int streak = (cachedStreak instanceof Integer) ? ((Integer) cachedStreak) : 0;
        status.setStreak(streak);
        status.setPoints(CHECKIN_BASE_POINTS + (streak > 0 && streak % 7 == 0 ? CHECKIN_WEEK_BONUS : 0));
        return status;
    }

    @Override
    public List<UmsIntegrationChangeHistory> history(Long memberId, Integer pageNum, Integer pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        UmsIntegrationChangeHistoryExample example = new UmsIntegrationChangeHistoryExample();
        example.createCriteria().andMemberIdEqualTo(memberId);
        example.setOrderByClause("create_time desc");
        return historyMapper.selectByExample(example);
    }

    /**
     * 写积分台账
     */
    private void record(Long memberId, int changeType, int changeCount, int sourceType,
                        String note, Long orderId, int balanceAfter) {
        UmsIntegrationChangeHistory history = new UmsIntegrationChangeHistory();
        history.setMemberId(memberId);
        history.setCreateTime(new Date());
        history.setChangeType(changeType);
        history.setChangeCount(changeCount);
        history.setOperateMan("member");
        history.setOperateNote(StrUtil.nullToEmpty(note));
        history.setSourceType(sourceType);
        history.setOrderId(orderId);
        history.setBalanceAfter(balanceAfter);
        historyMapper.insertSelective(history);
    }

    private boolean isYesterday(String lastDate, String today) {
        try {
            Date last = DateUtil.parse(lastDate, "yyyyMMdd");
            Date todayDate = DateUtil.parse(today, "yyyyMMdd");
            return DateUtil.betweenDay(last, todayDate, false) == 1;
        } catch (Exception e) {
            return false;
        }
    }
}
