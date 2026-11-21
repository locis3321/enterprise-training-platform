<script lang="ts" setup>
import { getCurrentInstance, onUnmounted, ref } from 'vue'

/**
 * 学习用视频播放器：**禁止拖动进度**。
 *
 * <p>为什么不用原生 controls：原生控制条自带可拖动的进度条，学员可以直接跳到末尾，
 * 服务端虽然按真实墙钟计时（拖进度不会多记时长），但用户体验上仍会出现「拖到底就以为看完了」，
 * 而且与服务端判定不一致。因此这里只提供「播放 / 暂停」，进度以只读方式展示。
 *
 * <p>同时关闭微信 video 的两个进度入口：
 * <ul>
 *   <li>{@code controls=false}：不渲染原生控制条；</li>
 *   <li>{@code enable-progress-gesture=false}：长按/拖动画面也不改变进度。</li>
 * </ul>
 * 另关闭全屏按钮：全屏下微信会接管控制条，等于重新打开拖动入口。
 */
const props = withDefaults(defineProps<{
  videoId: string
  src: string
  autoplay?: boolean
}>(), {
  autoplay: false,
})

const emit = defineEmits<{
  (e: 'play'): void
  (e: 'pause'): void
  (e: 'ended'): void
  (e: 'timeupdate', payload: { currentTime: number, duration: number }): void
  /** 媒体总时长就绪（loadedmetadata 或首次有效 timeupdate）。 */
  (e: 'duration', seconds: number): void
}>()

const instance = getCurrentInstance()
const playing = ref(false)
const currentTime = ref(0)
const duration = ref(0)
const ready = ref(false)
/** 自定义全屏：用页面级布局放大，而不是 video 原生全屏——原生全屏必然带出可拖动的进度条。 */
const fullscreen = ref(false)

function context() {
  return uni.createVideoContext(props.videoId, instance?.proxy as any)
}

function toggleFullscreen() {
  fullscreen.value = !fullscreen.value
}

/** 播放/暂停按钮：只控制播放状态，不涉及进度。 */
function toggle() {
  if (playing.value) {
    context().pause()
  }
  else {
    context().play()
  }
}

function onPlay() {
  playing.value = true
  emit('play')
}

function onPause() {
  playing.value = false
  emit('pause')
}

function onEnded() {
  playing.value = false
  emit('ended')
}

/** 只读取播放位置用于展示与诊断，不作为完成度依据。 */
function onTimeUpdate(event: any) {
  const t = Number(event?.detail?.currentTime ?? 0)
  const d = Number(event?.detail?.duration ?? 0)
  if (!Number.isNaN(t)) {
    currentTime.value = t
  }
  if (!Number.isNaN(d) && d > 0 && d !== duration.value) {
    duration.value = d
    emit('duration', Math.round(d))
  }
  emit('timeupdate', { currentTime: currentTime.value, duration: duration.value })
}

function onLoadedMetadata(event: any) {
  const d = Number(event?.detail?.duration ?? 0)
  if (!Number.isNaN(d) && d > 0) {
    duration.value = d
    emit('duration', Math.round(d))
  }
  ready.value = true
}

const percent = () => {
  if (!duration.value) {
    return 0
  }
  return Math.min(100, Math.round((currentTime.value / duration.value) * 100))
}

function fmt(seconds: number) {
  const s = Math.max(0, Math.floor(seconds))
  const m = Math.floor(s / 60)
  const r = s % 60
  return `${m}:${String(r).padStart(2, '0')}`
}

onUnmounted(() => {
  // 离开页面时必须停止播放，避免切页后音频继续、心跳定时器残留
  try {
    context().pause()
  }
  catch {
    // 页面销毁时上下文可能已失效，忽略即可
  }
})
</script>

<template>
  <view class="player" :class="{ 'player-fullscreen': fullscreen }">
    <video
      :id="videoId"
      class="video"
      :class="{ 'video-fullscreen': fullscreen }"
      :src="src"
      :autoplay="autoplay"
      :controls="false"
      :enable-progress-gesture="false"
      :show-center-play-btn="false"
      :show-play-btn="false"
      :show-fullscreen-btn="false"
      :show-progress="false"
      object-fit="contain"
      @play="onPlay"
      @pause="onPause"
      @ended="onEnded"
      @timeupdate="onTimeUpdate"
      @loadedmetadata="onLoadedMetadata"
    />

    <!-- 加载中：不显示播放按钮，避免用户快速点击触发重复心跳 -->
    <view v-if="!ready" class="overlay">
      <text class="loading-text">视频加载中…</text>
    </view>

    <!-- 只读进度：不可点击、不可拖动 -->
    <view v-else-if="!playing" class="overlay">
      <view class="center-btn" @click.stop="toggle">
        ▶
      </view>
    </view>

    <view class="bar" :class="{ 'bar-fullscreen': fullscreen }">
      <view class="play-toggle" @click.stop="toggle">
        {{ playing ? '暂停' : '播放' }}
      </view>
      <view class="track">
        <view class="fill" :style="{ width: `${percent()}%` }" />
      </view>
      <text class="time">{{ fmt(currentTime) }} / {{ ready || duration ? fmt(duration) : '--:--' }}</text>
      <view class="fullscreen-toggle" @click.stop="toggleFullscreen">
        {{ fullscreen ? '退出全屏' : '全屏' }}
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.player {
  position: relative;
  width: 100%;
  background-color: #000000;
}

.video {
  width: 100%;
  height: 56.25vw;
  max-height: 60vh;
  background-color: #000000;
}

/* 自定义全屏：铺满视口，仍不提供可拖动进度 */
.player-fullscreen {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 9999;
  display: flex;
  flex-direction: column;
  background-color: #000000;
}

.video-fullscreen {
  flex: 1;
  width: 100%;
  height: auto;
  max-height: none;
}

.bar-fullscreen {
  flex: none;
  padding-bottom: calc(16rpx + env(safe-area-inset-bottom));
}

.loading-text {
  color: #cfd8e3;
  font-size: 24rpx;
}

.fullscreen-toggle {
  flex: none;
  padding: 6rpx 20rpx;
  border: 1rpx solid #2f6fed;
  border-radius: 999rpx;
  color: #2f6fed;
  font-size: 22rpx;
}

.overlay {
  position: absolute;
  top: 0;
  right: 0;
  left: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  height: calc(100% - 88rpx);
  pointer-events: none;
}

.center-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 108rpx;
  height: 108rpx;
  border-radius: 50%;
  background-color: rgba(0, 0, 0, 0.55);
  color: #ffffff;
  font-size: 44rpx;
  pointer-events: auto;
}

.bar {
  display: flex;
  gap: 16rpx;
  align-items: center;
  padding: 16rpx 24rpx;
  background-color: #0b0f14;
}

.play-toggle {
  flex: none;
  padding: 6rpx 20rpx;
  border: 1rpx solid #2f6fed;
  border-radius: 999rpx;
  color: #2f6fed;
  font-size: 22rpx;
}

/* 只读轨道：不绑定任何手势 */
.track {
  flex: 1;
  height: 6rpx;
  overflow: hidden;
  border-radius: 999rpx;
  background-color: #2a313a;
}

.fill {
  height: 100%;
  background-color: #2f6fed;
}

.time {
  flex: none;
  color: #8a97a5;
  font-size: 21rpx;
}
</style>
