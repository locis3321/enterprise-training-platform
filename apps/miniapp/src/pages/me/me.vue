<script lang="ts" setup>
import { onShow } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { fetchProfile } from '@/api/auth'
import type { StudentProfile } from '@/api/auth'
import { fetchLearningArchive } from '@/api/archive'
import type { ArchiveSummary } from '@/api/archive'
import { useTokenStore } from '@/store/token'

definePage({
  style: {
    navigationBarTitleText: '我的',
  },
})

const tokenStore = useTokenStore()
const profile = ref<StudentProfile | null>(null)
const summary = ref<ArchiveSummary | null>(null)
const loggingOut = ref(false)

const hasLogin = computed(() => tokenStore.hasLogin)

/** 已登录才请求；401 由 http 层统一跳登录，这里不重复提示。 */
async function loadProfile() {
  if (!tokenStore.hasLogin) {
    profile.value = null
    summary.value = null
    return
  }
  try {
    profile.value = await fetchProfile()
  }
  catch {
    profile.value = null
  }
  // 档案摘要失败不影响页面其它入口，静默降级为不显示数字
  try {
    const archive = await fetchLearningArchive()
    summary.value = archive.summary
  }
  catch {
    summary.value = null
  }
}

onShow(loadProfile)

function goLogin() {
  uni.navigateTo({ url: '/pages/login/login' })
}

function goArchive(tab: 'learning' | 'exam' = 'learning') {
  uni.navigateTo({ url: `/pages/me/archive?tab=${tab}` })
}

function goPassword() {
  uni.navigateTo({ url: '/pages/me/password' })
}

function goPrivacy() {
  uni.navigateTo({ url: '/pages/me/privacy' })
}

function goAbout() {
  uni.navigateTo({ url: '/pages/about/about' })
}

async function logout() {
  const confirm = await new Promise<boolean>((resolve) => {
    uni.showModal({
      title: '退出登录',
      content: '退出后需要重新登录才能查看培训任务与成绩。确定退出吗？',
      confirmText: '退出',
      confirmColor: '#c0392b',
      success: res => resolve(!!res.confirm),
      fail: () => resolve(false),
    })
  })
  if (!confirm)
    return
  loggingOut.value = true
  try {
    await tokenStore.logout()
  }
  finally {
    loggingOut.value = false
    profile.value = null
    summary.value = null
    uni.reLaunch({ url: '/pages/login/login' })
  }
}
</script>

<template>
  <view class="page">
    <!-- 身份卡 -->
    <view class="profile">
      <view class="profile-glow" />
      <view class="profile-main">
        <view class="avatar"><text class="avatar-glyph">人</text></view>
        <view class="profile-text">
          <template v-if="profile">
            <view class="profile-name">
              {{ profile.name }}
            </view>
            <view class="profile-phone">
              {{ profile.phoneMasked }}
            </view>
          </template>
          <template v-else>
            <view class="profile-name">
              未登录
            </view>
            <view class="profile-phone">
              登录后可查看培训任务与考试成绩
            </view>
          </template>
        </view>
        <wd-button v-if="!hasLogin" size="small" type="primary" plain @click="goLogin">
          去登录
        </wd-button>
      </view>

      <!-- 一个手机号可关联多家企业，逐一列出避免只显示第一家造成误解 -->
      <view v-if="profile && profile.enterprises.length" class="org-list">
        <view
          v-for="ent in profile.enterprises"
          :key="ent.enterpriseId"
          class="org-chip"
        >
          {{ ent.enterpriseName }}
        </view>
      </view>
    </view>

    <!-- 档案速览：合并原「培训档案」「考试记录」两个入口 -->
    <view v-if="hasLogin" class="archive-card" @click="goArchive('learning')">
      <view class="archive-head">
        <view class="archive-title">
          培训档案
        </view>
        <view class="archive-more">
          <text>查看全部</text><text class="chev">›</text>
        </view>
      </view>

      <view class="archive-stats">
        <view class="archive-stat">
          <text class="a-value">{{ summary?.studyHours ?? '—' }}</text>
          <text class="a-label">有效学时</text>
        </view>
        <view class="archive-stat">
          <text class="a-value">
            {{ summary ? `${summary.taskCompleted}/${summary.taskTotal}` : '—' }}
          </text>
          <text class="a-label">任务完成</text>
        </view>
        <view class="archive-stat">
          <text class="a-value">{{ summary?.examPassed ?? '—' }}</text>
          <text class="a-label">考试通过</text>
        </view>
      </view>

      <view class="archive-links">
        <view class="archive-link" @click.stop="goArchive('learning')">
          学习档案
        </view>
        <view class="archive-link" @click.stop="goArchive('exam')">
          考试记录
        </view>
      </view>
    </view>

    <!-- 账号与安全 -->
    <view class="group">
      <view class="group-title">
        账号与安全
      </view>
      <view class="list">
        <view class="list-item" @click="hasLogin ? goPassword() : goLogin()">
          <view class="item-icon is-key">
            <text class="item-glyph">密</text>
          </view>
          <view class="list-text">
            <view class="list-label">
              修改密码
            </view>
            <view class="list-desc">
              用于手机号登录，修改后需重新登录
            </view>
          </view>
          <text class="chev">›</text>
        </view>
      </view>
    </view>

    <!-- 关于与协议 -->
    <view class="group">
      <view class="group-title">
        关于
      </view>
      <view class="list">
        <view class="list-item" @click="goPrivacy">
          <view class="item-icon is-doc">
            <text class="item-glyph">协</text>
          </view>
          <view class="list-text">
            <view class="list-label">
              隐私协议
            </view>
            <view class="list-desc">
              个人信息收集与使用说明
            </view>
          </view>
          <text class="chev">›</text>
        </view>

        <view class="list-item" @click="goAbout">
          <view class="item-icon is-info">
            <text class="item-glyph">讯</text>
          </view>
          <view class="list-text">
            <view class="list-label">
              关于平台
            </view>
            <view class="list-desc">
              版本信息与使用说明
            </view>
          </view>
          <text class="chev">›</text>
        </view>
      </view>
    </view>

    <view v-if="hasLogin" class="logout">
      <wd-button block plain :loading="loggingOut" @click="logout">
        退出登录
      </wd-button>
    </view>

    <view class="note">
      手机号仅由服务端调用微信接口获取，客户端提交的手机号不作为身份凭证。
    </view>
  </view>
</template>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx 48rpx;
  background-color: #f4f6f9;
}

/* ---------- 身份卡 ---------- */
.profile {
  position: relative;
  padding: 32rpx;
  border-radius: 24rpx;
  background: linear-gradient(145deg, #0e5a8a 0%, #12405f 62%, #0b2c42 100%);
  overflow: hidden;
}

.profile-glow {
  position: absolute;
  top: -110rpx;
  right: -70rpx;
  width: 300rpx;
  height: 300rpx;
  border-radius: 50%;
  background: rgba(94, 197, 255, 0.16);
}

.profile-main {
  position: relative;
  display: flex;
  align-items: center;
  gap: 24rpx;
}

.avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 96rpx;
  height: 96rpx;
  border-radius: 28rpx;
  background: rgba(255, 255, 255, 0.16);
}

:deep(.avatar-icon) {
  color: #ffffff;
}

.profile-text {
  flex: 1;
  min-width: 0;
}

.profile-name {
  font-size: 32rpx;
  font-weight: 600;
  color: #ffffff;
}

.profile-phone {
  margin-top: 8rpx;
  font-size: 23rpx;
  line-height: 1.5;
  color: rgba(255, 255, 255, 0.7);
}

.org-list {
  position: relative;
  display: flex;
  flex-wrap: wrap;
  gap: 10rpx;
  margin-top: 24rpx;
}

.org-chip {
  padding: 6rpx 16rpx;
  border-radius: 999rpx;
  background: rgba(255, 255, 255, 0.14);
  color: rgba(255, 255, 255, 0.86);
  font-size: 21rpx;
}

/* ---------- 档案速览 ---------- */
.archive-card {
  margin-top: 24rpx;
  padding: 28rpx;
  border: 1rpx solid #e2e8ef;
  border-radius: 20rpx;
  background-color: #ffffff;
}

.archive-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.archive-title {
  font-size: 29rpx;
  font-weight: 600;
  color: #16202b;
}

.archive-more {
  display: flex;
  align-items: center;
  gap: 4rpx;
  font-size: 22rpx;
  color: #8a97a5;
}

:deep(.more-arrow) {
  color: #8a97a5;
}

.archive-stats {
  display: flex;
  margin-top: 24rpx;
}

.archive-stat {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}

.a-value {
  font-size: 36rpx;
  font-weight: 700;
  line-height: 1;
  color: #0e5a8a;
}

.a-label {
  font-size: 21rpx;
  color: #8a97a5;
}

.archive-links {
  display: flex;
  gap: 16rpx;
  margin-top: 26rpx;
  padding-top: 22rpx;
  border-top: 1rpx solid #f0f3f7;
}

.archive-link {
  flex: 1;
  padding: 18rpx 0;
  border-radius: 12rpx;
  background-color: #eef3f8;
  color: #0e5a8a;
  font-size: 25rpx;
  font-weight: 500;
  text-align: center;
}

/* ---------- 分组列表 ---------- */
.group {
  margin-top: 32rpx;
}

.group-title {
  padding: 0 8rpx 14rpx;
  font-size: 22rpx;
  font-weight: 600;
  color: #8a97a5;
  letter-spacing: 1rpx;
}

.list {
  border: 1rpx solid #e2e8ef;
  border-radius: 20rpx;
  background-color: #ffffff;
  overflow: hidden;
}

.list-item {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 26rpx 28rpx;
  border-bottom: 1rpx solid #f0f3f7;

  &:last-child {
    border-bottom: none;
  }
}

.item-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 64rpx;
  height: 64rpx;
  border-radius: 18rpx;

  &.is-key {
    background-color: #e8f1f7;
  }

  &.is-doc {
    background-color: #eaf6ee;
  }

  &.is-info {
    background-color: #f1eefb;
  }
}

:deep(.item-icon-inner) {
  color: #0e5a8a;
}

.list-text {
  flex: 1;
  min-width: 0;
}

.list-label {
  font-size: 28rpx;
  color: #16202b;
}

.list-desc {
  margin-top: 6rpx;
  font-size: 22rpx;
  line-height: 1.5;
  color: #8a97a5;
}

:deep(.item-arrow) {
  flex-shrink: 0;
  color: #c3ccd6;
}

.chev {
  flex-shrink: 0;
  color: #c3ccd6;
  font-size: 32rpx;
  line-height: 1;
}

.item-glyph,
.avatar-glyph,
.hero-glyph {
  color: #0e5a8a;
  font-size: 26rpx;
  font-weight: 600;
}

.avatar-glyph {
  color: #ffffff;
  font-size: 32rpx;
}

.logout {
  margin-top: 40rpx;
}

.note {
  margin-top: 28rpx;
  font-size: 21rpx;
  line-height: 1.7;
  color: #a4b0bd;
  text-align: center;
}
</style>
