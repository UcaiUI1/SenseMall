<template>
  <view class="container">
    <view class="form-card">
      <view class="form-item">
        <text class="form-label">手机号</text>
        <input v-model="form.telephone" class="form-input" type="number" maxlength="11" placeholder="请输入注册手机号" />
      </view>
      <view class="form-item">
        <text class="form-label">验证码</text>
        <input v-model="form.authCode" class="form-input" type="number" maxlength="6" placeholder="请输入验证码" />
        <view class="code-btn" :class="{ disabled: countdown > 0 }" @click="getCode">
          {{ countdown > 0 ? `${countdown}s` : '获取验证码' }}
        </view>
      </view>
      <view class="form-item">
        <text class="form-label">新密码</text>
        <input v-model="form.password" class="form-input" password placeholder="请输入新密码" />
      </view>
    </view>
    <button class="save-btn" :disabled="saving" @click="save">{{ saving ? '提交中...' : '确认修改' }}</button>
  </view>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { getAuthCodeAPI, updatePasswordAPI } from '@/apis/member'

const saving = ref(false)
const countdown = ref(0)
const form = reactive({
  telephone: '',
  authCode: '',
  password: '',
})

const getCode = async () => {
  if (countdown.value > 0) return
  if (!/^1\d{10}$/.test(form.telephone)) {
    uni.showToast({ icon: 'none', title: '请输入正确的手机号' })
    return
  }
  try {
    await getAuthCodeAPI(form.telephone)
    uni.showToast({ icon: 'none', title: '验证码已发送' })
    countdown.value = 60
    const timer = setInterval(() => {
      countdown.value--
      if (countdown.value <= 0) clearInterval(timer)
    }, 1000)
  } catch (e) {
    uni.showToast({ icon: 'none', title: '验证码获取失败' })
  }
}

const save = async () => {
  if (!/^1\d{10}$/.test(form.telephone)) {
    uni.showToast({ icon: 'none', title: '请输入正确的手机号' })
    return
  }
  if (!form.authCode || !form.password) {
    uni.showToast({ icon: 'none', title: '请填写验证码和新密码' })
    return
  }
  saving.value = true
  try {
    const res = await updatePasswordAPI({ telephone: form.telephone, password: form.password, authCode: form.authCode })
    uni.showToast({ icon: 'none', title: res.message || '密码修改成功' })
    setTimeout(() => uni.navigateBack(), 1200)
  } catch (e) {
    uni.showToast({ icon: 'none', title: '修改失败，请稍后再试' })
  } finally {
    saving.value = false
  }
}
</script>

<style lang="scss" scoped>
.container {
  min-height: 100vh;
  background: #f5f6f8;
  padding: 24rpx;
  box-sizing: border-box;
}

.form-card {
  background: #ffffff;
  border-radius: 16rpx;
  padding: 8rpx 24rpx;
}

.form-item {
  display: flex;
  align-items: center;
  padding: 26rpx 0;
  border-bottom: 1rpx solid #f5f5f5;

  &:last-child {
    border-bottom: none;
  }
}

.form-label {
  width: 150rpx;
  font-size: 28rpx;
  color: #303133;
  flex-shrink: 0;
}

.form-input {
  flex: 1;
  font-size: 28rpx;
}

.code-btn {
  padding: 12rpx 24rpx;
  border-radius: 30rpx;
  font-size: 24rpx;
  color: #fa436a;
  background: #fff0f3;

  &.disabled {
    color: #909399;
    background: #f5f5f5;
  }
}

.save-btn {
  margin-top: 40rpx;
  background: linear-gradient(135deg, #ff7a8a, #fa436a);
  color: #ffffff;
  border-radius: 44rpx;
  font-size: 30rpx;

  &[disabled] {
    opacity: 0.5;
  }
}
</style>
