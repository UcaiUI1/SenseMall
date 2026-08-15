<template>
  <view class="container">
    <view class="empty">
      <text class="empty-icon">🔔</text>
      <text class="empty-text">暂无消息通知</text>
      <text class="empty-sub">有新消息时会第一时间提醒你</text>
    </view>
  </view>
</template>

<script setup lang="ts"></script>

<style lang="scss" scoped>
.container {
  min-height: 100vh;
  background: #f5f6f8;
}

.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-top: 220rpx;
}

.empty-icon {
  font-size: 100rpx;
}

.empty-text {
  margin-top: 28rpx;
  font-size: 30rpx;
  color: #303133;
  font-weight: 600;
}

.empty-sub {
  margin-top: 12rpx;
  font-size: 24rpx;
  color: #c0c4cc;
}
</style>
