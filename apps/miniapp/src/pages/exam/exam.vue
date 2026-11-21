<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { fetchExamCards, scorePercent } from '@/api/exam'
import type { ExamCard } from '@/api/exam'
import { useTokenStore } from '@/store/token'

definePage({
  style: {
    navigationBarTitleText: '考试',
  },
})

const tokenStore = useTokenStore()
const cards = ref<ExamCard[]>([])
const loading = ref(false)

/**
 * 考试页只列有已发布试卷的任务（服务端已过滤），不展示历史记录。
 *
 * 能否开考、是否已通过、最高分都由服务端给出，前端不推算——
 * 判定口径只在服务端一处，避免「按钮可点但开考被拒」。
 */
async function load() {
  if (!tokenStore.hasLogin) {
    cards.value = []
    return
  }
  loading.value = true
  try {
    cards.value = await fetchExamCards()
  }
  catch {
    cards.value = []
  }
  finally {
    loading.value = false
  }
}

function goLogin() {
  uni.navigateTo({ url: '/pages/login/login' })
}

/** 未考过的卡片：确认后直接进入答题页（不是成绩页）。 */
async function start(card: ExamCard) {
  if (!card.canStart) {
    uni.showToast({ title: card.reason || '暂不可考试', icon: 'none' })
    return
  }
  const paperId = card.paperIds[0]
  if (!paperId) {
    uni.showToast({ title: '该培训包还没有发布试卷', icon: 'none' })
    return
  }
  const confirm = await new Promise<boolean>((resolve) => {
    uni.showModal({
      title: '开始考试',
      content: '考试期间切换到其他页面会自动交卷（按已答计分）。确定开始吗？',
      success: res => resolve(!!res.confirm),
      fail: () => resolve(false),
    })
  })
  if (!confirm)
    return
  uni.navigateTo({
    url: `/pages/exam/take?assignmentId=${card.assignmentId}&paperId=${paperId}`,
  })
}

/** 考过的卡片：进入成绩详情（展示最高分，并可重新考试）。 */
function openDetail(card: ExamCard) {
  uni.navigateTo({ url: `/pages/exam/result?assignmentId=${card.assignmentId}` })
}

onShow(load)
</script>

<template>
  <view class="page">
    <view v-if="!tokenStore.hasLogin" class="hint-card">
      <view class="hint-title">
        尚未登录
      </view>
      <view class="hint-desc">
        登录后可查看考试安排与成绩。
      </view>
      <wd-button size="small" type="primary" @click="goLogin">
        去登录
      </wd-button>
    </view>

    <view v-else-if="loading" class="hint-card">
      <view class="hint-desc">
        加载中…
      </view>
    </view>

    <view v-else-if="!cards.length" class="hint-card">
      <view class="hint-title">
        暂无可考试的试卷
      </view>
      <view class="hint-desc">
        企业下发任务并配置试卷后，完成全部课程学习即可参加考试。
      </view>
    </view>

    <template v-else>
      <view
        v-for="card in cards"
        :key="card.assignmentId"
        class="exam-card"
        @click="card.attempted ? openDetail(card) : undefined"
      >
        <view class="card-head">
          <text class="card-title">{{ card.taskTitle || '培训任务' }}</text>
          <text
            v-if="card.best"
            class="badge"
            :class="card.best.passed ? 'badge-pass' : 'badge-fail'"
          >
            {{ card.best.passed ? '已通过' : '未通过' }}
          </text>
        </view>

        <!-- 未考过：只显示开始考试；未学完时置灰并说明原因 -->
        <template v-if="!card.attempted">
          <wd-button
            size="small"
            type="primary"
            :disabled="!card.canStart"
            @click.stop="start(card)"
          >
            开始考试
          </wd-button>
          <view v-if="!card.canStart" class="blocked">
            {{ card.reason || '暂不可考试' }}
          </view>
        </template>

        <!-- 考过：显示最高分摘要，点击卡片看详情 -->
        <template v-else-if="card.best">
          <view class="best-row">
            <text class="best-score">
              {{ card.best.scoreSum }} / {{ card.best.totalScore }}
            </text>
            <text class="best-percent">
              {{ scorePercent(card.best) }}%　及格线 {{ card.best.passScore }}%
            </text>
          </view>
          <view class="best-hint">
            最高分 · 点击查看详情
          </view>
        </template>
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

.hint-card {
  padding: 48rpx 32rpx;
  border-radius: 16rpx;
  background-color: #ffffff;
  text-align: center;
}

.hint-title {
  margin-bottom: 12rpx;
  color: #1f2733;
  font-size: 30rpx;
  font-weight: 600;
}

.hint-desc {
  margin-bottom: 24rpx;
  color: #6b7885;
  font-size: 24rpx;
  line-height: 1.6;
}

.exam-card {
  margin-bottom: 20rpx;
  padding: 28rpx;
  border-radius: 16rpx;
  background-color: #ffffff;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20rpx;
}

.card-title {
  flex: 1;
  color: #1f2733;
  font-size: 30rpx;
  font-weight: 600;
}

.badge {
  margin-left: 16rpx;
  padding: 4rpx 16rpx;
  border-radius: 999rpx;
  font-size: 22rpx;
}

.badge-pass {
  background-color: #e7f6ec;
  color: #1f7a3d;
}

.badge-fail {
  background-color: #fdecea;
  color: #c0392b;
}

.blocked {
  margin-top: 16rpx;
  padding: 16rpx 20rpx;
  border-radius: 10rpx;
  background-color: #fff7e6;
  color: #b26a00;
  font-size: 23rpx;
}

.best-row {
  display: flex;
  align-items: baseline;
  gap: 16rpx;
}

.best-score {
  color: #1f2733;
  font-size: 40rpx;
  font-weight: 600;
}

.best-percent {
  color: #6b7885;
  font-size: 23rpx;
}

.best-hint {
  margin-top: 12rpx;
  color: #8a97a5;
  font-size: 22rpx;
}
</style>
