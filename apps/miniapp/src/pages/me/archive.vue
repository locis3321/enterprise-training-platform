<script lang="ts" setup>
import { onLoad, onShow } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import {
  fetchExamRecords,
  fetchLearningArchive,
  ratioPercent,
  recordPercent,
} from '@/api/archive'
import type { ArchiveTask, ExamRecord, LearningArchive } from '@/api/archive'
import { useTokenStore } from '@/store/token'

definePage({
  style: {
    navigationBarTitleText: '培训档案',
  },
})

const tokenStore = useTokenStore()

/** 两个 tab 合并原「培训档案」「考试记录」两个入口，避免考试历史出现两处。 */
const tab = ref<'learning' | 'exam'>('learning')

const archive = ref<LearningArchive | null>(null)
const learningLoading = ref(false)
const learningError = ref('')

const records = ref<ExamRecord[]>([])
const examTotal = ref(0)
const examPage = ref(1)
const examLoading = ref(false)
const examError = ref('')
const examLoaded = ref(false)

const hasLogin = computed(() => tokenStore.hasLogin)
const examHasMore = computed(() => records.value.length < examTotal.value)

const statusMeta: Record<string, { label: string, cls: string }> = {
  NOT_STARTED: { label: '未开始', cls: 'is-idle' },
  IN_PROGRESS: { label: '进行中', cls: 'is-doing' },
  COMPLETED: { label: '已完成', cls: 'is-done' },
}

/** 总体完成度：以章节为分母，与任务内进度同源。 */
const overallPercent = computed(() => {
  const tasks = archive.value?.tasks ?? []
  const total = tasks.reduce((sum, t) => sum + t.sectionTotal, 0)
  const done = tasks.reduce((sum, t) => sum + t.sectionCompleted, 0)
  return ratioPercent(done, total)
})

async function loadLearning() {
  if (!tokenStore.hasLogin) {
    archive.value = null
    return
  }
  learningLoading.value = true
  learningError.value = ''
  try {
    archive.value = await fetchLearningArchive()
  }
  catch (error) {
    learningError.value = error instanceof Error ? error.message : '档案加载失败'
    archive.value = null
  }
  finally {
    learningLoading.value = false
  }
}

async function loadExams(reset = false) {
  if (!tokenStore.hasLogin) {
    records.value = []
    examTotal.value = 0
    return
  }
  if (reset) {
    examPage.value = 1
    records.value = []
  }
  examLoading.value = true
  examError.value = ''
  try {
    const result = await fetchExamRecords(examPage.value, 20)
    records.value = reset ? result.list : [...records.value, ...result.list]
    examTotal.value = result.total
    examLoaded.value = true
  }
  catch (error) {
    examError.value = error instanceof Error ? error.message : '考试记录加载失败'
  }
  finally {
    examLoading.value = false
  }
}

function switchTab(next: 'learning' | 'exam') {
  tab.value = next
  // 考试记录首次进入才拉取，避免无谓请求
  if (next === 'exam' && !examLoaded.value)
    loadExams(true)
}

function loadMoreExams() {
  if (examLoading.value || !examHasMore.value)
    return
  examPage.value += 1
  loadExams()
}

function openTask(item: ArchiveTask) {
  uni.navigateTo({ url: `/pages/task/detail?id=${item.assignmentId}` })
}

/** 考试记录点进成绩页：与「考试」页同一落点，不另建详情页。 */
function openResult(item: ExamRecord) {
  uni.navigateTo({ url: `/pages/exam/result?assignmentId=${item.assignmentId}` })
}

function goLogin() {
  uni.navigateTo({ url: '/pages/login/login' })
}

/** 只显示日期与时刻，去掉秒与毫秒，移动端更易读。 */
function formatTime(value: string | null) {
  if (!value)
    return '—'
  return value.replace('T', ' ').slice(0, 16)
}

function formatDate(value: string | null) {
  if (!value)
    return '未知日期'
  return value.slice(0, 10)
}

/** 按交卷日期分组，形成时间线；组内保持服务端倒序。 */
const examGroups = computed(() => {
  const map = new Map<string, ExamRecord[]>()
  for (const item of records.value) {
    const key = formatDate(item.submittedAt)
    const list = map.get(key) ?? []
    list.push(item)
    map.set(key, list)
  }
  return Array.from(map.entries()).map(([date, items]) => ({ date, items }))
})

onLoad((query) => {
  if (query?.tab === 'exam')
    tab.value = 'exam'
})

onShow(() => {
  loadLearning()
  if (tab.value === 'exam')
    loadExams(true)
})
</script>

<template>
  <view class="page">
    <view v-if="!hasLogin" class="state-card">
      <view class="state-title">
        尚未登录
      </view>
      <view class="state-desc">
        登录后可查看你的学习进度与全部考试成绩。
      </view>
      <wd-button type="primary" size="small" @click="goLogin">
        去登录
      </wd-button>
    </view>

    <template v-else>
      <!-- 汇总头图：学时为主指标，三项完成度并列 -->
      <view class="hero">
        <view class="hero-glow" />
        <view class="hero-top">
          <view class="hero-hours">
            <text class="hero-num">{{ archive?.summary.studyHours ?? 0 }}</text>
            <text class="hero-unit">小时</text>
          </view>
          <view class="hero-caption">
            累计有效学时
          </view>
        </view>

        <view class="hero-stats">
          <view class="hero-stat">
            <text class="stat-value">{{ archive?.summary.taskCompleted ?? 0 }}<text class="stat-total">/{{ archive?.summary.taskTotal ?? 0 }}</text></text>
            <text class="stat-label">任务完成</text>
          </view>
          <view class="hero-divider" />
          <view class="hero-stat">
            <text class="stat-value">{{ archive?.summary.courseCompleted ?? 0 }}<text class="stat-total">/{{ archive?.summary.courseTotal ?? 0 }}</text></text>
            <text class="stat-label">课程完成</text>
          </view>
          <view class="hero-divider" />
          <view class="hero-stat">
            <text class="stat-value">{{ archive?.summary.examPassed ?? 0 }}</text>
            <text class="stat-label">考试通过</text>
          </view>
        </view>

        <view class="hero-bar">
          <view class="hero-bar-track">
            <view class="hero-bar-fill" :style="{ width: `${overallPercent}%` }" />
          </view>
          <text class="hero-bar-text">章节总进度 {{ overallPercent }}%</text>
        </view>
      </view>

      <!-- 分段切换 -->
      <view class="segment">
        <view
          class="segment-item"
          :class="{ 'is-active': tab === 'learning' }"
          @click="switchTab('learning')"
        >
          学习档案
        </view>
        <view
          class="segment-item"
          :class="{ 'is-active': tab === 'exam' }"
          @click="switchTab('exam')"
        >
          考试记录
          <text v-if="examTotal" class="segment-badge">{{ examTotal }}</text>
        </view>
      </view>

      <!-- 学习档案 -->
      <view v-if="tab === 'learning'" class="panel">
        <view v-if="learningLoading" class="state-card">
          <view class="state-desc">
            加载中…
          </view>
        </view>

        <view v-else-if="learningError" class="state-card">
          <view class="state-title">
            加载失败
          </view>
          <view class="state-desc">
            {{ learningError }}
          </view>
          <wd-button size="small" @click="loadLearning">
            重试
          </wd-button>
        </view>

        <view v-else-if="!archive?.tasks.length" class="state-card">
          <view class="state-title">
            暂无培训档案
          </view>
          <view class="state-desc">
            企业下发培训任务后，学习进度会归档在这里。
          </view>
        </view>

        <view
          v-for="item in archive?.tasks ?? []"
          :key="item.assignmentId"
          class="task-card"
          @click="openTask(item)"
        >
          <view class="task-head">
            <view class="task-title">
              {{ item.title }}
            </view>
            <text class="tag" :class="statusMeta[item.status]?.cls">
              {{ statusMeta[item.status]?.label }}
            </text>
          </view>

          <view v-if="item.enterpriseName" class="task-org">
            {{ item.enterpriseName }}
          </view>

          <view class="task-metrics">
            <view class="metric">
              <text class="metric-value">{{ item.courseCompleted }}/{{ item.courseTotal }}</text>
              <text class="metric-label">课程</text>
            </view>
            <view class="metric">
              <text class="metric-value">{{ item.sectionCompleted }}/{{ item.sectionTotal }}</text>
              <text class="metric-label">章节</text>
            </view>
            <view class="metric">
              <text class="metric-value">{{ item.studyHours }}</text>
              <text class="metric-label">学时</text>
            </view>
          </view>

          <view class="task-bar">
            <view class="task-bar-track">
              <view
                class="task-bar-fill"
                :class="statusMeta[item.status]?.cls"
                :style="{ width: `${ratioPercent(item.sectionCompleted, item.sectionTotal)}%` }"
              />
            </view>
          </view>

          <view class="task-foot">
            <text v-if="item.status === 'COMPLETED'" class="foot-done">
              完成于 {{ formatTime(item.completedAt) }}
            </text>
            <text v-else-if="item.learnDeadlineAt" class="foot-deadline">
              截止 {{ formatTime(item.learnDeadlineAt) }}
            </text>
            <text v-else class="foot-deadline">
              未设置截止时间
            </text>

            <view v-if="item.bestExam" class="exam-chip" :class="{ 'is-pass': item.bestExam.passed }">
              <text class="chip-score">{{ item.bestExam.scoreSum ?? '—' }}/{{ item.bestExam.totalScore ?? '—' }}</text>
              <text class="chip-state">{{ item.bestExam.passed ? '已通过' : '未通过' }}</text>
            </view>
            <text v-else class="exam-chip is-none">未考试</text>
          </view>
        </view>
      </view>

      <!-- 考试记录 -->
      <view v-else class="panel">
        <view v-if="examLoading && !records.length" class="state-card">
          <view class="state-desc">
            加载中…
          </view>
        </view>

        <view v-else-if="examError && !records.length" class="state-card">
          <view class="state-title">
            加载失败
          </view>
          <view class="state-desc">
            {{ examError }}
          </view>
          <wd-button size="small" @click="loadExams(true)">
            重试
          </wd-button>
        </view>

        <view v-else-if="!records.length" class="state-card">
          <view class="state-title">
            暂无考试记录
          </view>
          <view class="state-desc">
            交卷后每次成绩都会保留在这里，不会被覆盖。
          </view>
        </view>

        <view v-for="group in examGroups" :key="group.date" class="exam-group">
          <view class="group-date">
            <text class="date-dot" />
            <text class="date-text">{{ group.date }}</text>
          </view>

          <view
            v-for="item in group.items"
            :key="item.attemptId"
            class="exam-card"
            @click="openResult(item)"
          >
            <view class="exam-line">
              <view class="exam-main">
                <view class="exam-paper">
                  {{ item.paperTitle }}
                </view>
                <view class="exam-task">
                  {{ item.taskTitle }}
                </view>
              </view>
              <view class="exam-score" :class="{ 'is-pass': item.passed }">
                <text class="score-num">{{ item.scoreSum ?? '—' }}</text>
                <text class="score-total">/{{ item.totalScore ?? '—' }}</text>
                <text class="score-percent">{{ recordPercent(item) }}%</text>
              </view>
            </view>

            <view class="exam-tags">
              <text class="mini-tag" :class="item.passed ? 'is-pass' : 'is-fail'">
                {{ item.passed ? '通过' : '未通过' }}
              </text>
              <text v-if="item.best" class="mini-tag is-best">
                最佳
              </text>
              <text v-if="item.autoSubmitted" class="mini-tag is-auto">
                自动交卷
              </text>
              <text v-if="item.enterpriseName" class="mini-tag is-org">
                {{ item.enterpriseName }}
              </text>
              <text class="exam-time">{{ formatTime(item.submittedAt) }}</text>
            </view>
          </view>
        </view>

        <view v-if="records.length" class="load-more">
          <wd-button
            v-if="examHasMore"
            size="small"
            plain
            :loading="examLoading"
            @click="loadMoreExams"
          >
            加载更多
          </wd-button>
          <text v-else class="load-end">
            已显示全部 {{ examTotal }} 条记录
          </text>
        </view>
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

/* ---------- 汇总头图 ---------- */
.hero {
  position: relative;
  padding: 36rpx 32rpx 32rpx;
  border-radius: 24rpx;
  background: linear-gradient(145deg, #0e5a8a 0%, #12405f 62%, #0b2c42 100%);
  overflow: hidden;
}

.hero-glow {
  position: absolute;
  top: -120rpx;
  right: -80rpx;
  width: 320rpx;
  height: 320rpx;
  border-radius: 50%;
  background: rgba(94, 197, 255, 0.18);
}

.hero-top {
  position: relative;
}

.hero-hours {
  display: flex;
  align-items: baseline;
  gap: 10rpx;
}

.hero-num {
  font-size: 76rpx;
  font-weight: 700;
  line-height: 1;
  color: #ffffff;
  letter-spacing: -1rpx;
}

.hero-unit {
  font-size: 26rpx;
  color: rgba(255, 255, 255, 0.72);
}

.hero-caption {
  margin-top: 10rpx;
  font-size: 24rpx;
  color: rgba(255, 255, 255, 0.66);
}

.hero-stats {
  position: relative;
  display: flex;
  align-items: center;
  margin-top: 32rpx;
  padding: 22rpx 0;
  border-radius: 16rpx;
  background: rgba(255, 255, 255, 0.1);
}

.hero-stat {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8rpx;
}

.hero-divider {
  width: 1rpx;
  height: 48rpx;
  background: rgba(255, 255, 255, 0.18);
}

.stat-value {
  font-size: 34rpx;
  font-weight: 600;
  color: #ffffff;
}

.stat-total {
  font-size: 24rpx;
  font-weight: 400;
  color: rgba(255, 255, 255, 0.6);
}

.stat-label {
  font-size: 22rpx;
  color: rgba(255, 255, 255, 0.66);
}

.hero-bar {
  position: relative;
  margin-top: 26rpx;
}

.hero-bar-track {
  height: 10rpx;
  border-radius: 999rpx;
  background: rgba(255, 255, 255, 0.16);
  overflow: hidden;
}

.hero-bar-fill {
  height: 100%;
  border-radius: 999rpx;
  background: linear-gradient(90deg, #5ec5ff, #9ae6b4);
  transition: width 0.4s ease;
}

.hero-bar-text {
  display: block;
  margin-top: 12rpx;
  font-size: 22rpx;
  color: rgba(255, 255, 255, 0.7);
}

/* ---------- 分段切换 ---------- */
.segment {
  display: flex;
  gap: 8rpx;
  margin-top: 24rpx;
  padding: 8rpx;
  border-radius: 16rpx;
  background-color: #e6ebf1;
}

.segment-item {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8rpx;
  padding: 20rpx 0;
  border-radius: 12rpx;
  font-size: 27rpx;
  color: #5b6b7c;
  transition: all 0.2s ease;

  &.is-active {
    background-color: #ffffff;
    color: #0e5a8a;
    font-weight: 600;
    box-shadow: 0 2rpx 8rpx rgba(14, 90, 138, 0.1);
  }
}

.segment-badge {
  padding: 2rpx 12rpx;
  border-radius: 999rpx;
  background-color: #0e5a8a;
  color: #ffffff;
  font-size: 20rpx;
  font-weight: 500;
}

.panel {
  margin-top: 24rpx;
}

/* ---------- 状态卡 ---------- */
.state-card {
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

/* ---------- 任务卡 ---------- */
.task-card {
  margin-bottom: 20rpx;
  padding: 28rpx;
  border: 1rpx solid #e2e8ef;
  border-radius: 20rpx;
  background-color: #ffffff;
}

.task-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16rpx;
}

.task-title {
  flex: 1;
  font-size: 29rpx;
  font-weight: 600;
  line-height: 1.4;
  color: #16202b;
}

.tag {
  flex-shrink: 0;
  padding: 4rpx 14rpx;
  border-radius: 8rpx;
  font-size: 21rpx;

  &.is-idle {
    background-color: #eef1f5;
    color: #7b8794;
  }

  &.is-doing {
    background-color: #fff4e0;
    color: #b26a00;
  }

  &.is-done {
    background-color: #e4f6ea;
    color: #1f7a45;
  }
}

.task-org {
  margin-top: 10rpx;
  font-size: 22rpx;
  color: #8a97a5;
}

.task-metrics {
  display: flex;
  margin-top: 22rpx;
}

.metric {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}

.metric-value {
  font-size: 30rpx;
  font-weight: 600;
  color: #0e5a8a;
}

.metric-label {
  font-size: 21rpx;
  color: #8a97a5;
}

.task-bar {
  margin-top: 20rpx;
}

.task-bar-track {
  height: 8rpx;
  border-radius: 999rpx;
  background-color: #eef1f5;
  overflow: hidden;
}

.task-bar-fill {
  height: 100%;
  border-radius: 999rpx;
  background-color: #b9c4d0;
  transition: width 0.4s ease;

  &.is-doing {
    background-color: #f0a63a;
  }

  &.is-done {
    background-color: #34a86b;
  }
}

.task-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  margin-top: 20rpx;
  padding-top: 20rpx;
  border-top: 1rpx solid #f0f3f7;
}

.foot-done {
  font-size: 22rpx;
  color: #1f7a45;
}

.foot-deadline {
  font-size: 22rpx;
  color: #8a97a5;
}

.exam-chip {
  display: flex;
  align-items: center;
  gap: 8rpx;
  padding: 6rpx 16rpx;
  border-radius: 999rpx;
  background-color: #fdecec;

  &.is-pass {
    background-color: #e4f6ea;
  }

  &.is-none {
    background-color: #eef1f5;
    color: #8a97a5;
    font-size: 21rpx;
  }
}

.chip-score {
  font-size: 23rpx;
  font-weight: 600;
  color: #16202b;
}

.chip-state {
  font-size: 21rpx;
  color: #5b6b7c;
}

/* ---------- 考试时间线 ---------- */
.exam-group {
  margin-bottom: 12rpx;
}

.group-date {
  display: flex;
  align-items: center;
  gap: 12rpx;
  padding: 8rpx 0 16rpx;
}

.date-dot {
  width: 12rpx;
  height: 12rpx;
  border-radius: 50%;
  background-color: #0e5a8a;
}

.date-text {
  font-size: 23rpx;
  font-weight: 600;
  color: #5b6b7c;
}

.exam-card {
  margin-bottom: 16rpx;
  margin-left: 24rpx;
  padding: 24rpx;
  border: 1rpx solid #e2e8ef;
  border-left: 4rpx solid #d6dee7;
  border-radius: 16rpx;
  background-color: #ffffff;
}

.exam-line {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20rpx;
}

.exam-main {
  flex: 1;
  min-width: 0;
}

.exam-paper {
  font-size: 28rpx;
  font-weight: 600;
  line-height: 1.4;
  color: #16202b;
}

.exam-task {
  margin-top: 8rpx;
  font-size: 22rpx;
  color: #8a97a5;
}

.exam-score {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-end;

  &.is-pass .score-num {
    color: #1f7a45;
  }
}

.score-num {
  font-size: 40rpx;
  font-weight: 700;
  line-height: 1;
  color: #c0392b;
}

.score-total {
  margin-top: 4rpx;
  font-size: 21rpx;
  color: #8a97a5;
}

.score-percent {
  margin-top: 6rpx;
  font-size: 21rpx;
  color: #5b6b7c;
}

.exam-tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10rpx;
  margin-top: 18rpx;
  padding-top: 16rpx;
  border-top: 1rpx solid #f0f3f7;
}

.mini-tag {
  padding: 3rpx 12rpx;
  border-radius: 6rpx;
  font-size: 20rpx;

  &.is-pass {
    background-color: #e4f6ea;
    color: #1f7a45;
  }

  &.is-fail {
    background-color: #fdecec;
    color: #c0392b;
  }

  &.is-best {
    background-color: #fff4e0;
    color: #b26a00;
  }

  &.is-auto {
    background-color: #eef1f5;
    color: #7b8794;
  }

  &.is-org {
    background-color: #e8f1f7;
    color: #0e5a8a;
  }
}

.exam-time {
  margin-left: auto;
  font-size: 21rpx;
  color: #a4b0bd;
}

.load-more {
  padding: 16rpx 0 8rpx;
  text-align: center;
}

.load-end {
  font-size: 22rpx;
  color: #a4b0bd;
}
</style>
