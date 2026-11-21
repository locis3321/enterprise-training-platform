<script lang="ts" setup>
import { ref } from 'vue'
import { changePassword, fetchProfile, passwordLogin, phoneBind, wechatLogin } from '@/api/auth'
import type { StudentProfile } from '@/api/auth'
import { getWxCode } from '@/api/login'
import { useTokenStore } from '@/store/token'

definePage({
  style: {
    navigationBarTitleText: '登录',
  },
})

/** 登录页状态机：选择方式 → 微信绑定 / 密码表单 → 已登录。 */
type Mode = 'choose' | 'wechat' | 'password' | 'pendingBind' | 'bound'

const tokenStore = useTokenStore()
const loading = ref(false)
const mode = ref<Mode>('choose')
const bindTicket = ref('')
const profile = ref<StudentProfile | null>(null)
const phone = ref('')
const password = ref('')
const mockPhoneCode = ref('')

/**
 * 本地 Mock 开关：个人主体小程序无手机号快速验证能力，真实微信手机号授权不可用。
 * 开启后「微信登录」在未绑定时提供手机号模拟绑定入口（本地联调用）。
 * 生产构建读不到 development 配置，因此不会泄漏到线上。
 */
const mockWechatEnabled = import.meta.env.VITE_APP_MOCK_WECHAT === 'true'

/**
 * 微信登录（code → openid）。
 *
 * 已绑定直接返回 token；未绑定返回 bindTicket，进入手机号授权步骤。
 * 注意：跨企业同手机号的学员在此会被服务端拒绝（转人工），需改用手机号密码登录。
 */
async function handleWechatLogin() {
  loading.value = true
  try {
    const loginRes = await getWxCode()
    if (!loginRes.code) {
      throw new Error('未获取到微信登录 code')
    }
    const result = await wechatLogin(loginRes.code)
    if (result.bound && result.token) {
      tokenStore.setTokenInfo({ token: result.token, expiresIn: 7 * 24 * 3600 })
      await loadProfile()
      mode.value = 'bound'
      return
    }
    bindTicket.value = result.bindTicket ?? ''
    mode.value = 'pendingBind'
  }
  catch (error) {
    uni.showModal({
      title: '微信登录未完成',
      content: error instanceof Error ? error.message : '微信登录失败，可改用手机号密码登录',
      showCancel: false,
    })
  }
  finally {
    loading.value = false
  }
}

/**
 * 手机号 + 密码登录。
 *
 * 手机号是账号标识（全局唯一，可关联多个企业），凭证是服务端 BCrypt 校验的密码。
 * 该入口不依赖小程序主体资质，个人主体也能用。
 */
async function handlePasswordLogin() {
  const trimmed = phone.value.trim()
  if (!/^1[3-9]\d{9}$/.test(trimmed)) {
    uni.showToast({ title: '请输入 11 位手机号', icon: 'none' })
    return
  }
  if (!password.value) {
    uni.showToast({ title: '请输入密码', icon: 'none' })
    return
  }
  loading.value = true
  try {
    const result = await passwordLogin(trimmed, password.value)
    tokenStore.setTokenInfo({ token: result.token, expiresIn: 7 * 24 * 3600 })
    // 强制改密需要原密码，登录后先留着，改密成功再清
    oldPassword.value = password.value
    password.value = ''
    await loadProfile()
    mode.value = 'bound'
    if (profile.value?.mustChangePassword) {
      // 服务端会拦截除改密与读取档案外的所有接口，此处直接强制弹层，避免用户点进首页才失败
      forceChangePassword.value = true
      showChangePassword.value = true
      return
    }
    oldPassword.value = ''
    if (result.enterpriseCount > 1) {
      uni.showToast({ title: `已登录，关联 ${result.enterpriseCount} 家企业`, icon: 'none' })
    }
  }
  catch (error) {
    uni.showModal({
      title: '登录失败',
      content: error instanceof Error ? error.message : '手机号或密码不正确',
      showCancel: false,
    })
  }
  finally {
    loading.value = false
  }
}

function enterWechat() {
  mode.value = 'wechat'
  handleWechatLogin()
}

/** 手机号授权回调（open-type="getPhoneNumber"）。 */
function onGetPhoneNumber(event: any) {
  const detail = event?.detail ?? {}
  const code = detail.code
  if (!code) {
    const reason = String(detail.errMsg ?? '')
    const message = reason.includes('deny')
      ? '你拒绝了手机号授权，可重试或改用手机号密码登录'
      : '当前小程序未开通手机号授权能力（需企业主体已认证）。请改用手机号密码登录'
    uni.showModal({ title: '无法授权手机号', content: message, showCancel: false })
    return
  }
  handlePhoneBind(code)
}

async function handlePhoneBind(phoneCode: string) {
  if (!bindTicket.value) {
    uni.showToast({ title: '请先完成微信登录', icon: 'none' })
    return
  }
  loading.value = true
  try {
    const result = await phoneBind(bindTicket.value, phoneCode)
    tokenStore.setTokenInfo({ token: result.token, expiresIn: 7 * 24 * 3600 })
    await loadProfile()
    mode.value = 'bound'
    uni.showToast({ title: `已绑定 ${result.enterpriseName}`, icon: 'success' })
  }
  catch (error) {
    uni.showModal({
      title: '绑定失败',
      content: error instanceof Error ? error.message : '手机号绑定失败',
      showCancel: false,
    })
  }
  finally {
    loading.value = false
  }
}

/** 本地 Mock：手机号派生 code 走真实 wechat-login 换 ticket，再以 phone-<手机号> 绑定。 */
async function onMockBind() {
  const value = mockPhoneCode.value.trim()
  if (!/^1[3-9]\d{9}$/.test(value)) {
    uni.showToast({ title: '请输入 11 位手机号', icon: 'none' })
    return
  }
  if (!bindTicket.value) {
    loading.value = true
    try {
      const result = await wechatLogin(await buildMockCode(value))
      if (result.bound && result.token) {
        tokenStore.setTokenInfo({ token: result.token, expiresIn: 7 * 24 * 3600 })
        await loadProfile()
        mode.value = 'bound'
        return
      }
      bindTicket.value = result.bindTicket ?? ''
    }
    catch (error) {
      uni.showModal({
        title: '模拟登录失败',
        content: error instanceof Error ? error.message : '请确认服务端已启用本地 Mock',
        showCancel: false,
      })
      return
    }
    finally {
      loading.value = false
    }
  }
  await handlePhoneBind(`phone-${value}`)
}

/** 生成服务端 MockWechatClient 可接受的 32 位 code（由手机号派生，保证同一手机号稳定）。 */
async function buildMockCode(value: string) {
  let code = `mock${value}`
  while (code.length < 32) {
    code += '0'
  }
  return code.slice(0, 32)
}

async function loadProfile() {
  try {
    profile.value = await fetchProfile()
  }
  catch {
    profile.value = null
  }
}

/* ---------- 修改密码 ---------- */

const showChangePassword = ref(false)
/** 强制改密（首次登录/被重置）：弹层不可关闭，只能改密或退出登录。 */
const forceChangePassword = ref(false)
const oldPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')

function openChangePassword() {
  forceChangePassword.value = false
  oldPassword.value = ''
  newPassword.value = ''
  confirmPassword.value = ''
  showChangePassword.value = true
}

/** 强制改密时不允许关闭弹层，否则用户会卡在被服务端拦截的状态里。 */
function closeChangePassword() {
  if (forceChangePassword.value)
    return
  showChangePassword.value = false
}

/** 强制改密状态下退出登录，回到登录方式选择。 */
function logoutAndBack() {
  tokenStore.logout()
  showChangePassword.value = false
  forceChangePassword.value = false
  oldPassword.value = ''
  newPassword.value = ''
  confirmPassword.value = ''
  profile.value = null
  backToChoose()
}

async function submitChangePassword() {
  if (newPassword.value.length < 6) {
    uni.showToast({ title: '新密码至少 6 位', icon: 'none' })
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    uni.showToast({ title: '两次输入的新密码不一致', icon: 'none' })
    return
  }
  loading.value = true
  try {
    await changePassword(oldPassword.value, newPassword.value)
    showChangePassword.value = false
    oldPassword.value = ''
    newPassword.value = ''
    confirmPassword.value = ''
    // 强制改密完成后服务端解除拦截，直接进入首页
    if (forceChangePassword.value) {
      forceChangePassword.value = false
      uni.showToast({ title: '密码已修改', icon: 'success' })
      setTimeout(() => goHome(), 800)
      return
    }
    uni.showToast({ title: '密码已修改', icon: 'success' })
  }
  catch (error) {
    uni.showModal({
      title: '修改失败',
      content: error instanceof Error ? error.message : '请检查原密码',
      showCancel: false,
    })
  }
  finally {
    loading.value = false
  }
}

function goHome() {
  uni.switchTab({ url: '/pages/index/index' })
}

function backToChoose() {
  mode.value = 'choose'
  bindTicket.value = ''
}
</script>

<template>
  <view class="page">
    <view class="card">
      <view class="title">
        登录 / 绑定手机号
      </view>

      <!-- 已登录 -->
      <template v-if="mode === 'bound' && profile">
        <view class="profile-box">
          <view class="profile-name">
            {{ profile.name }}
          </view>
          <view class="profile-line">
            {{ profile.enterpriseName }}
            <text v-if="profile.enterpriseCount > 1">等 {{ profile.enterpriseCount }} 家企业</text>
          </view>
          <view class="profile-line">
            {{ profile.phoneMasked }}
          </view>
        </view>
        <wd-button type="primary" block @click="goHome">
          进入首页
        </wd-button>
        <wd-button plain block custom-class="secondary-button" @click="openChangePassword">
          修改密码
        </wd-button>
      </template>

      <!-- 选择登录方式：不做主体类型判定，由用户自选 -->
      <template v-else-if="mode === 'choose'">
        <view class="desc">
          请选择登录方式。手机号密码登录不依赖微信授权能力，任何主体的小程序都可使用。
        </view>

        <wd-button type="primary" block :loading="loading" @click="enterWechat">
          微信授权登录
        </wd-button>

        <wd-button plain block custom-class="secondary-button" @click="mode = 'password'">
          手机号密码登录
        </wd-button>

        <view class="hint">
          初始密码为 anzhi + 手机号后 6 位（由企业导入时生成），登录后请及时修改。
        </view>
      </template>

      <!-- 手机号密码登录 -->
      <template v-else-if="mode === 'password'">
        <view class="field">
          <text class="field-label">手机号</text>
          <input v-model="phone" class="field-input" type="number" :maxlength="11" placeholder="请输入 11 位手机号">
        </view>
        <view class="field">
          <text class="field-label">密码</text>
          <input v-model="password" class="field-input" password placeholder="请输入密码" @confirm="handlePasswordLogin">
        </view>

        <wd-button type="primary" block :loading="loading" @click="handlePasswordLogin">
          登录
        </wd-button>
        <wd-button plain block custom-class="secondary-button" @click="backToChoose">
          返回选择登录方式
        </wd-button>
      </template>

      <!-- 微信登录后的手机号绑定 -->
      <template v-else-if="mode === 'pendingBind'">
        <view class="desc">
          微信已登录，还需授权手机号以匹配员工档案。
        </view>
        <wd-button
          type="primary"
          block
          open-type="getPhoneNumber"
          :loading="loading"
          @getphonenumber="onGetPhoneNumber"
        >
          授权手机号并绑定
        </wd-button>

        <view v-if="mockWechatEnabled" class="mock-box">
          <view class="mock-title">
            本地调试（个人主体，无手机号授权能力）
          </view>
          <view class="mock-desc">
            输入已导入的员工手机号，后端将以本地 Mock 完成绑定。正式环境请使用手机号密码登录。
          </view>
          <view class="mock-row">
            <input v-model="mockPhoneCode" class="field-input" type="number" :maxlength="11" placeholder="员工手机号">
            <wd-button size="small" type="success" :loading="loading" @click="onMockBind">
              模拟绑定
            </wd-button>
          </view>
        </view>

        <wd-button plain block custom-class="secondary-button" @click="backToChoose">
          改用手机号密码登录
        </wd-button>
      </template>

      <view class="hint">
        同一手机号命中多个企业员工时，微信绑定会转人工处理；手机号密码登录可直接进入。
      </view>
    </view>

    <!-- 修改密码弹层：强制改密时不可关闭 -->
    <view v-if="showChangePassword" class="mask" @click.self="closeChangePassword">
      <view class="dialog">
        <view class="dialog-title">
          {{ forceChangePassword ? '请修改初始密码' : '修改密码' }}
        </view>
        <view v-if="forceChangePassword" class="dialog-hint">
          首次登录（或密码被重置）需先修改密码才能继续使用。初始密码为 anzhi + 手机号后 6 位。
        </view>
        <input v-model="oldPassword" class="field-input" password placeholder="原密码">
        <input v-model="newPassword" class="field-input" password placeholder="新密码（至少 6 位）">
        <input v-model="confirmPassword" class="field-input" password placeholder="确认新密码">
        <view class="dialog-actions">
          <wd-button v-if="forceChangePassword" size="small" plain @click="logoutAndBack">
            退出登录
          </wd-button>
          <wd-button v-else size="small" plain @click="closeChangePassword">
            取消
          </wd-button>
          <wd-button size="small" type="primary" :loading="loading" @click="submitChangePassword">
            确认修改
          </wd-button>
        </view>
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 48rpx 24rpx;
  background-color: #f4f6f9;
}

.card {
  padding: 40rpx 32rpx;
  border: 1rpx solid #e2e8ef;
  border-radius: 16rpx;
  background-color: #ffffff;
}

.title {
  font-size: 34rpx;
  font-weight: 600;
  color: #16202b;
}

.profile-box {
  margin: 32rpx 0;
  padding: 24rpx;
  border-radius: 12rpx;
  background-color: #f5f9fc;
}

.profile-name {
  font-size: 32rpx;
  font-weight: 600;
  color: #16202b;
}

.profile-line {
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #5b6b7c;
}

.mock-box {
  margin-top: 24rpx;
  padding: 20rpx;
  border: 1rpx dashed #cbd8e4;
  border-radius: 12rpx;
  background-color: #fbfdff;
}

.mock-title {
  font-size: 24rpx;
  font-weight: 600;
  color: #0e5a8a;
}

.mock-desc {
  margin-top: 6rpx;
  font-size: 22rpx;
  line-height: 1.6;
  color: #7b8a99;
}

.mock-row {
  display: flex;
  gap: 12rpx;
  align-items: center;
  margin-top: 12rpx;
}

.desc {
  margin: 24rpx 0;
  font-size: 24rpx;
  line-height: 1.7;
  color: #5b6b7c;
}

.field {
  margin-bottom: 20rpx;
}

.field-label {
  display: block;
  margin-bottom: 8rpx;
  font-size: 24rpx;
  color: #5b6b7c;
}

.field-input {
  width: 100%;
  height: 76rpx;
  padding: 0 20rpx;
  border: 1rpx solid #dfe7ef;
  border-radius: 10rpx;
  font-size: 26rpx;
  box-sizing: border-box;
}

:deep(.secondary-button) {
  margin-top: 20rpx;
}

.mask {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: rgba(0, 0, 0, 0.45);
}

.dialog {
  width: 88%;
  padding: 32rpx;
  border-radius: 16rpx;
  background-color: #ffffff;
}

.dialog-title {
  margin-bottom: 20rpx;
  font-size: 30rpx;
  font-weight: 600;
  color: #16202b;
}

.dialog .field-input {
  margin-bottom: 16rpx;
}

.dialog-hint {
  margin-bottom: 20rpx;
  padding: 16rpx;
  border-radius: 10rpx;
  background-color: #fff7e6;
  color: #b26a00;
  font-size: 22rpx;
  line-height: 1.6;
}

.dialog-actions {
  display: flex;
  gap: 16rpx;
  justify-content: flex-end;
  margin-top: 8rpx;
}

.hint {
  margin-top: 24rpx;
  font-size: 22rpx;
  line-height: 1.6;
  color: #8a97a5;
}
</style>
