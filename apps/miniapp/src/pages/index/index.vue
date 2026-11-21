<script lang="ts" setup>
import { onShow } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { fetchProfile } from '@/api/auth'
import type { StudentProfile } from '@/api/auth'
import { fetchLearningOverview } from '@/api/learning'
import type { LearningOverview } from '@/api/learning'
import { fetchExamOverview } from '@/api/exam'
import type { ExamOverview } from '@/api/exam'
import { fetchMyTasks } from '@/api/training'
import type { MyAssignment } from '@/api/training'
import { useTokenStore } from '@/store/token'

defineOptions({
  name: 'Home',
})

definePage({
  type: 'home',
  style: {
    navigationBarTitleText: '企业内训',
  },
})

const tokenStore = useTokenStore()
const profile = ref<StudentProfile | null>(null)
const overview = ref<LearningOverview | null>(null)
const examOverview = ref<ExamOverview | null>(null)
const tasks = ref<MyAssignment[]>([])
const loading = ref(false)

/**
 * 首页统计：全部来自服务端聚合接口，无数据时为 0（不是「—」）。
 * 「待考考试」由考试域独立判定（任务开启 + 学完 + 包未下线 + 有已发布试卷 + 尚未通过），
 * 学习域的 overview 不再冒充考试数据。
 */
const stats = computed(() => [
  { label: '待学课程', value: overview.value?.pendingCourses ?? 0 },
  { label: '待考考试', value: examOverview.value?.pendingExams ?? 0 },
  { label: '已完成', value: overview.value?.completedCourses ?? 0 },
])

/**
 * 「我的学习任务」：只列未完成任务，直接在首页下方展开。
 *
 * 排序：有截止时间的按截止时间升序（最紧急在前）；无截止时间的固定排末尾，
 * 彼此按 assignmentId 降序。已过期但未完成仍属未完成，排在无截止时间之前。
 */
const pendingTasks = computed(() => {
  return tasks.value
    .filter(item => item.status !== 'COMPLETED')
    .slice()
    .sort((a, b) => {
      const da = a.learnDeadlineAt ?? ''
      const db = b.learnDeadlineAt ?? ''
      if (da && db) {
        return da === db ? b.assignmentId - a.assignmentId : da.localeCompare(db)
      }
      if (da) {
        return -1
      }
      if (db) {
        return 1
      }
      return b.assignmentId - a.assignmentId
    })
})

function goLogin() {
  uni.navigateTo({ url: '/pages/login/login' })
}

/** 保留独立列表页路由（既有链路），首页不再作为唯一入口。 */
function goTasks() {
  if (!tokenStore.hasLogin) {
    goLogin()
    return
  }
  uni.navigateTo({ url: '/pages/task/list' })
}

function openTask(item: MyAssignment) {
  uni.navigateTo({ url: `/pages/task/detail?id=${item.assignmentId}` })
}

function deadlineText(item: MyAssignment) {
  if (!item.learnDeadlineAt) {
    return '无截止时间'
  }
  const days = item.daysLeft ?? 0
  if (days < 0) {
    return '已过学习截止时间'
  }
  if (days === 0) {
    return '今天截止'
  }
  return `剩余 ${days} 天`
}

function progressPercent(item: MyAssignment) {
  if (!item.sectionTotal) {
    return 0
  }
  return Math.round((item.sectionDone / item.sectionTotal) * 100)
}

/**
 * 每次显示都重新读取档案与学习数据：绑定/改密/学习后返回首页必须立刻反映最新状态，
 * 否则会出现「绑定成功但首页仍提示登录」的割裂。
 */
async function loadProfile() {
  if (!tokenStore.hasLogin) {
    profile.value = null
    return
  }
  try {
    profile.value = await fetchProfile()
    // 强制改密：服务端会拦截其它接口，直接引导去登录页完成改密
    if (profile.value?.mustChangePassword) {
      uni.showModal({
        title: '请先修改初始密码',
        content: '首次登录需修改初始密码后才能继续使用。',
        showCancel: false,
        success: () => uni.navigateTo({ url: '/pages/login/login' }),
      })
    }
  }
  catch {
    profile.value = null
  }
}

async function loadLearning() {
  if (!tokenStore.hasLogin) {
    overview.value = null
    examOverview.value = null
    tasks.value = []
    return
  }
  loading.value = true
  try {
    const [o, t, e] = await Promise.all([
      fetchLearningOverview(),
      fetchMyTasks(1, 50),
      // 考试总览失败不应拖垮首页其它数据：单独兜底为 null
      fetchExamOverview().catch(() => null),
    ])
    overview.value = o
    tasks.value = t.list
    examOverview.value = e
  }
  catch {
    overview.value = null
    tasks.value = []
  }
  finally {
    loading.value = false
  }
}

onShow(async () => {
  await Promise.all([loadProfile(), loadLearning()])
})
</script>

<template>
  <view class="page">
    <view class="hero">
      <view class="hero-title">
        {{ profile ? `${profile.name}，欢迎回来` : '企业内训' }}
      </view>
      <view class="hero-desc">
        <template v-if="profile">
          {{ profile.enterpriseName }} · {{ profile.phoneMasked }}
        </template>
        <template v-else>
          完成所属企业下发的培训任务与考试，学习进度与成绩以服务端记录为准。
        </template>
      </view>
      <wd-button v-if="!profile" size="small" plain custom-class="hero-button" @click="goLogin">
        微信登录 / 绑定手机号
      </wd-button>
    </view>

    <view class="stats">
      <view v-for="item in stats" :key="item.label" class="stat">
        <text class="stat-value">{{ item.value }}</text>
        <text class="stat-label">{{ item.label }}</text>
      </view>
    </view>

    <view class="section-head">
      <text class="section-title">我的学习任务</text>
      <text v-if="pendingTasks.length" class="section-link" @click="goTasks">
        全部任务 ›
      </text>
    </view>

    <view v-if="!tokenStore.hasLogin" class="hint-card">
      <view class="hint-title">
        尚未登录
      </view>
      <view class="hint-desc">
        登录后查看企业下发的培训课程与学习进度。
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

    <view v-else-if="!pendingTasks.length" class="done-card">
      <text class="done-icon">✓</text>
      <text class="done-text">已完成所有课程</text>
    </view>

    <template v-else>
      <view
        v-for="item in pendingTasks"
        :key="item.assignmentId"
        class="task-card"
        @click="openTask(item)"
      >
        <view class="task-head">
          <text class="task-title">{{ item.title || '培训任务' }}</text>
          <text class="task-deadline">{{ deadlineText(item) }}</text>
        </view>
        <wd-progress :percentage="progressPercent(item)" :show-text="false" color="#2f6fed" />
        <view class="task-foot">
          课程 {{ item.courseDone }}/{{ item.courseTotal }} · 章节 {{ item.sectionDone }}/{{ item.sectionTotal }}
        </view>
      </view>
    </template>
  </view>
</template>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 24rpx;
  background-color: #f4f6f9;
}

.hero {
  padding: 32rpx;
  border: 1rpx solid #e2e8ef;
  border-radius: 16rpx;
  background-color: #ffffff;
}

.hero-title {
  font-size: 36rpx;
  font-weight: 600;
  color: #16202b;
}

.hero-desc {
  margin-top: 12rpx;
  font-size: 26rpx;
  line-height: 1.6;
  color: #5b6b7c;
}

:deep(.hero-button) {
  margin-top: 24rpx;
}

.stats {
  display: flex;
  margin-top: 24rpx;
  padding: 24rpx 0;
  border: 1rpx solid #e2e8ef;
  border-radius: 16rpx;
  background-color: #ffffff;
}

.stat {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  gap: 8rpx;
}

.stat-value {
  font-size: 36rpx;
  font-weight: 600;
  color: #0e5a8a;
}

.stat-label {
  font-size: 24rpx;
  color: #5b6b7c;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 32rpx 0 16rpx;
}

.section-title {
  font-size: 30rpx;
  font-weight: 600;
  color: #16202b;
}

.section-link {
  font-size: 23rpx;
  color: #2f6fed;
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

.done-card {
  display: flex;
  flex-direction: column;
  gap: 12rpx;
  align-items: center;
  padding: 56rpx 32rpx;
  border-radius: 16rpx;
  background-color: #ffffff;
}

.done-icon {
  color: #1f7a3d;
  font-size: 48rpx;
}

.done-text {
  color: #1f7a3d;
  font-size: 28rpx;
  font-weight: 600;
}

.task-card {
  margin-bottom: 16rpx;
  padding: 26rpx 24rpx;
  border-radius: 16rpx;
  background-color: #ffffff;
}

.task-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16rpx;
}

.task-title {
  flex: 1;
  margin-right: 16rpx;
  color: #1f2733;
  font-size: 28rpx;
  font-weight: 600;
}

.task-deadline {
  color: #b26a00;
  font-size: 22rpx;
}

.task-foot {
  margin-top: 12rpx;
  color: #8a97a5;
  font-size: 22rpx;
}
</style>
