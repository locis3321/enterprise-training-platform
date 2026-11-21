<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { fetchAssignmentResourceUrl, fetchMyTaskDetail } from '@/api/training'
import type { MyTaskDetail } from '@/api/training'
import { openResource } from '@/api/learning'
import SectionLearnButton from '@/components/SectionLearnButton/SectionLearnButton.vue'

definePage({
  style: {
    navigationBarTitleText: '任务详情',
  },
})

const assignmentId = ref(0)
const detail = ref<MyTaskDetail | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const expanded = ref<Record<number, boolean>>({})
const openingResourceId = ref(0)

const COMPLETED = 'COMPLETED'

/** 资源类型展示文案：不暴露存储层的 kind 枚举值。 */
function kindLabel(kind: string) {
  if (kind === 'VIDEO')
    return '视频'
  if (kind === 'IMAGE')
    return '图片'
  return '资料'
}

/** 学习可用性：只有服务端返回的进行中/未开始状态才允许进入学习页。 */
const learnable = computed(() => {
  const d = detail.value
  if (!d) {
    return false
  }
  return d.status !== COMPLETED
})

const percent = computed(() => {
  const d = detail.value
  if (!d || !d.sectionTotal) {
    return 0
  }
  return Math.round((d.sectionDone / d.sectionTotal) * 100)
})

onShow(async () => {
  // 从学习页返回时刷新，进度以服务端为准
  await load()
})

async function load() {
  const pages = getCurrentPages()
  const current = pages[pages.length - 1] as unknown as { options?: Record<string, string> }
  const id = Number(current?.options?.id ?? assignmentId.value)
  if (!id) {
    errorMessage.value = '缺少任务参数'
    return
  }
  assignmentId.value = id
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await fetchMyTaskDetail(id)
    detail.value = result
    uni.setNavigationBarTitle({ title: result.title })
  }
  catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '任务加载失败'
    detail.value = null
  }
  finally {
    loading.value = false
  }
}

function toggle(sectionId: number) {
  expanded.value[sectionId] = !expanded.value[sectionId]
}

/** 章节完成判定完全由服务端给出（视频播完 / 资料已打开），前端不推算。 */
function sectionDone(section: MyTaskDetail['courses'][number]['sections'][number]) {
  return section.done
}

/**
 * 章节「学习」：直接进入该章节第一个资源，无需先展开。
 *
 * <p>视频走学习页（携带 assignmentId 以走任务上下文取地址并上报心跳）；
 * 非视频取签名地址打开成功后登记 open。
 */
async function learnSection(
  resource: MyTaskDetail['courses'][number]['sections'][number]['resources'][number],
  section: MyTaskDetail['courses'][number]['sections'][number],
  courseId: number | null,
) {
  await open(resource, section, courseId)
}

/** 视频：跳学习页（由学习页负责心跳）；非视频：取签名地址后打开并登记。 */
async function open(item: MyTaskDetail['courses'][number]['sections'][number]['resources'][number], section: MyTaskDetail['courses'][number]['sections'][number], courseId: number | null) {
  if (courseId == null) {
    uni.showToast({ title: '课程信息缺失', icon: 'none' })
    return
  }
  if (item.kind === 'VIDEO') {
    if (!learnable.value) {
      uni.showToast({ title: '任务已完成，无需继续学习', icon: 'none' })
      return
    }
    uni.navigateTo({
      url: `/pages/task/learn?assignmentId=${assignmentId.value}&courseId=${courseId}`
        + `&sectionId=${section.sectionId}&resourceId=${item.resourceId}`
        + `&title=${encodeURIComponent(section.title)}`,
    })
    return
  }

  openingResourceId.value = item.resourceId
  try {
    const url = await fetchAssignmentResourceUrl(assignmentId.value, courseId, item.resourceId)
    // 先打开，成功后再登记「已打开」——打开失败不得登记，否则等于伪造已读
    await new Promise<void>((resolve, reject) => {
      if (item.kind === 'IMAGE') {
        uni.previewImage({ urls: [url], success: () => resolve(), fail: reject })
        return
      }
      uni.showLoading({ title: '正在打开' })
      uni.downloadFile({
        url,
        success: (res) => {
          if (res.statusCode !== 200) {
            reject(new Error(`下载失败（${res.statusCode}）`))
            return
          }
          uni.openDocument({
            filePath: res.tempFilePath,
            showMenu: true,
            success: () => resolve(),
            fail: reject,
          })
        },
        fail: reject,
        complete: () => uni.hideLoading(),
      })
    })
    const result = await openResource(assignmentId.value, item.resourceId)
    if (result.completed) {
      uni.showToast({ title: '本任务已完成', icon: 'success' })
    }
    await load()
  }
  catch (error) {
    uni.showModal({
      title: '资源打开失败',
      content: error instanceof Error ? error.message : '请稍后重试',
      showCancel: false,
    })
  }
  finally {
    openingResourceId.value = 0
  }
}
</script>

<template>
  <view class="page">
    <view v-if="loading" class="hint-card">
      加载中…
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

    <template v-else-if="detail">
      <view class="summary">
        <view class="summary-title">
          {{ detail.title }}
        </view>
        <view class="summary-meta">
          学习期限：{{ detail.startAt?.replace('T', ' ').slice(0, 16) }}
          － {{ detail.learnDeadlineAt?.replace('T', ' ').slice(0, 16) }}
        </view>
        <wd-progress :percentage="percent" :show-text="false" color="#2f6fed" />
        <view class="summary-foot">
          章节 <text class="strong">{{ detail.sectionDone }}/{{ detail.sectionTotal }}</text>
          · 课程 <text class="strong">{{ detail.courseDone }}/{{ detail.courseTotal }}</text>
        </view>
        <view v-if="detail.status === COMPLETED" class="done-banner">
          已完成全部学习，可联系企业管理员确认后续安排。
        </view>
      </view>

      <view v-for="course in detail.courses" :key="course.courseVersionId" class="course-card">
        <view class="course-title">
          {{ course.title }}
        </view>

        <view v-for="section in course.sections" :key="section.sectionId" class="section">
          <view class="section-head" @click="toggle(section.sectionId)">
            <view class="section-title">
              {{ section.title }}
              <text v-if="sectionDone(section)" class="done-mark">已学完</text>
            </view>
            <view class="section-meta">
              <text v-if="sectionDone(section)" class="section-done-text">已学完</text>
              <SectionLearnButton
                v-if="learnable"
                :assignment-id="assignmentId"
                :course-id="course.courseId"
                :section="section"
                @learn="({ resource }) => learnSection(resource, section, course.courseId)"
              />
              <text class="chevron">{{ expanded[section.sectionId] ? '收起' : '展开' }}</text>
            </view>
          </view>

          <view v-if="expanded[section.sectionId]" class="resource-list">
            <view v-if="!section.resources.length" class="resource-empty">
              该章节暂无资源
            </view>
            <view
              v-for="res in section.resources"
              :key="res.resourceId"
              class="resource-item"
              @click="open(res, section, course.courseId)"
            >
              <text class="resource-kind">{{ kindLabel(res.kind) }}</text>
              <text class="resource-name">{{ section.title }}</text>
              <text v-if="openingResourceId === res.resourceId" class="resource-loading">打开中…</text>
              <text v-else class="resource-action">{{ res.kind === 'VIDEO' ? '学习' : '打开' }}</text>
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
  background-color: #f5f7fb;
}

.hint-card {
  padding: 48rpx 32rpx;
  border-radius: 16rpx;
  background-color: #ffffff;
  color: #6b7885;
  font-size: 26rpx;
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
}

.summary {
  margin-bottom: 20rpx;
  padding: 28rpx;
  border-radius: 16rpx;
  background-color: #ffffff;
}

.summary-title {
  margin-bottom: 10rpx;
  color: #1f2733;
  font-size: 32rpx;
  font-weight: 600;
}

.summary-meta {
  margin-bottom: 18rpx;
  color: #6b7885;
  font-size: 23rpx;
}

.summary-foot {
  margin-top: 14rpx;
  color: #6b7885;
  font-size: 24rpx;
}

.strong {
  color: #1f2733;
  font-weight: 600;
}

.done-banner {
  margin-top: 18rpx;
  padding: 16rpx;
  border-radius: 10rpx;
  background-color: #e8f5ec;
  color: #1f7a3d;
  font-size: 23rpx;
}

.course-card {
  margin-bottom: 20rpx;
  padding: 24rpx;
  border-radius: 16rpx;
  background-color: #ffffff;
}

.course-title {
  margin-bottom: 12rpx;
  color: #1f2733;
  font-size: 28rpx;
  font-weight: 600;
}

.section {
  border-top: 1rpx solid #eef1f6;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20rpx 0;
}

.section-title {
  flex: 1;
  color: #1f2733;
  font-size: 26rpx;
}

.done-mark {
  margin-left: 12rpx;
  color: #1f7a3d;
  font-size: 21rpx;
}

.section-meta {
  display: flex;
  gap: 16rpx;
  align-items: center;
  color: #8a97a5;
  font-size: 22rpx;
}

.chevron {
  color: #2f6fed;
}

.resource-list {
  padding-bottom: 12rpx;
}

.resource-item {
  display: flex;
  align-items: center;
  margin-bottom: 12rpx;
  padding: 18rpx 20rpx;
  border-radius: 10rpx;
  background-color: #f7f9fc;
}

.resource-kind {
  margin-right: 14rpx;
  padding: 2rpx 10rpx;
  border-radius: 6rpx;
  background-color: #e7eeff;
  color: #2f6fed;
  font-size: 20rpx;
}

.resource-name {
  flex: 1;
  overflow: hidden;
  color: #3d4757;
  font-size: 24rpx;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.resource-action {
  color: #2f6fed;
  font-size: 23rpx;
}

.resource-loading {
  color: #8a97a5;
  font-size: 23rpx;
}

.resource-empty {
  padding: 16rpx 0;
  color: #8a97a5;
  font-size: 23rpx;
}
</style>
