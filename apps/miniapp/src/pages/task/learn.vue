<script setup lang="ts">
import { onHide, onLoad, onUnload } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { fetchAssignmentResourceUrl } from '@/api/training'
import { fetchAssignmentProgress, isLearnableConflict, sendHeartbeat } from '@/api/learning'
import { createHeartbeatTicker } from '@/utils/heartbeat'
import type { HeartbeatReason, HeartbeatTicker } from '@/utils/heartbeat'
import SecureVideo from '@/components/SecureVideo/SecureVideo.vue'

definePage({
  style: {
    navigationBarTitleText: '学习中',
  },
})

const assignmentId = ref(0)
const courseId = ref(0)
const sectionId = ref(0)
const resourceId = ref(0)
const videoUrl = ref('')
const title = ref('视频学习')
const loading = ref(false)
const blockedReason = ref('')
const completed = ref(false)
const assignmentStatus = ref('')

/**
 * 服务端返回的进度快照。
 *
 * <p>章节只有「已完成 / 未完成」，课程进度 = 已完成章节数 / 总章节数，
 * 不在前端按时长推算；本页只用于驱动心跳与完成判定，不展示进度数字。
 */
let ticker: HeartbeatTicker | null = null
let lastPosition = 0
/** 播放器读到并上报过一次的媒体时长；服务端首次写入后锁定。 */
let mediaDuration = 0
const startedAtMs = Date.now()

/** 只有能播放时才允许上报，避免无谓请求污染错误提示。 */
const canPlay = computed(() => !!videoUrl.value && !blockedReason.value)

onLoad(async (query) => {
  assignmentId.value = Number(query?.assignmentId ?? 0)
  courseId.value = Number(query?.courseId ?? 0)
  sectionId.value = Number(query?.sectionId ?? 0)
  resourceId.value = Number(query?.resourceId ?? 0)
  if (query?.title) {
    title.value = decodeURIComponent(String(query.title))
    uni.setNavigationBarTitle({ title: title.value })
  }
  await loadUrl()
  await loadProgress()
})

/**
 * 拉取服务端进度快照。
 *
 * <p>章节/课程百分比只用于展示；完成判定始终以服务端 `completed` 为准。
 * 心跳成功后也会刷新，让用户看到时长变化。
 */
async function loadProgress() {
  try {
    const p = await fetchAssignmentProgress(assignmentId.value)
    completed.value = p.completed
    assignmentStatus.value = p.status
  }
  catch {
    // 进度失败不影响播放；不打断用户，也不伪造数字
  }
}

async function loadUrl() {
  if (!assignmentId.value || !courseId.value || !resourceId.value) {
    blockedReason.value = '缺少学习参数，请返回任务详情重新进入'
    return
  }
  loading.value = true
  blockedReason.value = ''
  try {
    // 任务上下文路由：版本由 assignment 推导，旧任务不会读到改版后的内容
    videoUrl.value = await fetchAssignmentResourceUrl(assignmentId.value, courseId.value, resourceId.value)
  }
  catch (error) {
    blockedReason.value = isLearnableConflict(error)
      ? '任务尚未开始、已过期或已关闭，无法继续学习'
      : (error instanceof Error ? error.message : '视频地址获取失败')
  }
  finally {
    loading.value = false
  }
}

function currentPosition() {
  return Math.max(0, Math.floor(lastPosition))
}

/** 心跳请求：period 上报证明在播放，ended 时带 finished 声明该视频已完成。 */
/** 服务端心跳最小间隔 5s（Redis 防重放）；本地也节流，避免 start 与 duration 连发触发 429。 */
const MIN_SEND_GAP_MS = 6000
let lastSendAt = 0

async function send(reason: HeartbeatReason) {
  if (!canPlay.value) {
    return
  }
  // ended 必须立即上报（完成判定依赖它），其余原因按最小间隔节流
  if (reason !== 'ended') {
    const now = Date.now()
    if (now - lastSendAt < MIN_SEND_GAP_MS) {
      return
    }
    lastSendAt = now
  }
  else {
    lastSendAt = Date.now()
  }
  try {
    const result = await sendHeartbeat({
      assignmentId: assignmentId.value,
      sectionId: sectionId.value,
      resourceId: resourceId.value,
      positionSeconds: currentPosition(),
      clientTickMs: Date.now() - startedAtMs,
      mediaDurationSeconds: mediaDuration || undefined,
      finished: reason === 'ended',
    })
    assignmentStatus.value = result.assignmentStatus
    completed.value = result.completed
    // 完成后刷新进度，让章节/课程状态与服务端同步
    await loadProgress()
    if (completed.value) {
      uni.showToast({ title: '已完成本任务学习', icon: 'success' })
    }
    console.log('[heartbeat]', reason, 'finished=', result.finishedRecorded, result.assignmentStatus)
  }
  catch (error) {
    if (isLearnableConflict(error)) {
      blockedReason.value = '任务已不可学习（未开始 / 已过期 / 已关闭）'
      ticker?.dispose()
    }
  }
}

async function onPlay() {
  if (!ticker) {
    ticker = createHeartbeatTicker({
      send: async (reason: HeartbeatReason) => {
        await send(reason)
      },
    })
  }
  await ticker.start()
}

async function onPause() {
  await ticker?.pause()
}

async function onEnded() {
  await ticker?.ended()
}

function onTimeUpdate(event: any) {
  const position = Number(event?.detail?.currentTime ?? 0)
  if (!Number.isNaN(position)) {
    lastPosition = position
  }
}

/**
 * 播放器读到媒体时长。
 *
 * <p>拿到后立刻补报一次心跳（不等 30 秒），让服务端尽快固化「该看多久」；
 * 否则学员在第一个 30 秒内退出时，章节应看时长仍为空，进度无法判定。
 */
async function onDuration(seconds: number) {
  if (!seconds || seconds === mediaDuration) {
    return
  }
  mediaDuration = seconds
  if (canPlay.value) {
    await send('duration')
  }
}

/**
 * 切后台时补报一次并停表：否则末尾区间不计入，100% 阈值下会出现
 * 「看满还差一个间隔」。回到前台不自动续报，由用户再次触发播放。
 */
onHide(async () => {
  await ticker?.hide()
})

/** 离开页面必须清理定时器，避免退出后继续上报。 */
onUnload(() => {
  ticker?.dispose()
  ticker = null
})

function back() {
  uni.navigateBack()
}
</script>

<template>
  <view class="page">
    <view v-if="loading" class="hint">
      加载中…
    </view>

    <view v-else-if="blockedReason" class="blocked">
      <view class="blocked-title">
        无法继续学习
      </view>
      <view class="blocked-desc">
        {{ blockedReason }}
      </view>
      <wd-button size="small" @click="back">
        返回任务
      </wd-button>
    </view>

    <template v-else>
      <SecureVideo
        v-if="canPlay"
        video-id="learn-video"
        :src="videoUrl"
        @play="onPlay"
        @pause="onPause"
        @ended="onEnded"
        @timeupdate="onTimeUpdate"
        @duration="onDuration"
      />
      <view class="title">
        {{ title }}
      </view>
      <view class="hint-text">
        视频播放到结尾即算完成本章节。播放器已禁用拖动，必须完整观看。
      </view>
    </template>
  </view>
</template>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  background-color: #0b0f14;
}

.title {
  padding: 24rpx;
  color: #ffffff;
  font-size: 28rpx;
  font-weight: 600;
}

.hint {
  padding: 80rpx 32rpx;
  color: #8a97a5;
  font-size: 24rpx;
  text-align: center;
}

.blocked {
  padding: 80rpx 40rpx;
  text-align: center;
}

.blocked-title {
  margin-bottom: 14rpx;
  color: #ffffff;
  font-size: 30rpx;
  font-weight: 600;
}

.blocked-desc {
  margin-bottom: 28rpx;
  color: #8a97a5;
  font-size: 24rpx;
  line-height: 1.6;
}

.hint-text {
  padding: 0 24rpx 32rpx;
  color: #6b7885;
  font-size: 22rpx;
  line-height: 1.6;
}
</style>
