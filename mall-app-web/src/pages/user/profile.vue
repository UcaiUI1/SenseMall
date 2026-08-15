<template>
  <view class="container">
    <view class="form-card">
      <view class="form-item">
        <text class="form-label">昵称</text>
        <input v-model="form.nickname" class="form-input" placeholder="请输入昵称" />
      </view>
      <view class="form-item">
        <text class="form-label">性别</text>
        <view class="gender-group">
          <view
            v-for="g in genders"
            :key="g.value"
            class="gender-tag"
            :class="{ active: form.gender === g.value }"
            @click="form.gender = g.value"
          >
            {{ g.label }}
          </view>
        </view>
      </view>
      <view class="form-item">
        <text class="form-label">生日</text>
        <picker mode="date" :value="form.birthday || ''" @change="onBirthdayChange">
          <view class="form-picker">{{ form.birthday || '请选择生日' }}</view>
        </picker>
      </view>
      <view class="form-item">
        <text class="form-label">城市</text>
        <input v-model="form.city" class="form-input" placeholder="如：深圳" />
      </view>
      <view class="form-item">
        <text class="form-label">职业</text>
        <input v-model="form.job" class="form-input" placeholder="如：学生 / 工程师" />
      </view>
      <view class="form-item form-textarea-item">
        <text class="form-label">个性签名</text>
        <textarea v-model="form.personalizedSignature" class="form-textarea" placeholder="介绍一下自己（选填）" />
      </view>
    </view>
    <button class="save-btn" :disabled="saving" @click="save">{{ saving ? '保存中...' : '保存' }}</button>
  </view>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { getMemberInfoAPI, updateProfileAPI } from '@/apis/member'
import { useMemberStore } from '@/stores/member'

const memberStore = useMemberStore()
const saving = ref(false)
const genders = [
  { label: '保密', value: 0 },
  { label: '男', value: 1 },
  { label: '女', value: 2 },
]

const form = reactive({
  nickname: '',
  gender: 0,
  birthday: '',
  city: '',
  job: '',
  personalizedSignature: '',
})

const onBirthdayChange = (e: any) => {
  form.birthday = e.detail.value
}

const save = async () => {
  if (!form.nickname.trim()) {
    uni.showToast({ icon: 'none', title: '昵称不能为空' })
    return
  }
  saving.value = true
  try {
    await updateProfileAPI(form)
    uni.showToast({ icon: 'success', title: '保存成功' })
    setTimeout(() => uni.navigateBack(), 1000)
  } catch (e) {
    uni.showToast({ icon: 'none', title: '保存失败，请稍后再试' })
  } finally {
    saving.value = false
  }
}

onLoad(async () => {
  // 重新拉取最新会员信息，避免使用登录时的旧缓存
  let info = memberStore.memberInfo
  try {
    const res = await getMemberInfoAPI()
    info = res.data
    memberStore.setMemberInfo(res.data)
  } catch (e) {
    // 拉取失败则回退 store 缓存
  }
  if (info) {
    form.nickname = info.nickname || info.username || ''
    form.gender = info.gender ?? 0
    form.birthday = info.birthday || ''
    form.city = info.city || ''
    form.job = info.job || ''
    form.personalizedSignature = info.personalizedSignature || ''
  }
})
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

.form-picker {
  flex: 1;
  font-size: 28rpx;
  color: #303133;
}

.gender-group {
  flex: 1;
  display: flex;
  gap: 16rpx;
}

.gender-tag {
  padding: 10rpx 32rpx;
  border-radius: 30rpx;
  font-size: 26rpx;
  color: #606266;
  background: #f5f5f5;

  &.active {
    color: #fa436a;
    background: #fff0f3;
  }
}

.form-textarea-item {
  align-items: flex-start;
}

.form-textarea {
  flex: 1;
  height: 140rpx;
  font-size: 26rpx;
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
