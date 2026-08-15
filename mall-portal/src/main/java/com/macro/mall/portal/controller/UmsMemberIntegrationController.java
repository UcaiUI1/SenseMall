package com.macro.mall.portal.controller;

import com.macro.mall.common.api.CommonPage;
import com.macro.mall.common.api.CommonResult;
import com.macro.mall.model.UmsIntegrationChangeHistory;
import com.macro.mall.model.UmsMember;
import com.macro.mall.portal.domain.IntegrationCheckinStatus;
import com.macro.mall.portal.service.IntegrationService;
import com.macro.mall.portal.service.UmsMemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会员积分接口：签到、明细
 */
@RestController
@Tag(name = "UmsMemberIntegrationController", description = "会员积分")
@RequestMapping("/member/integration")
public class UmsMemberIntegrationController {

    @Autowired
    private IntegrationService integrationService;
    @Autowired
    private UmsMemberService memberService;

    @Operation(summary = "每日签到，返回本次获得积分")
    @PostMapping("/checkin")
    public CommonResult<Integer> checkin() {
        UmsMember member = memberService.getCurrentMember();
        return CommonResult.success(integrationService.checkin(member.getId()));
    }

    @Operation(summary = "签到状态（今日是否已签、连续天数）")
    @GetMapping("/checkin/status")
    public CommonResult<IntegrationCheckinStatus> checkinStatus() {
        UmsMember member = memberService.getCurrentMember();
        return CommonResult.success(integrationService.checkinStatus(member.getId()));
    }

    @Operation(summary = "积分明细")
    @GetMapping("/history")
    public CommonResult<CommonPage<UmsIntegrationChangeHistory>> history(
            @RequestParam(required = false, defaultValue = "1") Integer pageNum,
            @RequestParam(required = false, defaultValue = "10") Integer pageSize) {
        UmsMember member = memberService.getCurrentMember();
        List<UmsIntegrationChangeHistory> list = integrationService.history(member.getId(), pageNum, pageSize);
        return CommonResult.success(CommonPage.restPage(list));
    }
}
