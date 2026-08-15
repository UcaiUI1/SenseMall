<template>
  <view class="chat-page">
    <!-- 自定义导航栏 -->
    <view class="nav-bar" :style="{ paddingTop: statusBarHeight + 'px' }">
      <view class="nav-inner">
        <view class="nav-back" @click="goBack">
          <text class="nav-back-icon">‹</text>
        </view>
        <view class="nav-title">
          <text class="nav-title-text">好物推荐官</text>
          <text class="nav-sub">智能帮你精准找商品</text>
        </view>
        <view class="nav-placeholder"></view>
      </view>
    </view>

    <!-- 消息列表 -->
    <scroll-view
      scroll-y
      class="message-list"
      :scroll-into-view="scrollIntoView"
      :scroll-with-animation="true"
    >
      <!-- 欢迎页 -->
      <view v-if="messages.length === 0" class="welcome">
        <view class="welcome-avatar">
          <text class="welcome-char">感</text>
        </view>
        <text class="welcome-title">你好，我是小感</text>
        <text class="welcome-desc">SenseMall 的好物推荐官，直接说你的购物需求，我来帮你精准找到心仪好物</text>
        <view class="welcome-cards">
          <view
            class="welcome-card"
            v-for="(suggest, index) in suggestions"
            :key="index"
            @click="quickSend(suggest)"
          >
            <text class="card-text">{{ suggest }}</text>
            <text class="card-arrow">›</text>
          </view>
        </view>
      </view>

      <!-- 对话消息 -->
      <view v-for="(msg, index) in messages" :key="index" :id="'msg-' + index">
        <view class="msg-row" :class="msg.role === 'user' ? 'row-user' : 'row-ai'">
          <view v-if="msg.role === 'ai'" class="avatar">
            <text class="avatar-char">感</text>
          </view>
          <view class="bubble" :class="msg.role === 'user' ? 'bubble-user' : 'bubble-ai'">
            <text v-if="msg.content" class="bubble-text">{{ msg.content }}</text>
            <view v-else class="typing">
              <view class="dot"></view>
              <view class="dot"></view>
              <view class="dot"></view>
            </view>
          </view>
        </view>
        <scroll-view
          v-if="msg.products && msg.products.length > 0"
          scroll-x
          class="product-scroll"
          :show-scrollbar="false"
        >
          <view class="product-card" v-for="product in msg.products" :key="product.id" @click="goDetail(product.id)">
            <image class="product-pic" :src="product.pic" mode="aspectFill" />
            <view class="product-info">
              <text v-if="product.brandName" class="product-brand">{{ product.brandName }}</text>
              <text class="product-name">{{ product.name }}</text>
              <view class="product-bottom">
                <text class="product-price">￥{{ product.price }}</text>
                <text class="product-link">去看看</text>
              </view>
            </view>
          </view>
        </scroll-view>
      </view>
      <view :id="'msg-' + messages.length" class="scroll-anchor"></view>
    </scroll-view>

    <!-- 底部输入栏 -->
    <view class="input-area">
      <view class="input-wrap">
        <input
          v-model="input"
          class="chat-input"
          placeholder="说出你的购物需求，如：500以内的运动鞋"
          placeholder-class="input-placeholder"
          confirm-type="send"
          :disabled="loading"
          @confirm="handleSend"
        />
        <view
          class="send-btn"
          :class="{ 'send-btn-disabled': !input.trim() || loading }"
          @click="handleSend"
        >
            <text class="send-icon">发送</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { streamAiChatAPI, type AiProduct } from '@/apis/ai'

interface ChatMessage {
  role: 'user' | 'ai'
  content: string
  products?: AiProduct[]
}

const messages = ref<ChatMessage[]>([])
const input = ref('')
const loading = ref(false)
const scrollIntoView = ref('')
const sessionId = ref('')
const statusBarHeight = ref(0)

const suggestions = ['500以内的运动鞋', '3000以内的小米手机', '4000元以内的电视', '500GB 固态硬盘']

const goBack = () => {
  uni.navigateBack({
    fail: () => {
      uni.switchTab({ url: '/pages/index/index' })
    },
  })
}

const goDetail = (id: number) => {
  uni.navigateTo({ url: `/pages/product/product?id=${id}` })
}

const scrollToBottom = () => {
  setTimeout(() => {
    scrollIntoView.value = 'msg-' + messages.value.length
  }, 100)
}

const handleSend = async () => {
  const text = input.value.trim()
  if (!text || loading.value) {
    return
  }
  input.value = ''
  messages.value.push({ role: 'user', content: text })
  const aiMessage = reactive<ChatMessage>({ role: 'ai', content: '' })
  messages.value.push(aiMessage)
  loading.value = true
  scrollToBottom()
  try {
    const result = await streamAiChatAPI(sessionId.value, text, (chunk) => {
      aiMessage.content += chunk
      scrollToBottom()
    })
    sessionId.value = result.sessionId
    uni.setStorageSync('ai_session_id', result.sessionId)
    aiMessage.content = result.reply
    aiMessage.products = result.products
    if (!aiMessage.content) {
      aiMessage.content = '抱歉，刚刚出了点问题，请稍后再试。'
    }
  } catch (e) {
    aiMessage.content = aiMessage.content || '抱歉，刚刚出了点问题，请稍后再试。'
  } finally {
    loading.value = false
    scrollToBottom()
  }
}

const quickSend = (text: string) => {
  input.value = text
  handleSend()
}

onLoad(() => {
  const systemInfo = uni.getSystemInfoSync()
  statusBarHeight.value = systemInfo.statusBarHeight || 0
  const cached = uni.getStorageSync('ai_session_id')
  if (cached) {
    sessionId.value = cached
  } else {
    sessionId.value = `ai_${Date.now()}_${Math.floor(Math.random() * 100000)}`
    uni.setStorageSync('ai_session_id', sessionId.value)
  }
})
</script>

<style lang="scss" scoped>
.chat-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: linear-gradient(180deg, #fff0f3 0%, #f6f7fb 32%);
}

/* ===== 导航栏 ===== */
.nav-bar {
  background: linear-gradient(135deg, #ff7a8a 0%, #fa436a 100%);
  padding-bottom: 24rpx;
  box-shadow: 0 6rpx 20rpx rgba(250, 67, 106, 0.25);
}

.nav-inner {
  display: flex;
  align-items: center;
  padding: 0 24rpx;
}

.nav-back {
  width: 88rpx;
  height: 88rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-left: -16rpx;
}

.nav-back-icon {
  font-size: 56rpx;
  color: #ffffff;
  line-height: 1;
}

.nav-title {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.nav-title-text {
  font-size: 34rpx;
  font-weight: 600;
  color: #ffffff;
  line-height: 1.3;
}

.nav-sub {
  font-size: 22rpx;
  color: rgba(255, 255, 255, 0.85);
  line-height: 1.4;
}

.nav-placeholder {
  width: 72rpx;
}

/* ===== 消息列表 ===== */
.message-list {
  flex: 1;
  padding: 28rpx 24rpx 20rpx;
  box-sizing: border-box;
}

/* 欢迎页 */
.welcome {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 80rpx 20rpx 30rpx;
}

.welcome-avatar {
  width: 132rpx;
  height: 132rpx;
  border-radius: 50%;
  background: linear-gradient(135deg, #ffb3c1, #fa436a);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 12rpx 30rpx rgba(250, 67, 106, 0.3);
}

.welcome-char {
  font-size: 56rpx;
  font-weight: 700;
  color: #ffffff;
}

.welcome-title {
  margin-top: 28rpx;
  font-size: 38rpx;
  font-weight: 600;
  color: #303133;
}

.welcome-desc {
  margin-top: 14rpx;
  font-size: 26rpx;
  color: #909399;
  text-align: center;
  width: 100%;
  box-sizing: border-box;
  padding: 0 30rpx;
  word-break: break-all;
  line-height: 1.6;
}

.welcome-cards {
  margin-top: 44rpx;
  width: 100%;
}

.welcome-card {
  display: flex;
  align-items: center;
  background: #ffffff;
  border-radius: 20rpx;
  padding: 26rpx 28rpx;
  margin-bottom: 20rpx;
  box-shadow: 0 8rpx 24rpx rgba(31, 41, 55, 0.06);
}

.card-text {
  flex: 1;
  font-size: 28rpx;
  color: #303133;
}

.card-arrow {
  font-size: 36rpx;
  color: #c0c4cc;
}

/* 消息气泡 */
.msg-row {
  display: flex;
  align-items: flex-start;
  margin-bottom: 30rpx;

  &.row-user {
    justify-content: flex-end;
  }

  &.row-ai {
    justify-content: flex-start;
  }
}

.avatar {
  width: 72rpx;
  height: 72rpx;
  border-radius: 50%;
  background: linear-gradient(135deg, #ffb3c1, #fa436a);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 18rpx;
  flex-shrink: 0;
  box-shadow: 0 6rpx 16rpx rgba(250, 67, 106, 0.25);
}

.avatar-char {
  font-size: 30rpx;
  font-weight: 700;
  color: #ffffff;
}

.bubble {
  max-width: 74%;
  padding: 20rpx 26rpx;
  border-radius: 22rpx;
  font-size: 28rpx;
  line-height: 1.6;
  word-break: break-all;

  &.bubble-user {
    background: linear-gradient(135deg, #ff7a8a, #fa436a);
    color: #ffffff;
    border-top-right-radius: 6rpx;
    box-shadow: 0 6rpx 16rpx rgba(250, 67, 106, 0.25);
  }

  &.bubble-ai {
    background: #ffffff;
    color: #303133;
    border-top-left-radius: 6rpx;
    box-shadow: 0 6rpx 16rpx rgba(31, 41, 55, 0.06);
  }
}

/* 打字动画 */
.typing {
  display: flex;
  align-items: center;
  height: 40rpx;
}

.dot {
  width: 12rpx;
  height: 12rpx;
  margin-right: 10rpx;
  border-radius: 50%;
  background: #fa436a;
  animation: typing-bounce 1.2s infinite ease-in-out;

  &:nth-child(2) {
    animation-delay: 0.15s;
  }

  &:nth-child(3) {
    animation-delay: 0.3s;
    margin-right: 0;
  }
}

@keyframes typing-bounce {
  0%,
  60%,
  100% {
    transform: translateY(0);
    opacity: 0.4;
  }

  30% {
    transform: translateY(-10rpx);
    opacity: 1;
  }
}

/* 商品卡片 */
.product-scroll {
  margin-left: 90rpx;
  margin-top: -8rpx;
  margin-bottom: 12rpx;
  white-space: nowrap;
}

.product-card {
  display: inline-block;
  width: 260rpx;
  margin-right: 22rpx;
  background: #ffffff;
  border-radius: 18rpx;
  overflow: hidden;
  vertical-align: top;
  box-shadow: 0 8rpx 24rpx rgba(31, 41, 55, 0.08);
}

.product-pic {
  width: 260rpx;
  height: 200rpx;
  background-color: #f5f5f5;
}

.product-info {
  padding: 16rpx 18rpx 18rpx;
}

.product-brand {
  display: inline-block;
  font-size: 20rpx;
  color: #fa436a;
  background: #fff0f3;
  border-radius: 8rpx;
  padding: 2rpx 12rpx;
  margin-bottom: 10rpx;
}

.product-name {
  display: block;
  font-size: 24rpx;
  color: #303133;
  line-height: 1.4;
  height: 68rpx;
  overflow: hidden;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.product-bottom {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 12rpx;
}

.product-price {
  font-size: 30rpx;
  font-weight: 700;
  color: #fa436a;
}

.product-link {
  font-size: 22rpx;
  color: #909399;
}

.scroll-anchor {
  height: 20rpx;
}

/* ===== 输入栏 ===== */
.input-area {
  padding: 16rpx 24rpx 20rpx;
  padding-bottom: calc(20rpx + constant(safe-area-inset-bottom));
  padding-bottom: calc(20rpx + env(safe-area-inset-bottom));
}

.input-wrap {
  display: flex;
  align-items: center;
  background: #ffffff;
  border-radius: 44rpx;
  padding: 10rpx 12rpx 10rpx 30rpx;
  box-shadow: 0 8rpx 28rpx rgba(31, 41, 55, 0.08);
}

.chat-input {
  flex: 1;
  height: 68rpx;
  font-size: 28rpx;
}

.input-placeholder {
  color: #c0c4cc;
}

.send-btn {
  width: 76rpx;
  height: 76rpx;
  border-radius: 50%;
  background: linear-gradient(135deg, #ff7a8a, #fa436a);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  box-shadow: 0 6rpx 16rpx rgba(250, 67, 106, 0.3);
  transition: opacity 0.2s;

  &.send-btn-disabled {
    opacity: 0.4;
  }
}

.send-icon {
  font-size: 24rpx;
  color: #ffffff;
  font-weight: 600;
}
</style>
