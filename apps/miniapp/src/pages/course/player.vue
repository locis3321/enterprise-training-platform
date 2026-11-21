<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { ref } from 'vue'
import SecureVideo from '@/components/SecureVideo/SecureVideo.vue'

definePage({
  style: {
    navigationBarTitleText: '视频学习',
  },
})

const videoUrl = ref('')
const title = ref('视频学习')

/**
 * 课程库预览播放器。
 *
 * <p>**不承担学习计时**：心跳必须绑定 assignmentId，而本页没有任务上下文。
 * 真正的学习入口是 `pages/task/learn`（带 assignmentId，按 30 秒节奏上报心跳）。
 * 保留本页是为了课程库浏览仍可播放，页面顶部已明确标注「不计入学习进度」。
 */
onLoad((query) => {
  const url = query?.url ? decodeURIComponent(String(query.url)) : ''
  videoUrl.value = url
  if (query?.title) {
    title.value = decodeURIComponent(String(query.title))
    uni.setNavigationBarTitle({ title: title.value })
  }
})
</script>

<template>
  <view class="page">
    <view class="preview-note">
      课程预览模式：此页不计入学习进度。要记录学习时长，请从「我的培训任务」进入学习。
    </view>

    <view class="video-wrap">
      <SecureVideo
        v-if="videoUrl"
        video-id="preview-video"
        :src="videoUrl"
        autoplay
      />
      <view v-else class="empty">
        缺少视频地址，请返回课程重新打开。
      </view>
    </view>
    <view class="title">
      {{ title }}
    </view>
    <view class="hint">
      本页面仅做课程库浏览播放，服务端不会记录观看时长；学习需通过企业下发的培训任务进入。
    </view>
  </view>
</template>

<style lang="scss" scoped>
.page {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  // 播放器垂直居中：内容区整体居中，避免贴顶
  justify-content: center;
  padding: 24rpx 0;
  background-color: #0b0f14;
}

.preview-note {
  margin: 0 24rpx 24rpx;
  padding: 16rpx 20rpx;
  border-radius: 10rpx;
  background-color: rgba(255, 193, 7, 0.12);
  color: #e0b25a;
  font-size: 22rpx;
  line-height: 1.6;
}

.video-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
}

.empty {
  padding: 80rpx 32rpx;
  color: #8a97a5;
  font-size: 24rpx;
  text-align: center;
}

.title {
  padding: 24rpx;
  color: #ffffff;
  font-size: 28rpx;
  font-weight: 600;
  text-align: center;
}

.hint {
  padding: 0 24rpx 32rpx;
  color: #6b7885;
  font-size: 22rpx;
  line-height: 1.6;
  text-align: center;
}
</style>
