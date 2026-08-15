<template>
  <view class="container">
    <view v-if="orders.length === 0 && !loading" class="empty">
      <text class="empty-text">暂无可申请售后的订单</text>
    </view>

    <view v-for="order in orders" :key="order.id" class="order-card">
      <view class="order-head">
        <text class="order-sn">订单号：{{ order.orderSn }}</text>
        <text class="order-status">{{ statusText(order.status) }}</text>
      </view>
      <view
        class="order-item"
        v-for="item in order.orderItemList"
        :key="item.id"
        @click="selectItem(order, item)"
      >
        <radio
          class="item-radio"
          :checked="selected !== null && selected.item.id === item.id"
          color="#fa436a"
        />
        <image class="item-pic" :src="item.productPic" mode="aspectFill" />
        <view class="item-info">
          <text class="item-name">{{ item.productName }}</text>
          <text v-if="item.productAttr" class="item-attr">{{ item.productAttr }}</text>
          <text class="item-price">￥{{ item.productPrice }} × {{ item.productQuantity }}</text>
        </view>
      </view>
    </view>

    <view v-if="selected" class="form-section">
      <view class="form-item">
        <text class="form-label">联系人</text>
        <input v-model="form.returnName" class="form-input" placeholder="请输入联系人姓名" />
      </view>
      <view class="form-item">
        <text class="form-label">联系电话</text>
        <input v-model="form.returnPhone" class="form-input" type="number" placeholder="请输入联系电话" />
      </view>
      <view class="form-item">
        <text class="form-label">退货原因</text>
        <picker :range="reasons" @change="onReasonChange">
          <view class="form-picker">{{ form.reason || '请选择退货原因' }}</view>
        </picker>
      </view>
      <view class="form-item form-textarea-item">
        <text class="form-label">问题描述</text>
        <textarea
          v-model="form.description"
          class="form-textarea"
          placeholder="请描述遇到的问题（选填）"
        />
      </view>
    </view>

    <view v-if="selected" class="submit-bar">
      <button class="submit-btn" :disabled="submitting" @click="submit">
        {{ submitting ? '提交中...' : '提交申请' }}
      </button>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { getOrderListAPI, createReturnApplyAPI } from '@/apis/order'
import type { OmsOrderDetail, OmsOrderItem, OmsOrderReturnApplyParam } from '@/types/order'
import { useMemberStore } from '@/stores/member'

const memberStore = useMemberStore()
const orders = ref<OmsOrderDetail[]>([])
const loading = ref(false)
const submitting = ref(false)
const reasons = ['质量问题', '商品与描述不符', '发错货', '不想要了', '其他']

interface Selection {
  order: OmsOrderDetail
  item: OmsOrderItem
}

const selected = ref<Selection | null>(null)
const form = ref({
  returnName: '',
  returnPhone: '',
  reason: '',
  description: '',
})

const statusText = (status: number) => {
  const map: Record<number, string> = {
    2: '已发货',
    3: '已完成',
  }
  return map[status] || '未知'
}

const selectItem = (order: OmsOrderDetail, item: OmsOrderItem) => {
  selected.value = { order, item }
}

const onReasonChange = (e: any) => {
  form.value.reason = reasons[e.detail.value]
}

const loadOrders = async () => {
  loading.value = true
  try {
    // 可申请售后：已发货(2)、已完成(3)
    const tasks = [getOrderListAPI({ pageNum: 1, pageSize: 50, status: 2 }), getOrderListAPI({ pageNum: 1, pageSize: 50, status: 3 })]
    const results = await Promise.all(tasks)
    orders.value = [...results[0].data.list, ...results[1].data.list]
  } catch (e) {
    orders.value = []
  } finally {
    loading.value = false
  }
}

const submit = async () => {
  if (!selected.value) return
  if (!form.value.returnName.trim() || !form.value.returnPhone.trim()) {
    uni.showToast({ icon: 'none', title: '请填写联系人和电话' })
    return
  }
  if (!form.value.reason) {
    uni.showToast({ icon: 'none', title: '请选择退货原因' })
    return
  }
  const { order, item } = selected.value
  const param: OmsOrderReturnApplyParam = {
    orderId: order.id,
    orderSn: order.orderSn,
    memberUsername: memberStore.memberInfo?.username || '',
    productId: item.productId,
    productName: item.productName,
    productPic: item.productPic,
    productBrand: item.productBrand,
    productAttr: item.productAttr,
    productPrice: item.productPrice,
    productRealPrice: item.realAmount,
    productCount: item.productQuantity,
    returnName: form.value.returnName,
    returnPhone: form.value.returnPhone,
    reason: form.value.reason,
    description: form.value.description,
  }
  submitting.value = true
  try {
    const res = await createReturnApplyAPI(param)
    uni.showToast({ icon: 'none', title: res.message || '申请已提交' })
    setTimeout(() => uni.navigateBack(), 1200)
  } catch (e) {
    uni.showToast({ icon: 'none', title: '提交失败，请稍后再试' })
  } finally {
    submitting.value = false
  }
}

onLoad(() => {
  const member = memberStore.memberInfo
  form.value.returnName = member?.nickname || member?.username || ''
  form.value.returnPhone = member?.phone || ''
  loadOrders()
})
</script>

<style lang="scss" scoped>
.container {
  min-height: 100vh;
  background: #f5f6f8;
  padding: 24rpx;
  box-sizing: border-box;
  padding-bottom: 140rpx;
}

.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-top: 200rpx;
}

.empty-text {
  margin-top: 24rpx;
  font-size: 28rpx;
  color: #909399;
}

.order-card {
  background: #ffffff;
  border-radius: 16rpx;
  margin-bottom: 24rpx;
  overflow: hidden;
}

.order-head {
  display: flex;
  justify-content: space-between;
  padding: 20rpx 24rpx;
  border-bottom: 1rpx solid #f2f2f2;
  font-size: 24rpx;
  color: #909399;
}

.order-status {
  color: #fa436a;
}

.order-item {
  display: flex;
  align-items: center;
  padding: 20rpx 24rpx;
}

.item-radio {
  transform: scale(0.8);
  flex-shrink: 0;
}

.item-pic {
  width: 120rpx;
  height: 120rpx;
  border-radius: 12rpx;
  background: #f5f5f5;
  margin: 0 20rpx;
  flex-shrink: 0;
}

.item-info {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.item-name {
  font-size: 26rpx;
  color: #303133;
}

.item-attr {
  margin-top: 8rpx;
  font-size: 22rpx;
  color: #909399;
}

.item-price {
  margin-top: 12rpx;
  font-size: 26rpx;
  color: #fa436a;
}

.form-section {
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

.form-textarea-item {
  align-items: flex-start;
}

.form-textarea {
  flex: 1;
  height: 140rpx;
  font-size: 26rpx;
}

.submit-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 20rpx 30rpx calc(20rpx + env(safe-area-inset-bottom));
  background: #ffffff;
  box-shadow: 0 -4rpx 16rpx rgba(31, 41, 55, 0.06);
}

.submit-btn {
  background: linear-gradient(135deg, #ff7a8a, #fa436a);
  color: #ffffff;
  border-radius: 44rpx;
  font-size: 30rpx;

  &[disabled] {
    opacity: 0.5;
  }
}
</style>
