<script lang="ts" setup>
import { computed } from 'vue'
import type { MyTaskResource, MyTaskSection } from '@/api/training'

/**
 * 章节「学习」直达按钮。
 *
 * <p>为什么必须携带 assignmentId：取签名地址走的是**任务上下文路由**
 * {@code /miniapp/training/assignments/{a}/courses/{c}/resources/{r}/url}，
 * 版本由 assignment 推导。若退化为「课程最新版本」，旧任务会读到改版后的新内容，
 * 违反「培训任务绑定具体版本」的约束。
 *
 * <p>因此本组件只在已知 assignment 时可用；找不到可选资源时禁用并提示。
 */
const props = defineProps<{
  assignmentId: number
  courseId: number | null
  section: MyTaskSection
  disabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'learn', payload: { resource: MyTaskResource, section: MyTaskSection }): void
}>()

/** 章节内第一个可学资源（按 sort_no 顺序，后端已排序）。 */
const firstResource = computed(() => props.section.resources?.[0] ?? null)

const canLearn = computed(() => !props.disabled && props.courseId != null && !!firstResource.value)

const label = computed(() => {
  if (props.courseId == null) {
    return '不可学'
  }
  if (!firstResource.value) {
    return '无资源'
  }
  return '学习'
})

function onTap() {
  if (!canLearn.value || !firstResource.value) {
    return
  }
  emit('learn', { resource: firstResource.value, section: props.section })
}
</script>

<template>
  <view class="learn-button" :class="{ disabled: !canLearn }" @click.stop="onTap">
    {{ label }}
  </view>
</template>

<style lang="scss" scoped>
.learn-button {
  padding: 6rpx 20rpx;
  border-radius: 999rpx;
  background-color: #2f6fed;
  color: #ffffff;
  font-size: 22rpx;
  line-height: 1.8;
}

.learn-button.disabled {
  background-color: #d7dee7;
  color: #8a97a5;
}
</style>
