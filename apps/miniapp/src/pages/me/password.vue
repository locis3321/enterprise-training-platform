<script lang="ts" setup>
import { computed, ref } from 'vue'
import { changePassword } from '@/api/auth'
import { useTokenStore } from '@/store/token'

definePage({
  style: {
    navigationBarTitleText: '修改密码',
  },
})

const tokenStore = useTokenStore()

const oldPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const submitting = ref(false)
const errorMessage = ref('')

const canSubmit = computed(() =>
  oldPassword.value.length > 0
  && newPassword.value.length >= 6
  && confirmPassword.value === newPassword.value,
)

/** 强度提示：只做展示，真正的长度校验在服务端。 */
const strength = computed(() => {
  const value = newPassword.value
  if (!value)
    return { label: '', cls: '', level: 0 }
  let score = 0
  if (value.length >= 6)
    score++
  if (value.length >= 10)
    score++
  if (/[a-z]/.test(value) && /[A-Z]/.test(value))
    score++
  if (/\d/.test(value))
    score++
  if (/[^A-Za-z0-9]/.test(value))
    score++
  if (score <= 2)
    return { label: '弱', cls: 'is-weak', level: 1 }
  if (score <= 3)
    return { label: '中', cls: 'is-mid', level: 2 }
  return { label: '强', cls: 'is-strong', level: 3 }
})

async function submit() {
  errorMessage.value = ''
  if (!oldPassword.value) {
    errorMessage.value = '请输入当前密码'
    return
  }
  if (newPassword.value.length < 6 || newPassword.value.length > 64) {
    errorMessage.value = '新密码长度需在 6-64 位之间'
    return
  }
  if (newPassword.value === oldPassword.value) {
    errorMessage.value = '新密码不能与当前密码相同'
    return
  }
  if (confirmPassword.value !== newPassword.value) {
    errorMessage.value = '两次输入的新密码不一致'
    return
  }

  submitting.value = true
  try {
    await changePassword(oldPassword.value, newPassword.value)
    uni.showToast({ title: '密码已修改', icon: 'success' })
    // 改密后旧 token 仍有效，但为安全起见要求重新登录
    setTimeout(() => {
      tokenStore.logout()
      uni.reLaunch({ url: '/pages/login/login' })
    }, 900)
  }
  catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '修改失败，请重试'
  }
  finally {
    submitting.value = false
  }
}
</script>

<template>
  <view class="page">
    <view class="intro">
      <view class="intro-title">
        修改登录密码
      </view>
      <view class="intro-desc">
        密码用于手机号登录。修改成功后需要重新登录，其他设备上的登录状态也会失效。
      </view>
    </view>

    <view class="form">
      <view class="field">
        <text class="field-label">当前密码</text>
        <input
          v-model="oldPassword"
          class="field-input"
          type="text"
          password
          placeholder="请输入当前密码"
          placeholder-class="field-placeholder"
        >
      </view>

      <view class="field">
        <text class="field-label">新密码</text>
        <input
          v-model="newPassword"
          class="field-input"
          type="text"
          password
          placeholder="6-64 位"
          placeholder-class="field-placeholder"
        >
        <view v-if="strength.level" class="strength">
          <view class="strength-bars">
            <view
              v-for="n in 3"
              :key="n"
              class="strength-bar"
              :class="n <= strength.level ? strength.cls : ''"
            />
          </view>
          <text class="strength-text" :class="strength.cls">强度 {{ strength.label }}</text>
        </view>
      </view>

      <view class="field">
        <text class="field-label">确认新密码</text>
        <input
          v-model="confirmPassword"
          class="field-input"
          type="text"
          password
          placeholder="再次输入新密码"
          placeholder-class="field-placeholder"
        >
        <text
          v-if="confirmPassword && confirmPassword !== newPassword"
          class="field-error"
        >
          两次输入不一致
        </text>
      </view>
    </view>

    <view v-if="errorMessage" class="error-banner">
      {{ errorMessage }}
    </view>

    <wd-button
      type="primary"
      block
      :loading="submitting"
      :disabled="!canSubmit"
      @click="submit"
    >
      确认修改
    </wd-button>

    <view class="tips">
      <view class="tips-title">
        密码建议
      </view>
      <view class="tips-item">
        · 至少 6 位，建议 10 位以上
      </view>
      <view class="tips-item">
        · 混合大小写字母、数字与符号更安全
      </view>
      <view class="tips-item">
        · 不要与其他网站使用相同密码
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx 48rpx;
  background-color: #f4f6f9;
}

.intro {
  padding: 28rpx;
  border-radius: 20rpx;
  background: linear-gradient(135deg, #0e5a8a, #12405f);
}

.intro-title {
  font-size: 30rpx;
  font-weight: 600;
  color: #ffffff;
}

.intro-desc {
  margin-top: 10rpx;
  font-size: 23rpx;
  line-height: 1.6;
  color: rgba(255, 255, 255, 0.72);
}

.form {
  margin: 24rpx 0;
  padding: 8rpx 28rpx;
  border: 1rpx solid #e2e8ef;
  border-radius: 20rpx;
  background-color: #ffffff;
}

.field {
  padding: 26rpx 0;
  border-bottom: 1rpx solid #f0f3f7;

  &:last-child {
    border-bottom: none;
  }
}

.field-label {
  display: block;
  font-size: 23rpx;
  color: #8a97a5;
}

.field-input {
  width: 100%;
  margin-top: 12rpx;
  font-size: 30rpx;
  color: #16202b;
}

.field-placeholder {
  color: #b9c4d0;
}

.field-error {
  display: block;
  margin-top: 10rpx;
  font-size: 22rpx;
  color: #c0392b;
}

.strength {
  display: flex;
  align-items: center;
  gap: 14rpx;
  margin-top: 14rpx;
}

.strength-bars {
  display: flex;
  gap: 6rpx;
}

.strength-bar {
  width: 48rpx;
  height: 6rpx;
  border-radius: 999rpx;
  background-color: #eef1f5;

  &.is-weak {
    background-color: #e05c4a;
  }

  &.is-mid {
    background-color: #f0a63a;
  }

  &.is-strong {
    background-color: #34a86b;
  }
}

.strength-text {
  font-size: 21rpx;
  color: #8a97a5;

  &.is-weak {
    color: #c0392b;
  }

  &.is-mid {
    color: #b26a00;
  }

  &.is-strong {
    color: #1f7a45;
  }
}

.error-banner {
  margin-bottom: 20rpx;
  padding: 20rpx 24rpx;
  border-radius: 12rpx;
  background-color: #fdecec;
  color: #c0392b;
  font-size: 24rpx;
  line-height: 1.5;
}

.tips {
  margin-top: 32rpx;
  padding: 24rpx 28rpx;
  border-radius: 16rpx;
  background-color: #eef3f8;
}

.tips-title {
  font-size: 24rpx;
  font-weight: 600;
  color: #0e5a8a;
}

.tips-item {
  margin-top: 10rpx;
  font-size: 22rpx;
  line-height: 1.6;
  color: #5b6b7c;
}
</style>
