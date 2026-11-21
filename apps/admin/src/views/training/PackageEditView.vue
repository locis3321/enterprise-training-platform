<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  bindPackageCourse,
  copyPackageVersion,
  createPackageVersion,
  fetchPackageDetail,
  publishPackageVersion,
  unbindPackageCourse,
  updatePackage,
  updatePackageVersion,
} from '@/api/training'
import type { PackageDetail } from '@/api/training'
import { fetchCourses, fetchVersions } from '@/api/course'
import type { Course, CourseVersion } from '@/api/course'

const route = useRoute()
const router = useRouter()
const packageId = Number(route.params.id)

const detail = ref<PackageDetail | null>(null)
const versionNo = ref<number | undefined>()
const loading = ref(false)
const editing = ref(false)
const form = ref({ title: '', description: '' })

/** 课程选择：先选课程，再选它的已发布版本。只允许发布后的版本进包。 */
const courseOptions = ref<Course[]>([])
const selectedCourseId = ref<number | undefined>()
const versionOptions = ref<CourseVersion[]>([])
const selectedCourseVersionId = ref<number | undefined>()
const sortNo = ref(1)

const currentVersion = computed(() => detail.value?.version)
const isDraft = computed(() => currentVersion.value?.status === 'DRAFT')

async function load(keepVersion = false) {
  loading.value = true
  try {
    detail.value = await fetchPackageDetail(packageId, keepVersion ? versionNo.value : undefined)
    versionNo.value = detail.value.version.versionNo
    form.value.title = detail.value.title
    form.value.description = detail.value.description ?? ''
    sortNo.value = (currentVersion.value?.courses.length ?? 0) + 1
  }
  catch {
    detail.value = null
  }
  finally {
    loading.value = false
  }
}

async function loadCourses() {
  try {
    const result = await fetchCourses({ page: 1, pageSize: 200, status: 'PUBLISHED' })
    courseOptions.value = result.items
  }
  catch {
    courseOptions.value = []
  }
}

/** 选中课程后加载其版本，仅保留已发布版本。 */
async function onCourseChange(courseId: number | undefined) {
  selectedCourseVersionId.value = undefined
  versionOptions.value = []
  if (!courseId) {
    return
  }
  try {
    const versions = await fetchVersions(courseId)
    versionOptions.value = versions.filter(v => v.status === 'PUBLISHED')
    if (versionOptions.value.length === 1) {
      selectedCourseVersionId.value = versionOptions.value[0].id
    }
  }
  catch {
    versionOptions.value = []
  }
}

async function saveBasic() {
  if (!form.value.title.trim()) {
    ElMessage.warning('培训包名称不能为空')
    return
  }
  try {
    await updatePackage(packageId, form.value.title.trim(), form.value.description || undefined)
    ElMessage.success('已保存；草稿版本标题已同步')
    editing.value = false
    await load(true)
  }
  catch {
    // 拦截器已提示
  }
}

async function bind() {
  if (!selectedCourseVersionId.value) {
    ElMessage.warning('请选择课程及其已发布版本')
    return
  }
  try {
    await bindPackageCourse(packageId, versionNo.value!, selectedCourseVersionId.value, sortNo.value)
    ElMessage.success('已关联课程')
    selectedCourseId.value = undefined
    selectedCourseVersionId.value = undefined
    versionOptions.value = []
    await load(true)
  }
  catch {
    // 409/403 由拦截器提示
  }
}

async function unbind(courseVersionId: number, title: string) {
  try {
    await ElMessageBox.confirm(`解除关联「${title}」？`, '确认解除', { type: 'warning' })
    await unbindPackageCourse(packageId, versionNo.value!, courseVersionId)
    ElMessage.success('已解除关联')
    await load(true)
  }
  catch {
    // 取消或已提示
  }
}

async function publish() {
  const count = currentVersion.value?.courses.length ?? 0
  if (count === 0) {
    ElMessage.warning('至少关联 1 门课程才能发布')
    return
  }
  try {
    await ElMessageBox.confirm(
      '发布后该版本内容不可修改，任务只能绑定已发布版本。确认发布？',
      '确认发布',
      { type: 'warning' },
    )
    await publishPackageVersion(packageId, versionNo.value!)
    ElMessage.success('已发布')
    await load(true)
  }
  catch {
    // 取消或已提示
  }
}

async function newVersion() {
  try {
    const v = await createPackageVersion(packageId)
    ElMessage.success(`已创建 v${v.versionNo} 草稿`)
    await load()
  }
  catch {
    // 已提示
  }
}

async function copyVersion() {
  try {
    const v = await copyPackageVersion(packageId, versionNo.value!)
    ElMessage.success(`已复制为 v${v.versionNo} 草稿`)
    await load()
  }
  catch {
    // 已提示
  }
}

async function renameVersion() {
  const title = currentVersion.value?.title ?? ''
  try {
    const { value } = await ElMessageBox.prompt('版本标题（仅影响草稿）', '修改版本标题', {
      inputValue: title,
      inputPattern: /\S+/,
      inputErrorMessage: '标题不能为空',
    })
    await updatePackageVersion(packageId, versionNo.value!, value.trim(), currentVersion.value?.description ?? undefined)
    ElMessage.success('已保存')
    await load(true)
  }
  catch {
    // 取消或已提示
  }
}

async function switchVersion(no: number) {
  versionNo.value = no
  await load(true)
}

function courseStatusTag(status: string) {
  if (status === 'OFFLINE') {
    return 'warning'
  }
  if (status === 'PUBLISHED') {
    return 'success'
  }
  return 'info'
}

onMounted(async () => {
  await Promise.all([load(), loadCourses()])
})
</script>

<template>
  <div v-loading="loading">
    <header class="page-header">
      <div class="header-row">
        <h1 class="page-title">{{ detail?.title ?? '培训包' }}</h1>
        <div class="header-actions">
          <el-button @click="router.push('/training/packages')">返回列表</el-button>
          <el-button @click="editing = true">编辑基本信息</el-button>
        </div>
      </div>
      <p class="page-desc">
        <el-tag size="small">{{ detail?.status ?? '—' }}</el-tag>
        最新版本 v{{ detail?.latestVersionNo ?? 0 }}
        <template v-if="detail?.publishedVersionNo">
          · 生效版本 v{{ detail.publishedVersionNo }}
        </template>
        · 已发布版本不可修改，改版请「复制版本」后重新发布
      </p>
    </header>

    <el-row :gutter="16">
      <el-col :span="6">
        <div class="surface-card">
          <h2 class="section-title">版本</h2>
          <el-empty v-if="!detail?.versions.length" description="暂无版本" :image-size="60" />
          <ul v-else class="version-list">
            <li
              v-for="v in detail.versions"
              :key="v.id"
              class="version-item"
              :class="{ active: v.versionNo === versionNo }"
              @click="switchVersion(v.versionNo)"
            >
              <span>v{{ v.versionNo }}</span>
              <el-tag size="small" :type="v.status === 'PUBLISHED' ? 'success' : 'info'">
                {{ v.status === 'PUBLISHED' ? '已发布' : '草稿' }}
              </el-tag>
              <span class="muted">{{ v.courseCount }} 门</span>
            </li>
          </ul>
          <div class="version-actions">
            <el-button size="small" @click="newVersion">新建草稿版本</el-button>
            <el-button v-if="versionNo" size="small" @click="copyVersion">复制当前版本</el-button>
          </div>
        </div>
      </el-col>

      <el-col :span="18">
        <div class="surface-card">
          <div class="section-head">
            <h2 class="section-title">
              当前版本 v{{ currentVersion?.versionNo }} · {{ currentVersion?.title }}
            </h2>
            <div>
              <el-button v-if="isDraft" size="small" @click="renameVersion">改版本标题</el-button>
              <el-button v-if="isDraft" type="primary" size="small" @click="publish">发布版本</el-button>
            </div>
          </div>

          <el-alert
            v-if="!isDraft"
            type="info"
            :closable="false"
            show-icon
            title="该版本已发布，内容不可修改。如需调整请复制为新版本。"
            class="mb"
          />

          <el-table v-if="currentVersion?.courses.length" :data="currentVersion.courses" border size="small">
            <el-table-column prop="sortNo" label="顺序" width="70" />
            <el-table-column prop="courseVersionTitle" label="课程（发布时快照）" min-width="200" />
            <el-table-column prop="courseTitle" label="课程当前名称" min-width="180" />
            <el-table-column label="课程状态" width="110">
              <template #default="{ row }">
                <el-tag size="small" :type="courseStatusTag(row.courseStatus)">
                  {{ row.courseStatus }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110">
              <template #default="{ row }">
                <el-button
                  v-if="isDraft"
                  link
                  type="danger"
                  @click="unbind(row.courseVersionId, row.courseVersionTitle)"
                >
                  解除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-else description="该版本还没有关联课程" :image-size="80" />

          <template v-if="isDraft">
            <el-divider content-position="left">关联已发布课程版本</el-divider>
            <div class="bind-row">
              <el-select
                v-model="selectedCourseId"
                placeholder="选择课程"
                filterable
                clearable
                style="width: 260px"
                @change="onCourseChange"
              >
                <el-option v-for="c in courseOptions" :key="c.id" :label="c.title" :value="c.id" />
              </el-select>
              <el-select
                v-model="selectedCourseVersionId"
                placeholder="选择已发布版本"
                style="width: 200px"
                :disabled="!versionOptions.length"
              >
                <el-option
                  v-for="v in versionOptions"
                  :key="v.id"
                  :label="`v${v.versionNo}（${v.sectionCount} 章 / ${v.resourceCount} 资源）`"
                  :value="v.id"
                />
              </el-select>
              <el-input-number v-model="sortNo" :min="1" :max="999" controls-position="right" style="width: 130px" />
              <el-button type="primary" @click="bind">关联</el-button>
              <span v-if="selectedCourseId && !versionOptions.length" class="muted">
                该课程没有已发布版本，需先发布课程
              </span>
            </div>
          </template>
        </div>
      </el-col>
    </el-row>

    <el-dialog v-model="editing" title="编辑培训包基本信息" width="520px">
      <el-form label-width="80px">
        <el-form-item label="名称" required>
          <el-input v-model="form.title" maxlength="200" show-word-limit />
        </el-form-item>
        <el-form-item label="简介">
          <el-input v-model="form.description" type="textarea" :rows="4" maxlength="2000" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editing = false">取消</el-button>
        <el-button type="primary" @click="saveBasic">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.header-actions {
  display: flex;
  gap: 8px;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.section-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}

.version-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.version-item {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
}

.version-item:hover {
  background: var(--el-fill-color-light);
}

.version-item.active {
  background: var(--el-color-primary-light-9);
  font-weight: 600;
}

.muted {
  margin-left: auto;
  color: var(--color-text-secondary, #909399);
  font-size: 12px;
}

.version-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}

.bind-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}

.mb {
  margin-bottom: 12px;
}
</style>
