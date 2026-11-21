<script lang="ts" setup>
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { fetchCourses } from '@/api/course'
import type { MiniappCourse } from '@/api/course'
import { useTokenStore } from '@/store/token'

definePage({
  style: {
    navigationBarTitleText: '课程',
  },
})

const tokenStore = useTokenStore()
const courses = ref<MiniappCourse[]>([])
const loading = ref(false)
const errorMessage = ref('')
const needLogin = ref(false)

async function load() {
  if (!tokenStore.hasLogin) {
    needLogin.value = true
    courses.value = []
    return
  }
  needLogin.value = false
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await fetchCourses(1, 50)
    courses.value = result.list
  }
  catch (error) {
    courses.value = []
    errorMessage.value = error instanceof Error ? error.message : '课程加载失败'
  }
  finally {
    loading.value = false
  }
}

function openDetail(course: MiniappCourse) {
  uni.navigateTo({ url: `/pages/course/detail?id=${course.id}` })
}

function goLogin() {
  uni.navigateTo({ url: '/pages/login/login' })
}

onShow(load)
</script>

<template>
  <view class="page">
    <view class="header">
      <view class="title">
        学习课程
      </view>
      <view class="desc">
        课程与资料来自已发布的版本快照；学习进度由服务端按有效观看记录计算。
      </view>
    </view>

    <view v-if="needLogin" class="state-box">
      <text class="state-title">请先登录</text>
      <text class="state-desc">登录并绑定员工档案后可查看企业下发的培训课程。</text>
      <wd-button size="small" type="primary" @click="goLogin">
        去登录
      </wd-button>
    </view>

    <view v-else-if="loading" class="state-box">
      <text class="state-desc">加载中…</text>
    </view>

    <view v-else-if="errorMessage" class="state-box">
      <text class="state-title">加载失败</text>
      <text class="state-desc">{{ errorMessage }}</text>
      <wd-button size="small" @click="load">
        重试
      </wd-button>
    </view>

    <view v-else-if="courses.length === 0" class="state-box">
      <text class="state-title">暂无可学习课程</text>
      <text class="state-desc">所属企业发布课程后，会出现在这里。</text>
    </view>

    <view v-else class="list">
      <view
        v-for="course in courses"
        :key="course.id"
        class="course-card"
        @click="openDetail(course)"
      >
        <view class="course-main">
          <view class="course-title">
            {{ course.title }}
          </view>
          <view class="course-desc">
            {{ course.description || '暂无简介' }}
          </view>
        </view>
        <wd-icon name="arrow-right" size="16px" custom-class="course-arrow" />
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 24rpx;
  background-color: #f4f6f9;
}

.header {
  padding: 32rpx;
  border-radius: 16rpx;
  background-color: #ffffff;
}

.title {
  font-size: 34rpx;
  font-weight: 600;
  color: #16202b;
}

.desc {
  margin-top: 12rpx;
  font-size: 24rpx;
  line-height: 1.6;
  color: #5b6b7c;
}

.state-box {
  display: flex;
  flex-direction: column;
  gap: 12rpx;
  align-items: center;
  margin-top: 24rpx;
  padding: 48rpx 32rpx;
  border-radius: 16rpx;
  background-color: #ffffff;
}

.state-title {
  font-size: 28rpx;
  font-weight: 600;
  color: #16202b;
}

.state-desc {
  font-size: 24rpx;
  line-height: 1.6;
  color: #7b8a99;
  text-align: center;
}

.list {
  margin-top: 24rpx;
}

.course-card {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin-bottom: 16rpx;
  padding: 28rpx 24rpx;
  border-radius: 16rpx;
  background-color: #ffffff;
}

.course-main {
  flex: 1;
  min-width: 0;
}

.course-title {
  font-size: 28rpx;
  font-weight: 600;
  color: #16202b;
}

.course-desc {
  margin-top: 8rpx;
  font-size: 22rpx;
  line-height: 1.5;
  color: #7b8a99;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

:deep(.course-arrow) {
  color: #b7c2cc;
}
</style>
