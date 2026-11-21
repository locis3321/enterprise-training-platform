<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { fetchMyTasks } from '@/api/training'
import type { MyAssignment } from '@/api/training'
import { useTokenStore } from '@/store/token'

definePage({
  style: {
    navigationBarTitleText: '我的培训任务',
  },
})

const tokenStore = useTokenStore()
const tasks = ref<MyAssignment[]>([])
const loading = ref(false)
const errorMessage = ref('')

const hasLogin = computed(() => tokenStore.hasLogin)

const statusMeta: Record<string, { label: string, cls: string }> = {
  NOT_STARTED: { label: '未开始', cls: 'tag-idle' },
  IN_PROGRESS: { label: '进行中', cls: 'tag-doing' },
  COMPLETED: { label: '已完成', cls: 'tag-done' },
}

async function load() {
  if (!tokenStore.hasLogin) {
    tasks.value = []
    return
  }
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await fetchMyTasks()
    tasks.value = result.list
  }
  catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '任务加载失败'
    tasks.value = []
  }
  finally {
    loading.value = false
  }
}

function goLogin() {
  uni.navigateTo({ url: '/pages/login/login' })
}

function openTask(item: MyAssignment) {
  uni.navigateTo({ url: `/pages/task/detail?id=${item.assignmentId}` })
}

/** 剩余天数提示：负数表示已过期，由服务端时间判定，前端只做展示。 */
function deadlineText(item: MyAssignment) {
  if (!item.learnDeadlineAt) {
    return ''
  }
  if (item.status === 'COMPLETED') {
    return '已完成'
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

onShow(load)
</script>

<template>
  <view class="page">
    <view v-if="!hasLogin" class="hint-card">
      <view class="hint-title">
        尚未登录
      </view>
      <view class="hint-desc">
        请先完成微信登录与手机号绑定，才能查看分配给您的培训任务。
      </view>
      <wd-button type="primary" size="small" @click="goLogin">
        去登录
      </wd-button>
    </view>

    <template v-else>
      <view v-if="loading" class="hint-card">
        <view class="hint-desc">
          加载中…
        </view>
      </view>

      <view v-else-if="errorMessage" class="hint-card">
        <view class="hint-title">
          加载失败
        </view>
        <view class="hint-desc">
          {{ errorMessage }}
        </view>
        <wd-button size="small" @click="load">
          重试
        </wd-button>
      </view>

      <view v-else-if="!tasks.length" class="hint-card">
        <view class="hint-title">
          暂无培训任务
        </view>
        <view class="hint-desc">
          企业管理员下发任务后，会出现在这里。
        </view>
      </view>

      <view
        v-for="item in tasks"
        :key="item.assignmentId"
        class="task-card"
        @click="openTask(item)"
      >
        <view class="task-head">
          <view class="task-title">
            {{ item.title || '培训任务' }}
          </view>
          <text class="tag" :class="statusMeta[item.status]?.cls">{{ statusMeta[item.status]?.label }}</text>
        </view>
        <view class="task-meta">
          <text>{{ deadlineText(item) }}</text>
          <text v-if="item.courseTotal">课程 {{ item.courseDone }}/{{ item.courseTotal }}</text>
        </view>
        <wd-progress
          :percentage="progressPercent(item)"
          :show-text="false"
          color="#2f6fed"
          custom-class="task-progress"
        />
        <view class="task-foot">
          章节进度 {{ item.sectionDone }}/{{ item.sectionTotal }}
        </view>
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

.task-card {
  margin-bottom: 20rpx;
  padding: 28rpx;
  border-radius: 16rpx;
  background-color: #ffffff;
}

.task-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.task-title {
  flex: 1;
  margin-right: 16rpx;
  color: #1f2733;
  font-size: 30rpx;
  font-weight: 600;
}

.tag {
  padding: 4rpx 14rpx;
  border-radius: 999rpx;
  font-size: 22rpx;
}

.tag-idle {
  background-color: #eef1f6;
  color: #6b7885;
}

.tag-doing {
  background-color: #fff3e0;
  color: #b26a00;
}

.tag-done {
  background-color: #e8f5ec;
  color: #1f7a3d;
}

.task-meta {
  display: flex;
  gap: 24rpx;
  margin: 14rpx 0 18rpx;
  color: #6b7885;
  font-size: 24rpx;
}

.task-progress {
  margin-bottom: 12rpx;
}

.task-foot {
  color: #8a97a5;
  font-size: 22rpx;
}
</style>
