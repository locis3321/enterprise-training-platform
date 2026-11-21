<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { fetchBestAttempt, fetchExamAvailability, scorePercent } from '@/api/exam'
import type { BestAttempt } from '@/api/exam'

definePage({
  style: {
    navigationBarTitleText: '考试成绩',
  },
})

const assignmentId = ref(0)
const best = ref<BestAttempt | null>(null)
const paperIds = ref<number[]>([])
const canStart = ref(false)
const blockedReason = ref('')
const loading = ref(false)

/**
 * 成绩只取最高分：服务端按「百分比 → 得分 → 交卷时间新」选出最佳一场，
 * 前端不拼装、不比较，避免口径分叉。
 */
const percent = computed(() => (best.value ? scorePercent(best.value) : 0))

async function load() {
  if (!assignmentId.value) {
    return
  }
  loading.value = true
  try {
    const [bestResult, availability] = await Promise.all([
      fetchBestAttempt(assignmentId.value).catch(() => null),
      fetchExamAvailability(assignmentId.value)
        .catch(() => ({ canStart: false, reason: '状态未知', paperIds: [] as number[] })),
    ])
    best.value = bestResult
    paperIds.value = availability.paperIds ?? []
    // 已通过后仍可重考；此处只要求「有试卷」，不要求学习进度重新达标
    canStart.value = (availability.paperIds ?? []).length > 0
    blockedReason.value = canStart.value ? '' : (availability.reason || '')
  }
  finally {
    loading.value = false
  }
}

onLoad(async (query) => {
  assignmentId.value = Number(query?.assignmentId ?? 0)
  await load()
})

function goExams() {
  uni.switchTab({ url: '/pages/exam/exam' })
}

/** 重新考试：直接进入答题页，通过后同样允许重考。 */
async function retake() {
  if (!canStart.value || !paperIds.value.length) {
    uni.showToast({ title: blockedReason.value || '暂不可考试', icon: 'none' })
    return
  }
  const confirm = await new Promise<boolean>((resolve) => {
    uni.showModal({
      title: '重新考试',
      content: '考试期间切换到其他页面会自动交卷（按已答计分）。确定开始吗？',
      success: res => resolve(!!res.confirm),
      fail: () => resolve(false),
    })
  })
  if (!confirm)
    return
  uni.navigateTo({
    url: `/pages/exam/take?assignmentId=${assignmentId.value}&paperId=${paperIds.value[0]}`,
  })
}
</script>

<template>
  <view class="page">
    <view v-if="loading" class="card">
      <view class="desc">
        正在读取成绩…
      </view>
    </view>

    <view v-else-if="!best" class="card">
      <view class="title">
        还没有考试成绩
      </view>
      <view class="desc">
        完成一次考试后，这里会显示最高分。
      </view>
      <wd-button size="small" type="primary" @click="goExams">
        返回考试
      </wd-button>
    </view>

    <template v-else>
      <view class="card result" :class="{ pass: best.passed, fail: !best.passed }">
        <view class="verdict">
          {{ best.passed ? '已通过' : '未通过' }}
        </view>
        <view class="score">
          {{ best.scoreSum }} / {{ best.totalScore }}
        </view>
        <view class="percent">
          {{ percent }}%　　及格线 {{ best.passScore }}%
        </view>
      </view>

      <view class="card">
        <view class="row">
          <text class="label">最佳成绩</text>
          <text>多次考试只取最高分</text>
        </view>
        <view class="row">
          <text class="label">交卷时间</text>
          <text>
            {{ best.submittedAt ? best.submittedAt.replace('T', ' ').slice(0, 16) : '—' }}
          </text>
        </view>
      </view>

      <view class="note">
        成绩由服务端判分；多选必须与正确答案完全一致才得分。正确答案不对外提供。
      </view>

      <view class="actions">
        <wd-button v-if="canStart" type="primary" block @click="retake">
          重新考试
        </wd-button>
        <view v-else class="blocked">
          {{ blockedReason || '暂不可重新考试' }}
        </view>
        <wd-button plain block custom-class="secondary-button" @click="goExams">
          返回考试列表
        </wd-button>
      </view>
    </template>
  </view>
</template>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 24rpx;
  background-color: #f5f7fb;
}

.card {
  margin-bottom: 20rpx;
  padding: 32rpx;
  border-radius: 16rpx;
  background-color: #ffffff;
}

.result {
  text-align: center;
}

.verdict {
  font-size: 36rpx;
  font-weight: 600;
}

.result.pass .verdict {
  color: #1f7a3d;
}

.result.fail .verdict {
  color: #c0392b;
}

.score {
  margin-top: 16rpx;
  color: #1f2733;
  font-size: 56rpx;
  font-weight: 600;
}

.percent {
  margin-top: 8rpx;
  color: #6b7885;
  font-size: 24rpx;
}

.title {
  margin-bottom: 12rpx;
  color: #1f2733;
  font-size: 30rpx;
  font-weight: 600;
}

.desc {
  margin-bottom: 24rpx;
  color: #6b7885;
  font-size: 24rpx;
  line-height: 1.6;
}

.row {
  display: flex;
  justify-content: space-between;
  padding: 10rpx 0;
  color: #3d4757;
  font-size: 24rpx;
}

.label {
  color: #6b7885;
}

.note {
  margin-bottom: 24rpx;
  color: #8a97a5;
  font-size: 22rpx;
  line-height: 1.6;
}

.actions {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}

.blocked {
  padding: 20rpx 24rpx;
  border-radius: 10rpx;
  background-color: #fff7e6;
  color: #b26a00;
  font-size: 23rpx;
  text-align: center;
}

:deep(.secondary-button) {
  margin-top: 16rpx;
}
</style>
