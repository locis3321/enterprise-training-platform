<script setup lang="ts">
import { onHide, onLoad, onUnload } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { saveExamAnswer, startExam, submitExam } from '@/api/exam'
import type { ExamAttempt, ExamQuestion } from '@/api/exam'

definePage({
  style: {
    navigationBarTitleText: '考试中',
  },
})

const assignmentId = ref(0)
const paperId = ref(0)
const attempt = ref<ExamAttempt | null>(null)
const loading = ref(false)
const blockedReason = ref('')
/** 学员已选：questionId → labels */
const picked = ref<Record<number, string[]>>({})
const submitting = ref(false)
/** 保证 onHide 与 onUnload 只交卷一次（用 ref 以便模板与回调共享同一状态）。 */
const submitted = ref(false)

/**
 * 剩余秒数。
 *
 * <p>基于服务端返回的 {@code deadlineAt} 计算**偏移量**，再在本地按
 * 每秒递减推算——不直接用设备时间与 deadline 比较（设备时间可被修改）。
 */
const remaining = ref(0)
let tickTimer: ReturnType<typeof setInterval> | null = null

const remainingText = computed(() => {
  const s = Math.max(0, remaining.value)
  const m = Math.floor(s / 60)
  const sec = s % 60
  return `${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}`
})

const answeredCount = computed(() => Object.values(picked.value).filter(v => v.length > 0).length)

onLoad(async (query) => {
  assignmentId.value = Number(query?.assignmentId ?? 0)
  paperId.value = Number(query?.paperId ?? 0)
  if (!assignmentId.value || !paperId.value) {
    blockedReason.value = '缺少考试参数，请返回考试列表重新进入'
    return
  }
  await load()
})

async function load() {
  loading.value = true
  blockedReason.value = ''
  try {
    const result = await startExam(paperId.value, assignmentId.value)
    attempt.value = result
    // 恢复已保存的答案
    const restored: Record<number, string[]> = {}
    for (const a of result.answers ?? []) {
      restored[a.questionId] = a.labels ?? []
    }
    picked.value = restored
    if (result.resumed) {
      uni.showToast({ title: '已恢复上次未完成的考试', icon: 'none' })
    }
    if (result.status === 'SUBMITTED') {
      goResult(result.attemptId)
      return
    }
    startCountdown(result.deadlineAt)
  }
  catch (error: any) {
    blockedReason.value = error?.message || '无法开始考试'
  }
  finally {
    loading.value = false
  }
}

/** 按服务端 deadline 算偏移，本地递减；归零自动交卷。 */
function startCountdown(deadlineAt: string) {
  if (tickTimer) {
    clearInterval(tickTimer)
    tickTimer = null
  }
  const deadline = new Date(deadlineAt).getTime()
  const offset = deadline - Date.now()
  const startedAt = Date.now()
  remaining.value = Math.max(0, Math.floor(offset / 1000))

  tickTimer = setInterval(() => {
    const left = Math.floor((offset - (Date.now() - startedAt)) / 1000)
    remaining.value = Math.max(0, left)
    if (left <= 0) {
      stopCountdown()
      // 超时自动交卷（服务端也会按截止时间判定）
      void doSubmit(true)
    }
  }, 1000)
}

function stopCountdown() {
  if (tickTimer) {
    clearInterval(tickTimer)
    tickTimer = null
  }
}

function isPicked(questionId: number, label: string) {
  return (picked.value[questionId] ?? []).includes(label)
}

/** 单选：选一个即替换；多选：切换。每次变化立即保存（切页自动交卷时不丢答案）。 */
async function choose(question: ExamQuestion, label: string) {
  if (submitted.value)
    return
  if (question.type === 'SINGLE') {
    picked.value[question.questionId] = [label]
  }
  else {
    const current = picked.value[question.questionId] ?? []
    picked.value[question.questionId] = current.includes(label)
      ? current.filter(l => l !== label)
      : [...current, label]
  }
  await saveExamAnswer(attempt.value!.attemptId, question.questionId, picked.value[question.questionId])
    .catch(() => uni.showToast({ title: '答案保存失败，请重试', icon: 'none' }))
}

async function doSubmit(auto: boolean) {
  if (submitted.value || !attempt.value)
    return
  submitted.value = true
  submitting.value = true
  stopCountdown()
  try {
    const result = await submitExam(attempt.value.attemptId, auto)
    goResult(result.attemptId)
  }
  catch (error: any) {
    submitted.value = false
    uni.showModal({
      title: '交卷失败',
      content: error?.message || '请稍后重试',
      showCancel: false,
    })
  }
  finally {
    submitting.value = false
  }
}

function confirmSubmit() {
  uni.showModal({
    title: '确认交卷',
    content: `已作答 ${answeredCount.value} / ${attempt.value?.questions.length ?? 0} 题，交卷后不可修改。`,
    success: (res) => {
      if (res.confirm)
        void doSubmit(false)
    },
  })
}

function goResult(attemptId: number) {
  uni.redirectTo({ url: `/pages/exam/result?attemptId=${attemptId}` })
}

/**
 * 切后台立即自动交卷：这是产品规则（切页视为放弃），且 `onHide` 不一定可靠，
 * 因此 `onUnload` 也兜一次，用 submitted 标记保证只提交一次。
 */
onHide(() => {
  if (attempt.value && attempt.value.status === 'IN_PROGRESS' && !submitted.value) {
    void doSubmit(true)
  }
})

onUnload(() => {
  stopCountdown()
  if (attempt.value && attempt.value.status === 'IN_PROGRESS' && !submitted.value) {
    void doSubmit(true)
  }
})
</script>

<template>
  <view class="page">
    <view v-if="loading" class="hint">
      正在准备试卷…
    </view>

    <view v-else-if="blockedReason" class="blocked">
      <view class="blocked-title">
        无法开始考试
      </view>
      <view class="blocked-desc">
        {{ blockedReason }}
      </view>
      <wd-button size="small" @click="goResult(0)">
        返回
      </wd-button>
    </view>

    <template v-else-if="attempt">
      <view class="bar">
        <text class="countdown">剩余 {{ remainingText }}</text>
        <text class="progress">已答 {{ answeredCount }}/{{ attempt.questions.length }}</text>
      </view>

      <view class="hint-line">
        切换到其他页面会自动交卷，请一次性完成作答。
      </view>

      <view
        v-for="(q, index) in attempt.questions"
        :key="q.questionId"
        class="question"
      >
        <view class="stem">
          <text class="index">{{ index + 1 }}.</text>
          <text class="type-tag">{{ q.type === 'SINGLE' ? '单选' : '多选' }}</text>
          <text>{{ q.stem }}</text>
        </view>
        <view
          v-for="opt in q.options"
          :key="opt.label"
          class="option"
          :class="{ active: isPicked(q.questionId, opt.label) }"
          @click="choose(q, opt.label)"
        >
          <text class="opt-label">{{ opt.label }}</text>
          <text class="opt-content">{{ opt.content }}</text>
        </view>
      </view>

      <view class="footer">
        <wd-button type="primary" block :loading="submitting" @click="confirmSubmit">
          交卷
        </wd-button>
      </view>
    </template>
  </view>
</template>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 24rpx;
  padding-bottom: 200rpx;
  background-color: #f5f7fb;
}

.hint {
  padding: 80rpx 32rpx;
  color: #6b7885;
  font-size: 24rpx;
  text-align: center;
}

.blocked {
  padding: 80rpx 40rpx;
  text-align: center;
}

.blocked-title {
  margin-bottom: 14rpx;
  color: #1f2733;
  font-size: 30rpx;
  font-weight: 600;
}

.blocked-desc {
  margin-bottom: 28rpx;
  color: #6b7885;
  font-size: 24rpx;
  line-height: 1.6;
}

.bar {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  justify-content: space-between;
  margin: -24rpx -24rpx 16rpx;
  padding: 20rpx 24rpx;
  background-color: #ffffff;
}

.countdown {
  color: #b26a00;
  font-size: 28rpx;
  font-weight: 600;
}

.progress {
  color: #6b7885;
  font-size: 24rpx;
}

.hint-line {
  margin-bottom: 16rpx;
  padding: 14rpx 18rpx;
  border-radius: 10rpx;
  background-color: #fff7e6;
  color: #b26a00;
  font-size: 22rpx;
}

.question {
  margin-bottom: 20rpx;
  padding: 24rpx;
  border-radius: 16rpx;
  background-color: #ffffff;
}

.stem {
  margin-bottom: 18rpx;
  color: #1f2733;
  font-size: 27rpx;
  line-height: 1.6;
}

.index {
  margin-right: 8rpx;
  font-weight: 600;
}

.type-tag {
  margin-right: 10rpx;
  padding: 2rpx 10rpx;
  border-radius: 6rpx;
  background-color: #e7eeff;
  color: #2f6fed;
  font-size: 20rpx;
}

.option {
  display: flex;
  gap: 12rpx;
  align-items: flex-start;
  margin-bottom: 12rpx;
  padding: 16rpx 18rpx;
  border: 1rpx solid #e2e8ef;
  border-radius: 10rpx;
  background-color: #fafcff;
}

.option.active {
  border-color: #2f6fed;
  background-color: #eff4ff;
}

.opt-label {
  flex: none;
  color: #2f6fed;
  font-size: 24rpx;
  font-weight: 600;
}

.opt-content {
  flex: 1;
  color: #3d4757;
  font-size: 24rpx;
  line-height: 1.5;
}

.footer {
  position: fixed;
  right: 0;
  bottom: 0;
  left: 0;
  padding: 20rpx 24rpx;
  background-color: #ffffff;
  box-shadow: 0 -2rpx 12rpx rgba(0, 0, 0, 0.06);
}
</style>
