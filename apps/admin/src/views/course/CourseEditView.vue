<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  bindResource,
  copyVersion,
  createSection,
  createVersion,
  deleteSection,
  fetchCourseDetail,
  fetchVersions,
  publishVersion,
  unbindResource,
  updateCourse,
  uploadResource,
} from '@/api/course'
import type { CourseDetail, CourseVersion } from '@/api/course'

const route = useRoute()
const router = useRouter()
const courseId = Number(route.params.id)

const detail = ref<CourseDetail | null>(null)
const versions = ref<CourseVersion[]>([])
const activeVersionNo = ref<number>(0)
const loading = ref(false)
const saving = ref(false)

const editTitle = ref('')
const editDescription = ref('')

const uploadKind = ref<'VIDEO' | 'PDF' | 'PPT' | 'IMAGE'>('PDF')
const uploading = ref(false)
const uploadPercent = ref(0)
const bindTarget = ref<{ sectionId: number, resourceId: number } | null>(null)

const currentVersion = computed(() => versions.value.find(v => v.versionNo === activeVersionNo.value) ?? null)
const isDraft = computed(() => currentVersion.value?.status === 'DRAFT')

async function load() {
  loading.value = true
  try {
    versions.value = await fetchVersions(courseId)
    if (!activeVersionNo.value && versions.value.length > 0) {
      activeVersionNo.value = versions.value[0].versionNo
    }
    detail.value = await fetchCourseDetail(courseId, activeVersionNo.value || undefined)
    editTitle.value = detail.value.title
    editDescription.value = detail.value.description ?? ''
  }
  catch {
    detail.value = null
  }
  finally {
    loading.value = false
  }
}

async function switchVersion(no: number) {
  activeVersionNo.value = no
  await load()
}

async function saveBasic() {
  if (!editTitle.value.trim()) {
    ElMessage.warning('标题不能为空')
    return
  }
  saving.value = true
  try {
    await updateCourse(courseId, editTitle.value.trim(), editDescription.value || undefined)
    ElMessage.success('已保存')
    await load()
  }
  finally {
    saving.value = false
  }
}

async function addSection() {
  if (!isDraft.value) return
  try {
    const { value } = await ElMessageBox.prompt('章节标题', '新增章节', {
      inputPattern: /\S+/,
      inputErrorMessage: '标题不能为空',
    })
    await createSection(courseId, activeVersionNo.value, value.trim())
    ElMessage.success('章节已新增')
    await load()
  }
  catch {
    // 取消
  }
}

async function removeSection(sectionId: number, title: string) {
  try {
    await ElMessageBox.confirm(`确认删除章节「${title}」？章节下有资源时需先解绑。`, '确认删除', {
      type: 'warning',
    })
    await deleteSection(courseId, activeVersionNo.value, sectionId)
    ElMessage.success('已删除')
    await load()
  }
  catch {
    // 取消或已提示（409 由拦截器展示）
  }
}

function pickFile() {
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = '.mp4,.webm,.pdf,.pptx,.png,.jpg,.jpeg,.webp'
  input.onchange = async () => {
    const file = input.files?.[0]
    if (!file) return
    uploading.value = true
    uploadPercent.value = 0
    try {
      await uploadResource(courseId, uploadKind.value, file, p => (uploadPercent.value = p))
      ElMessage.success('上传成功，请在下方选择章节绑定')
      await load()
    }
    catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '上传失败')
    }
    finally {
      uploading.value = false
    }
  }
  input.click()
}

async function bind(sectionId: number, resourceId: number) {
  bindTarget.value = { sectionId, resourceId }
  try {
    await bindResource(courseId, activeVersionNo.value, sectionId, resourceId)
    ElMessage.success('已绑定')
    await load()
  }
  catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '绑定失败')
  }
  finally {
    bindTarget.value = null
  }
}

async function unbind(sectionId: number, resourceId: number) {
  try {
    await unbindResource(courseId, activeVersionNo.value, sectionId, resourceId)
    ElMessage.success('已解绑')
    await load()
  }
  catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '解绑失败')
  }
}

async function publish() {
  try {
    await ElMessageBox.confirm(
      '发布后该版本内容不可修改，且会立即对学员可见。如需改版请复制为新版本。',
      '确认发布',
      { type: 'warning', confirmButtonText: '确认发布' },
    )
    await publishVersion(courseId, activeVersionNo.value)
    ElMessage.success('已发布')
    await load()
  }
  catch {
    // 取消或已提示（409 由拦截器展示）
  }
}

async function createNewVersion() {
  try {
    const { value } = await ElMessageBox.prompt('新版本标题（留空沿用当前）', '新建版本', {
      inputValue: currentVersion.value?.title ?? '',
    })
    await createVersion(courseId, value?.trim() || undefined)
    ElMessage.success('已新建草稿版本')
    await load()
  }
  catch {
    // 取消
  }
}

async function copyCurrent() {
  try {
    const result = await copyVersion(courseId, activeVersionNo.value)
    ElMessage.success(`已复制为 v${result.versionNo}`)
    await load()
    activeVersionNo.value = result.versionNo
    await load()
  }
  catch {
    // 已提示
  }
}

function formatSize(bytes: number) {
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(0)} KB`
  return `${bytes} B`
}

function kindLabel(kind: string) {
  return { VIDEO: '视频', PDF: 'PDF', PPT: 'PPT', IMAGE: '图片' }[kind] ?? kind
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <header class="page-header">
      <div class="header-row">
        <h1 class="page-title">课程编辑</h1>
        <el-button link @click="router.push('/courses')">返回列表</el-button>
      </div>
      <p class="page-desc">课程信息作用于列表展示；版本内容发布后不可修改。</p>
    </header>

    <div v-if="detail" class="surface-card section-block">
      <h2 class="block-title">基本信息</h2>
      <el-form label-width="90px" class="basic-form">
        <el-form-item label="课程标题">
          <el-input v-model="editTitle" maxlength="200" />
        </el-form-item>
        <el-form-item label="简介">
          <el-input v-model="editDescription" type="textarea" :rows="3" maxlength="2000" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="saveBasic">保存基本信息</el-button>
        </el-form-item>
      </el-form>
    </div>

    <div v-if="detail" class="surface-card section-block">
      <div class="block-head">
        <h2 class="block-title">版本</h2>
        <div class="block-actions">
          <el-button size="small" @click="createNewVersion">新建版本</el-button>
          <el-button size="small" @click="copyCurrent">复制当前版本</el-button>
          <el-button
            size="small"
            type="primary"
            :disabled="!isDraft"
            @click="publish"
          >
            发布当前版本
          </el-button>
        </div>
      </div>

      <el-radio-group :model-value="activeVersionNo" @change="switchVersion(Number($event))">
        <el-radio-button v-for="v in versions" :key="v.versionNo" :value="v.versionNo">
          v{{ v.versionNo }} · {{ v.status === 'PUBLISHED' ? '已发布' : '草稿' }}
        </el-radio-button>
      </el-radio-group>

      <el-alert
        v-if="!isDraft"
        class="readonly-alert"
        type="info"
        :closable="false"
        show-icon
        title="该版本已发布，内容只读"
        description="如需修改，请点击「复制当前版本」生成新草稿后再编辑。"
      />

      <div class="chapters">
        <div class="chapter-head">
          <span class="chapter-title">章节（v{{ activeVersionNo }}）</span>
          <el-button size="small" :disabled="!isDraft" @click="addSection">新增章节</el-button>
        </div>

        <el-empty v-if="detail.version.sections.length === 0" description="暂无章节" />

        <div
          v-for="section in detail.version.sections"
          :key="section.id"
          class="chapter-card"
        >
          <div class="chapter-row">
            <span class="chapter-index">{{ section.sortNo }}</span>
            <span class="chapter-name">{{ section.title }}</span>
            <span v-if="section.durationSeconds" class="chapter-meta">
              {{ Math.round(section.durationSeconds / 60) }} 分钟
            </span>
            <el-button
              v-if="isDraft"
              link
              type="danger"
              @click="removeSection(section.id, section.title)"
            >
              删除章节
            </el-button>
          </div>

          <div class="resource-list">
            <div
              v-for="res in section.resources"
              :key="res.resourceId"
              class="resource-item"
            >
              <el-tag size="small" effect="plain">{{ kindLabel(res.kind) }}</el-tag>
              <span class="resource-name">{{ res.originalName }}</span>
              <span class="resource-size">{{ formatSize(res.sizeBytes) }}</span>
              <el-button
                v-if="isDraft"
                link
                type="danger"
                @click="unbind(section.id, res.resourceId)"
              >
                解绑
              </el-button>
            </div>
            <span v-if="section.resources.length === 0" class="resource-empty">
              该章节还没有资源
            </span>
          </div>
        </div>
      </div>
    </div>

    <div v-if="detail" class="surface-card section-block">
      <h2 class="block-title">上传资源</h2>
      <p class="block-desc">
        上传后需在章节中选择「绑定」。视频上限 1GB，其他 50MB；类型由服务端按文件内容校验。
      </p>
      <div class="upload-bar">
        <el-select v-model="uploadKind" style="width: 140px">
          <el-option label="视频" value="VIDEO" />
          <el-option label="PDF" value="PDF" />
          <el-option label="PPT" value="PPT" />
          <el-option label="图片" value="IMAGE" />
        </el-select>
        <el-button type="primary" :loading="uploading" @click="pickFile">
          {{ uploading ? `上传中 ${uploadPercent}%` : '选择文件上传' }}
        </el-button>
        <el-progress
          v-if="uploading"
          class="upload-progress"
          :percentage="uploadPercent"
          :stroke-width="10"
        />
      </div>

      <div v-if="detail.unboundResources.length > 0" class="unbound-list">
        <div class="unbound-title">未绑定资源（{{ detail.unboundResources.length }}）</div>
        <div
          v-for="res in detail.unboundResources"
          :key="res.id"
          class="resource-item"
        >
          <el-tag size="small" effect="plain">{{ kindLabel(res.kind) }}</el-tag>
          <span class="resource-name">{{ res.originalName }}</span>
          <span class="resource-size">{{ formatSize(res.sizeBytes) }}</span>
          <el-select
            v-if="isDraft"
            placeholder="选择章节绑定"
            size="small"
            style="width: 160px"
            @change="bind(Number($event), res.id)"
          >
            <el-option
              v-for="s in detail.version.sections"
              :key="s.id"
              :label="`${s.sortNo}. ${s.title}`"
              :value="s.id"
            />
          </el-select>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.header-row {
  display: flex;
  gap: 12px;
  align-items: baseline;
}

.section-block {
  margin-bottom: 16px;
}

.block-title {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 600;
}

.block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.block-actions {
  display: flex;
  gap: 8px;
}

.block-desc {
  margin: 0 0 12px;
  color: var(--color-text-secondary);
  font-size: 12px;
}

.basic-form {
  max-width: 640px;
}

.readonly-alert {
  margin-top: 12px;
}

.chapters {
  margin-top: 16px;
}

.chapter-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.chapter-title {
  font-size: 13px;
  color: var(--color-text-secondary);
}

.chapter-card {
  margin-bottom: 10px;
  padding: 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
}

.chapter-row {
  display: flex;
  gap: 10px;
  align-items: center;
}

.chapter-index {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--color-bg-subtle, #eef2f6);
  font-size: 12px;
}

.chapter-name {
  font-weight: 500;
}

.chapter-meta {
  color: var(--color-text-secondary);
  font-size: 12px;
}

.resource-list {
  margin-top: 8px;
  padding-left: 32px;
}

.resource-item {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 4px 0;
  font-size: 13px;
}

.resource-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.resource-size {
  color: var(--color-text-secondary);
  font-size: 12px;
}

.resource-empty {
  color: var(--color-text-secondary);
  font-size: 12px;
}

.upload-bar {
  display: flex;
  gap: 12px;
  align-items: center;
}

.upload-progress {
  flex: 1;
  max-width: 320px;
}

.unbound-list {
  margin-top: 16px;
}

.unbound-title {
  margin-bottom: 8px;
  font-size: 13px;
  color: var(--color-text-secondary);
}
</style>
