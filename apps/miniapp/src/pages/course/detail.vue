<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { fetchCourseDetail, fetchResourceUrl } from '@/api/course'
import type { MiniappCourseDetail } from '@/api/course'
import { openResource } from '@/api/learning'
import { fetchAssignmentResourceUrl, fetchMyTaskDetail, fetchMyTasks } from '@/api/training'
import type { MyTaskDetail } from '@/api/training'
import SectionLearnButton from '@/components/SectionLearnButton/SectionLearnButton.vue'

definePage({
  style: {
    navigationBarTitleText: '课程详情',
  },
})

const courseId = ref(0)
const detail = ref<MiniappCourseDetail | null>(null)
const openingResourceId = ref(0)
const loading = ref(false)
const errorMessage = ref('')
const expanded = ref<Record<number, boolean>>({})

/**
 * 该课程在当前学员任务中的上下文。
 *
 * <p>课程库本身是「非任务上下文」（读最新已发布版本）；学习必须绑定 assignment：
 * 取地址走任务上下文路由，版本由 assignment 推导，否则旧任务会读到改版后的新内容。
 * 这里按 assignmentId 升序取第一条包含该课程的分配，保证稳定。
 */
const taskContext = ref<{
  assignmentId: number
  course: MyTaskDetail['courses'][number]
  learnable: boolean
} | null>(null)

/** 课程进度：来自任务详情的服务端数据，不在前端推算。 */
const courseProgress = computed(() => {
  const ctx = taskContext.value
  if (!ctx) {
    return null
  }
  const sections = ctx.course.sections
  if (!sections.length) {
    return null
  }
  const doneCount = sections.filter(s => isSectionDone(s)).length
  return {
    doneCount,
    total: sections.length,
    percent: Math.round((doneCount / sections.length) * 100),
  }
})

/** 完成判定由服务端给出，课程库视图不自行推算。 */
function isSectionDone(section: MyTaskDetail['courses'][number]['sections'][number]) {
  return section.done
}

/** 章节在任务上下文中的进度（匹配不到时返回 null）。 */
function sectionProgress(sectionId: number) {
  const ctx = taskContext.value
  if (!ctx) {
    return null
  }
  return ctx.course.sections.find(s => s.sectionId === sectionId) ?? null
}

onLoad(async (query) => {
  courseId.value = Number(query?.id ?? 0)
  await load()
})

async function load() {
  if (!courseId.value) {
    errorMessage.value = '缺少课程参数'
    return
  }
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await fetchCourseDetail(courseId.value)
    detail.value = result
    uni.setNavigationBarTitle({ title: result.title })
    await loadTaskContext()
  }
  catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '课程加载失败'
  }
  finally {
    loading.value = false
  }
}

/** 在「我的培训任务」里找包含本课程的分配，用于显示进度与学习入口。 */
async function loadTaskContext() {
  taskContext.value = null
  try {
    const tasks = await fetchMyTasks(1, 50)
    const ordered = tasks.list.slice().sort((a, b) => a.assignmentId - b.assignmentId)
    for (const item of ordered) {
      const taskDetail = await fetchMyTaskDetail(item.assignmentId)
      const matched = taskDetail.courses.find(c => c.courseId === courseId.value)
      if (matched) {
        taskContext.value = {
          assignmentId: item.assignmentId,
          course: matched,
          learnable: taskDetail.status !== 'COMPLETED',
        }
        return
      }
    }
  }
  catch {
    taskContext.value = null
  }
}

function toggle(sectionId: number) {
  expanded.value[sectionId] = !expanded.value[sectionId]
}

/**
 * 章节「学习」：直接进入该章节第一个资源。
 *
 * <p>有任务上下文时走 assignment 路由（计入进度）；无上下文时明确提示，
 * 不退化为「最新版本地址」——那会让用户以为学过但服务端无记录。
 */
async function learnSection(section: MiniappCourseDetail['sections'][number]) {
  const ctx = taskContext.value
  if (!ctx) {
    uni.showModal({
      title: '暂不可学习',
      content: '该课程当前没有进行中的培训任务。课程库仅供浏览，学习需通过企业下发的任务进入。',
      showCancel: false,
    })
    return
  }
  if (!ctx.learnable) {
    uni.showToast({ title: '任务已完成，无需继续学习', icon: 'none' })
    return
  }
  const taskSection = ctx.course.sections.find(s => s.sectionId === section.id)
  const resource = taskSection?.resources?.[0]
  if (!resource || ctx.course.courseId == null) {
    uni.showToast({ title: '该章节暂无可用资源', icon: 'none' })
    return
  }
  await openTaskResource(ctx.assignmentId, ctx.course.courseId, resource, section)
}

/** 任务上下文打开资源：视频进学习页（上报心跳），非视频打开后登记 open。 */
async function openTaskResource(
  assignmentId: number,
  taskCourseId: number,
  resource: MyTaskDetail['courses'][number]['sections'][number]['resources'][number],
  section: MiniappCourseDetail['sections'][number],
) {
  openingResourceId.value = resource.resourceId
  try {
    const url = await fetchAssignmentResourceUrl(assignmentId, taskCourseId, resource.resourceId)
    if (resource.kind === 'VIDEO') {
      uni.navigateTo({
        url: `/pages/task/learn?assignmentId=${assignmentId}&courseId=${taskCourseId}`
          + `&sectionId=${section.id}&resourceId=${resource.resourceId}`
          + `&title=${encodeURIComponent(resource.originalName)}`,
      })
      return
    }
    await downloadAndOpen(url, resource.originalName)
    await openResource(assignmentId, resource.resourceId)
    await loadTaskContext()
  }
  catch (error) {
    const message = error instanceof Error ? error.message : '获取地址失败'
    uni.showModal({
      title: '无法打开资料',
      content: message.includes('过期') ? '链接已过期，请重试' : message,
      showCancel: false,
    })
  }
  finally {
    openingResourceId.value = 0
  }
}

/** 仅在任务上下文缺失时使用：课程库浏览打开资料（不计入学习进度）。 */
async function openLibraryResource(resourceId: number, kind: string, originalName: string) {
  openingResourceId.value = resourceId
  try {
    const url = await fetchResourceUrl(courseId.value, resourceId)
    if (kind === 'VIDEO') {
      uni.navigateTo({
        url: `/pages/course/player?courseId=${courseId.value}&url=${encodeURIComponent(url)}&title=${encodeURIComponent(originalName)}`,
      })
      return
    }
    await downloadAndOpen(url, originalName)
  }
  catch (error) {
    const message = error instanceof Error ? error.message : '获取地址失败'
    uni.showModal({
      title: '无法打开资料',
      content: message.includes('过期') ? '链接已过期，请重试' : message,
      showCancel: false,
    })
  }
  finally {
    openingResourceId.value = 0
  }
}

async function downloadAndOpen(url: string, name: string) {
  return new Promise<void>((resolve, reject) => {
    uni.showLoading({ title: '正在打开' })
    uni.downloadFile({
      url,
      success: (res) => {
        if (res.statusCode !== 200) {
          uni.hideLoading()
          reject(new Error(`下载失败（HTTP ${res.statusCode}）`))
          return
        }
        uni.openDocument({
          filePath: res.tempFilePath,
          fileType: name.split('.').pop(),
          showMenu: true,
          success: () => {
            uni.hideLoading()
            resolve()
          },
          fail: (err) => {
            uni.hideLoading()
            reject(new Error(err.errMsg || '文档打开失败'))
          },
        })
      },
      fail: (err) => {
        uni.hideLoading()
        reject(new Error(err.errMsg || '下载失败'))
      },
    })
  })
}

function formatDuration(seconds: number | null) {
  if (!seconds)
    return ''
  const minutes = Math.round(seconds / 60)
  return minutes >= 60 ? `${Math.floor(minutes / 60)} 时 ${minutes % 60} 分` : `${minutes} 分钟`
}
</script>

<template>
  <view class="page">
    <view v-if="loading" class="state-box">
      <text class="state-desc">加载中…</text>
    </view>

    <view v-else-if="errorMessage" class="state-box">
      <text class="state-title">加载失败</text>
      <text class="state-desc">{{ errorMessage }}</text>
      <wd-button size="small" @click="load">
        重试
      </wd-button>
    </view>

    <template v-else-if="detail">
      <view class="header">
        <view class="title">
          {{ detail.title }}
        </view>
        <view class="meta">
          v{{ detail.versionNo }} · {{ detail.sectionCount }} 个章节
        </view>
        <view v-if="detail.description" class="desc">
          {{ detail.description }}
        </view>
        <view v-if="courseProgress" class="progress-box">
          <view class="progress-head">
            <text class="progress-label">学习进度</text>
            <text class="progress-value">{{ courseProgress.doneCount }}/{{ courseProgress.total }} 章节 · {{ courseProgress.percent }}%</text>
          </view>
          <wd-progress :percentage="courseProgress.percent" :show-text="false" color="#2f6fed" />
        </view>
        <view v-else class="progress-note">
          该课程暂无进行中的培训任务，进度不可用。
        </view>
      </view>

      <view class="sections">
        <view
          v-for="section in detail.sections"
          :key="section.id"
          class="section-card"
        >
          <view class="section-head" @click="toggle(section.id)">
            <text class="section-index">{{ section.sortNo }}</text>
            <text class="section-title">{{ section.title }}</text>
            <view class="section-right">
              <text v-if="sectionProgress(section.id)?.done" class="section-progress">
                已学完
              </text>
              <text v-else-if="section.durationSeconds" class="section-meta">
                {{ formatDuration(section.durationSeconds) }}
              </text>
              <SectionLearnButton
                v-if="taskContext && sectionProgress(section.id)"
                :assignment-id="taskContext.assignmentId"
                :course-id="taskContext.course.courseId"
                :section="sectionProgress(section.id)!"
                :disabled="!taskContext.learnable"
                @learn="() => learnSection(section)"
              />
              <text class="chevron">{{ expanded[section.id] ? '收起' : '展开' }}</text>
            </view>
          </view>
          <view v-if="expanded[section.id]" class="section-body">
            <view v-if="section.resources.length === 0" class="section-note">
              该章节暂无资料
            </view>
            <view
              v-for="res in section.resources"
              :key="res.resourceId"
              class="resource-row"
              @click="openLibraryResource(res.resourceId, res.kind, res.originalName)"
            >
              <text class="resource-kind">{{ res.kind === 'VIDEO' ? '视频' : res.kind }}</text>
              <text class="resource-name">{{ res.originalName }}</text>
              <text class="resource-action">
                {{ openingResourceId === res.resourceId ? '打开中…' : (res.kind === 'VIDEO' ? '播放' : '打开') }}
              </text>
            </view>
          </view>
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

.meta {
  margin-top: 10rpx;
  font-size: 22rpx;
  color: #8a97a5;
}

.desc {
  margin-top: 16rpx;
  font-size: 24rpx;
  line-height: 1.6;
  color: #5b6b7c;
}

.progress-box {
  margin-top: 20rpx;
}

.progress-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10rpx;
}

.progress-label {
  font-size: 24rpx;
  color: #5b6b7c;
}

.progress-value {
  font-size: 23rpx;
  font-weight: 600;
  color: #2f6fed;
}

.progress-note {
  margin-top: 16rpx;
  font-size: 22rpx;
  color: #a8b4c0;
}

.section-right {
  display: flex;
  gap: 12rpx;
  align-items: center;
}

.section-progress {
  font-size: 22rpx;
  color: #2f6fed;
}

.chevron {
  font-size: 22rpx;
  color: #2f6fed;
}

.sections {
  margin-top: 24rpx;
}

.section-card {
  margin-bottom: 16rpx;
  border-radius: 16rpx;
  background-color: #ffffff;
  overflow: hidden;
}

.section-head {
  display: flex;
  gap: 12rpx;
  align-items: center;
  padding: 26rpx 24rpx;
}

.section-index {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36rpx;
  height: 36rpx;
  border-radius: 50%;
  background-color: #e8f1f7;
  color: #0e5a8a;
  font-size: 22rpx;
}

.section-title {
  flex: 1;
  font-size: 26rpx;
  color: #16202b;
}

.section-meta,
.section-count {
  font-size: 22rpx;
  color: #8a97a5;
}

.section-body {
  padding: 0 24rpx 24rpx 72rpx;
}

.section-note {
  font-size: 22rpx;
  line-height: 1.6;
  color: #7b8a99;
}

.resource-row {
  display: flex;
  gap: 12rpx;
  align-items: center;
  padding: 16rpx 0;
  border-bottom: 1rpx solid #f0f4f8;
}

.resource-row:last-child {
  border-bottom: none;
}

.resource-kind {
  padding: 2rpx 10rpx;
  border-radius: 6rpx;
  background-color: #eef4fa;
  color: #0e5a8a;
  font-size: 20rpx;
}

.resource-name {
  flex: 1;
  overflow: hidden;
  font-size: 24rpx;
  color: #16202b;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.resource-action {
  font-size: 22rpx;
  color: #1890ff;
}

.state-box {
  display: flex;
  flex-direction: column;
  gap: 12rpx;
  align-items: center;
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
  color: #7b8a99;
  text-align: center;
}
</style>
