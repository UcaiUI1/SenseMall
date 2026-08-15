<template>
  <view class="container">
    <!-- 积分余额卡片 -->
    <view class="balance-card">
      <view class="balance-left">
        <text class="balance-label">我的积分</text>
        <text class="balance-num">{{ memberInfo?.integration ?? 0 }}</text>
      </view>
      <view class="balance-right">
        <text class="rule-line">100 积分 = 1 元</text>
        <text class="rule-line">下单支付即送积分</text>
      </view>
    </view>

    <!-- 签到 -->
    <view class="checkin-card">
      <view class="checkin-info">
        <text class="checkin-title">每日签到</text>
        <text class="checkin-sub">连续 {{ checkinStatus?.streak || 0 }} 天 · 连续 7 天额外 +20</text>
      </view>
      <view
        class="checkin-btn"
        :class="{ checked: checkinStatus?.checked }"
        @click="handleCheckin"
      >
        <text>{{ checkinStatus?.checked ? '已签到' : '签到' }}</text>
      </view>
    </view>

    <!-- 规则说明 -->
    <view class="rules-card">
      <text class="rules-title">积分规则</text>
      <view class="rule-item">
        <text class="rule-dot"></text>
        <text class="rule-text">每日签到 +5 积分，连续签到第 7 天额外 +20</text>
      </view>
      <view class="rule-item">
        <text class="rule-dot"></text>
        <text class="rule-text">新用户注册即送 100 积分</text>
      </view>
      <view class="rule-item">
        <text class="rule-dot"></text>
        <text class="rule-text">购物每消费 1 元送 1 积分（支付成功后到账）</text>
      </view>
      <view class="rule-item">
        <text class="rule-dot"></text>
        <text class="rule-text">下单可用积分抵扣现金，100 积分抵 1 元，单笔最高抵 50%</text>
      </view>
    </view>

    <!-- 明细 -->
    <view class="history-header">
      <text class="history-title">积分明细</text>
    </view>
    <view v-if="historyList.length === 0" class="history-empty">
      <text>暂无积分记录</text>
    </view>
    <view v-for="item in historyList" :key="item.id" class="history-item">
      <view class="history-info">
        <text class="history-note">{{ item.operateNote || sourceText(item.sourceType) }}</text>
        <text class="history-time">{{ formatTime(item.createTime) }}</text>
      </view>
      <view class="history-count" :class="item.changeType === 0 ? 'plus' : 'minus'">
        {{ item.changeType === 0 ? '+' : '-' }}{{ item.changeCount }}
      </view>
    </view>
    <uni-load-more :status="loadingType"></uni-load-more>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onLoad, onReachBottom } from '@dcloudio/uni-app'
import {
  checkinAPI,
  getCheckinStatusAPI,
  getIntegrationHistoryAPI,
  type IntegrationCheckinStatus,
  type IntegrationHistoryItem,
} from '@/apis/integration'
import { getMemberInfoAPI } from '@/apis/member'
import { useMemberStore } from '@/stores/member'
import type { PageParam } from '@/types/common'

const memberStore = useMemberStore()
const memberInfo = ref(memberStore.memberInfo)
const checkinStatus = ref<IntegrationCheckinStatus | null>(null)
const historyList = ref<IntegrationHistoryItem[]>([])
const pageParam = ref<PageParam>({ pageNum: 1, pageSize: 10 })
const loadingType = ref<'more' | 'loading' | 'noMore'>('more')

const sourceText = (type: number) => {
  const map: Record<number, string> = {
    0: '购物赠送',
    1: '管理员调整',
    2: '每日签到',
    3: '注册奖励',
    4: '订单抵扣',
    5: '取消退回',
  }
  return map[type] || '积分变动'
}

const formatTime = (time: string) => {
  if (!time) return ''
  return time.replace('T', ' ').slice(0, 16)
}

const loadHistory = async () => {
  try {
    const res = await getIntegrationHistoryAPI(pageParam.value)
    historyList.value = res.data.list || []
    loadingType.value = res.data.list.length < pageParam.value.pageSize ? 'noMore' : 'more'
  } catch (e) {
    historyList.value = []
  }
}

const handleCheckin = async () => {
  if (checkinStatus.value?.checked) return
  try {
    const res = await checkinAPI()
    uni.showToast({ icon: 'none', title: `签到成功 +${res.data} 积分` })
    // 刷新状态与会员积分
    const info = await getMemberInfoAPI()
    memberStore.setMemberInfo(info.data)
    memberInfo.value = info.data
    await Promise.all([refreshStatus(), loadHistory()])
  } catch (e) {
    uni.showToast({ icon: 'none', title: '签到失败，请稍后再试' })
  }
}

const refreshStatus = async () => {
  try {
    const res = await getCheckinStatusAPI()
    checkinStatus.value = res.data
  } catch (e) {
    checkinStatus.value = null
  }
}

onLoad(() => {
  refreshStatus()
  loadHistory()
})

onReachBottom(() => {
  if (loadingType.value === 'noMore') return
  pageParam.value.pageNum++
  loadingType.value = 'loading'
  getIntegrationHistoryAPI(pageParam.value)
    .then((res) => {
      const list = res.data.list || []
      historyList.value = historyList.value.concat(list)
      loadingType.value = list.length < pageParam.value.pageSize ? 'noMore' : 'more'
    })
    .catch(() => {
      pageParam.value.pageNum--
      loadingType.value = 'more'
    })
})
</script>

<style lang="scss" scoped>
.container {
  min-height: 100vh;
  background: #f5f6f8;
  padding-bottom: 40rpx;
}

.balance-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 24rpx;
  padding: 44rpx 36rpx;
  border-radius: 20rpx;
  background: linear-gradient(135deg, #ff7a8a, #fa436a);
  box-shadow: 0 10rpx 28rpx rgba(250, 67, 106, 0.25);
}

.balance-left {
  display: flex;
  flex-direction: column;
}

.balance-label {
  font-size: 26rpx;
  color: rgba(255, 255, 255, 0.9);
}

.balance-num {
  margin-top: 10rpx;
  font-size: 64rpx;
  font-weight: 700;
  color: #ffffff;
}

.balance-right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.rule-line {
  font-size: 22rpx;
  color: rgba(255, 255, 255, 0.85);
  margin-top: 8rpx;
}

.checkin-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 0 24rpx 24rpx;
  padding: 30rpx;
  background: #ffffff;
  border-radius: 16rpx;
}

.checkin-title {
  font-size: 30rpx;
  font-weight: 600;
  color: #303133;
}

.checkin-sub {
  margin-top: 8rpx;
  font-size: 22rpx;
  color: #909399;
}

.checkin-btn {
  padding: 18rpx 40rpx;
  border-radius: 40rpx;
  background: linear-gradient(135deg, #ff7a8a, #fa436a);
  color: #ffffff;
  font-size: 28rpx;
  font-weight: 600;

  &.checked {
    background: #c0c4cc;
  }
}

.rules-card {
  margin: 0 24rpx 24rpx;
  padding: 28rpx 30rpx;
  background: #ffffff;
  border-radius: 16rpx;
}

.rules-title {
  font-size: 28rpx;
  font-weight: 600;
  color: #303133;
}

.rule-item {
  display: flex;
  align-items: flex-start;
  margin-top: 16rpx;
}

.rule-dot {
  width: 10rpx;
  height: 10rpx;
  border-radius: 50%;
  background: #fa436a;
  margin: 12rpx 14rpx 0 4rpx;
  flex-shrink: 0;
}

.rule-text {
  flex: 1;
  font-size: 24rpx;
  color: #606266;
  line-height: 1.6;
}

.history-header {
  margin: 8rpx 30rpx 20rpx;
}

.history-title {
  font-size: 28rpx;
  font-weight: 600;
  color: #303133;
}

.history-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 0 24rpx 16rpx;
  padding: 24rpx 28rpx;
  background: #ffffff;
  border-radius: 14rpx;
}

.history-note {
  font-size: 26rpx;
  color: #303133;
}

.history-time {
  margin-top: 8rpx;
  font-size: 22rpx;
  color: #c0c4cc;
}

.history-count {
  font-size: 32rpx;
  font-weight: 700;

  &.plus {
    color: #fa436a;
  }

  &.minus {
    color: #303133;
  }
}

.history-empty {
  text-align: center;
  padding: 60rpx 0;
  color: #c0c4cc;
  font-size: 26rpx;
}
</style>
