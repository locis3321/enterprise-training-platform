<script lang="ts" setup>
import { onShow } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { fetchPrivacyPolicy } from '@/api/archive'
import type { PrivacyPolicy } from '@/api/archive'

definePage({
  style: {
    navigationBarTitleText: '隐私协议',
  },
})

const policy = ref<PrivacyPolicy | null>(null)
const loading = ref(false)
const opening = ref(false)
const errorMessage = ref('')
/** 小程序端下载后的本地临时路径，避免重复下载。 */
const localPath = ref('')

const available = computed(() => !!policy.value?.available && !!policy.value?.url)

const updatedText = computed(() => {
  const value = policy.value?.updatedAt
  if (!value)
    return '—'
  return value.replace('T', ' ').slice(0, 16)
})

/**
 * 每次进入页面都重新请求：服务端返回的是短期签名地址，
 * 管理端替换 PDF 后无需客户端发版即可看到新版本。
 */
async function load() {
  loading.value = true
  errorMessage.value = ''
  localPath.value = ''
  try {
    policy.value = await fetchPrivacyPolicy()
  }
  catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '协议加载失败'
    policy.value = null
  }
  finally {
    loading.value = false
  }
}

/** 小程序：下载到本地后用原生阅读器打开 PDF。 */
function openInMiniProgram(url: string) {
  uni.showLoading({ title: '正在打开', mask: true })
  const doOpen = (filePath: string) => {
    uni.openDocument({
      filePath,
      fileType: 'pdf',
      showMenu: true,
      success: () => uni.hideLoading(),
      fail: () => {
        uni.hideLoading()
        errorMessage.value = '无法打开 PDF，请稍后重试'
      },
    })
  }
  if (localPath.value) {
    doOpen(localPath.value)
    return
  }
  uni.downloadFile({
    url,
    success: (res) => {
      if (res.statusCode !== 200) {
        uni.hideLoading()
        errorMessage.value = `协议下载失败（${res.statusCode}）`
        return
      }
      localPath.value = res.tempFilePath
      doOpen(res.tempFilePath)
    },
    fail: () => {
      uni.hideLoading()
      errorMessage.value = '协议下载失败，请检查网络'
    },
  })
}

function open() {
  const url = policy.value?.url
  if (!url) {
    errorMessage.value = '协议地址不可用，请重新获取'
    return
  }
  opening.value = true
  errorMessage.value = ''
  // #ifdef H5
  window.open(url, '_blank')
  opening.value = false
  // #endif
  // #ifndef H5
  openInMiniProgram(url)
  opening.value = false
  // #endif
}

onShow(load)
</script>

<template>
  <view class="page">
    <view class="hero">
      <view class="hero-icon"><text class="hero-glyph">PDF</text></view>
      <view class="hero-title">
        隐私协议
      </view>
      <view class="hero-desc">
        我们如何收集、使用与保护你的个人信息。协议由平台统一发布，内容以最新版本为准。
      </view>
    </view>

    <view v-if="loading" class="state-card">
      <view class="state-desc">
        正在获取最新协议…
      </view>
    </view>

    <view v-else-if="errorMessage && !available" class="state-card">
      <view class="state-title">
        获取失败
      </view>
      <view class="state-desc">
        {{ errorMessage }}
      </view>
      <wd-button size="small" @click="load">
        重新获取
      </wd-button>
    </view>

    <view v-else-if="!available" class="state-card">
      <view class="state-title">
        协议暂未发布
      </view>
      <view class="state-desc">
        平台管理员尚未上传隐私协议文件。请稍后再来查看，或联系企业管理员。
      </view>
      <wd-button size="small" plain @click="load">
        刷新
      </wd-button>
    </view>

    <template v-else>
      <view class="meta-card">
        <view class="meta-row">
          <text class="meta-label">文件</text>
          <text class="meta-value">{{ policy?.filename }}</text>
        </view>
        <view class="meta-row">
          <text class="meta-label">更新时间</text>
          <text class="meta-value">{{ updatedText }}</text>
        </view>
        <view class="meta-row">
          <text class="meta-label">格式</text>
          <text class="meta-value">PDF</text>
        </view>
      </view>

      <view v-if="errorMessage" class="error-banner">
        {{ errorMessage }}
      </view>

      <wd-button type="primary" block :loading="opening" @click="open">
        查看协议全文
      </wd-button>

      <view class="secondary">
        <wd-button size="small" plain block @click="load">
          重新获取最新版本
        </wd-button>
      </view>

      <view class="note">
        协议链接为短期有效地址，每次进入本页都会重新获取，确保看到的是平台当前发布的版本。
      </view>
    </template>
  </view>
</template>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx 48rpx;
  background-color: #f4f6f9;
}

.hero {
  padding: 40rpx 32rpx;
  border-radius: 24rpx;
  background: linear-gradient(145deg, #0e5a8a, #0b2c42);
  text-align: center;
}

.hero-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 96rpx;
  height: 96rpx;
  margin: 0 auto;
  border-radius: 24rpx;
  background: rgba(255, 255, 255, 0.14);
}

:deep(.hero-icon-inner) {
  color: #ffffff;
}

.hero-glyph {
  color: #ffffff;
  font-size: 26rpx;
  font-weight: 700;
  letter-spacing: 1rpx;
}

.hero-title {
  margin-top: 20rpx;
  font-size: 34rpx;
  font-weight: 600;
  color: #ffffff;
}

.hero-desc {
  margin-top: 12rpx;
  font-size: 23rpx;
  line-height: 1.7;
  color: rgba(255, 255, 255, 0.72);
}

.state-card {
  margin-top: 24rpx;
  padding: 48rpx 32rpx;
  border: 1rpx solid #e2e8ef;
  border-radius: 20rpx;
  background-color: #ffffff;
  text-align: center;
}

.state-title {
  font-size: 28rpx;
  font-weight: 600;
  color: #16202b;
}

.state-desc {
  margin: 12rpx 0 24rpx;
  font-size: 24rpx;
  line-height: 1.6;
  color: #8a97a5;
}

.meta-card {
  margin: 24rpx 0;
  padding: 8rpx 28rpx;
  border: 1rpx solid #e2e8ef;
  border-radius: 20rpx;
  background-color: #ffffff;
}

.meta-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
  padding: 24rpx 0;
  border-bottom: 1rpx solid #f0f3f7;

  &:last-child {
    border-bottom: none;
  }
}

.meta-label {
  font-size: 24rpx;
  color: #8a97a5;
}

.meta-value {
  font-size: 25rpx;
  color: #16202b;
  text-align: right;
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

.secondary {
  margin-top: 16rpx;
}

.note {
  margin-top: 28rpx;
  font-size: 22rpx;
  line-height: 1.7;
  color: #8a97a5;
}
</style>
