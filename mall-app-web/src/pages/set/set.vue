<template>
  <view class="container">
    <view class="list-cell b-b m-t" @click="navTo('/pages/user/profile')" hover-class="cell-hover" :hover-stay-time="50">
      <text class="cell-tit">个人资料</text>
      <text class="cell-more yticon icon-you"></text>
    </view>
    <view class="list-cell b-b" @click="navTo('/pages/address/address')" hover-class="cell-hover" :hover-stay-time="50">
      <text class="cell-tit">收货地址</text>
      <text class="cell-more yticon icon-you"></text>
    </view>
    <view class="list-cell" @click="navTo('/pages/user/changePassword')" hover-class="cell-hover" :hover-stay-time="50">
      <text class="cell-tit">修改密码</text>
      <text class="cell-more yticon icon-you"></text>
    </view>

    <view class="list-cell m-t">
      <text class="cell-tit">消息推送</text>
      <switch :checked="pushEnabled" color="#fa436a" @change="switchChange" />
    </view>
    <view class="list-cell m-t b-b" @click="clearCache" hover-class="cell-hover" :hover-stay-time="50">
      <text class="cell-tit">清除缓存</text>
      <text class="cell-more yticon icon-you"></text>
    </view>
    <view class="list-cell b-b" @click="openGithub" hover-class="cell-hover" :hover-stay-time="50">
      <text class="cell-tit">GitHub 项目</text>
      <text class="cell-more yticon icon-you"></text>
    </view>
    <view class="list-cell" @click="checkUpdate" hover-class="cell-hover" :hover-stay-time="50">
      <text class="cell-tit">检查更新</text>
      <text class="cell-more yticon icon-you"></text>
    </view>
    <view class="list-cell log-out-btn" @click="toLogout">
      <text class="cell-tit">退出登录</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useMemberStore } from '@/stores/member'

const memberStore = useMemberStore()

// 消息推送开关（本地偏好）
const pushEnabled = ref(uni.getStorageSync('push_enabled') !== 'off')

const toLogout = () => {
  uni.showModal({
    content: '确定要退出登录么',
    success: (e) => {
      if (e.confirm) {
        memberStore.memberLogout()
        uni.showToast({ title: '已退出登录', icon: 'success' })
        setTimeout(() => uni.navigateBack(), 200)
      }
    },
  })
}

const navTo = (url: string) => {
  uni.navigateTo({ url })
}

const switchChange = (e: any) => {
  const on = e.detail.value
  uni.setStorageSync('push_enabled', on ? 'on' : 'off')
  uni.showToast({ icon: 'none', title: on ? '消息推送已开启' : '消息推送已关闭' })
}

const clearCache = () => {
  uni.showModal({
    content: '确定要清除本地缓存吗？',
    success: (e) => {
      if (e.confirm) {
        const token = uni.getStorageSync('token')
        const username = uni.getStorageSync('username')
        uni.clearStorageSync()
        if (token) uni.setStorageSync('token', token)
        if (username) uni.setStorageSync('username', username)
        uni.showToast({ title: '缓存已清除', icon: 'success' })
      }
    },
  })
}

// 直达 GitHub 项目
const openGithub = () => {
  const url = 'https://github.com/UcaiUI1/mall'
  // #ifdef H5
  window.location.href = url
  // #endif
  // #ifdef APP-PLUS
  plus.runtime.openURL(url)
  // #endif
  // #ifdef MP
  uni.setClipboardData({
    data: url,
    success: () => uni.showToast({ title: '项目地址已复制', icon: 'none' }),
  })
  // #endif
}

const checkUpdate = () => {
  uni.showToast({ icon: 'none', title: '当前已是最新版本 v1.0.0' })
}
</script>

<style lang="scss">
page {
  background: #f5f6f8;
}

.container {
  padding: 20rpx 24rpx 60rpx;
}

.list-cell {
  display: flex;
  align-items: center;
  padding: 28rpx 24rpx;
  background: #ffffff;
  font-size: 28rpx;
  color: #303133;

  &.m-t {
    margin-top: 20rpx;
  }

  &.b-b {
    border-bottom: 1rpx solid #f5f5f5;
  }

  &.log-out-btn {
    margin-top: 20rpx;
    justify-content: center;
    color: #fa436a;
  }
}

.cell-tit {
  flex: 1;
}

.cell-tip {
  font-size: 24rpx;
  color: #909399;
  margin-right: 8rpx;
}

.cell-more {
  font-size: 28rpx;
  color: #c0c4cc;
}
</style>
