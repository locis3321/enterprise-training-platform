<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { closeTask, createTask, fetchPackageDetail, fetchPackages, fetchTasks } from '@/api/training'
import type { CreateTaskPayload, TrainingPackage, TrainingTask } from '@/api/training'
import { fetchEmployees } from '@/api/employee'
import { fetchEnterprises } from '@/api/tenancy'

const router = useRouter()
const tasks = ref<TrainingTask[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
const loading = ref(false)

const enterpriseFilter = ref<number | undefined>()
const statusFilter = ref<string | undefined>()
const keyword = ref('')

const enterprises = ref<{ id: number, name: string }[]>([])
const employees = ref<{ id: number, name: string, status: number }[]>([])

const dialogVisible = ref(false)
const submitting = ref(false)
const publishedPackages = ref<TrainingPackage[]>([])
/** 选中培训包后取详情拿到「生效版本」的版本 id——建任务绑定的是版本，不是包。 */
const packageVersions = ref<{ versionId: number, label: string }[]>([])

const form = ref({
  enterpriseId: undefined as number | undefined,
  packageId: undefined as number | undefined,
  packageVersionId: undefined as number | undefined,
  title: '',
  range: [] as string[],
  assignAll: true,
  employeeIds: [] as number[],
})

const statusMeta: Record<string, { label: string, type: 'success' | 'info' | 'warning' }> = {
  DRAFT: { label: '草稿', type: 'info' },
  PUBLISHED: { label: '进行中', type: 'success' },
  CLOSED: { label: '已关闭', type: 'warning' },
}

const canSubmit = computed(() =>
  !!form.value.enterpriseId && !!form.value.packageVersionId && !!form.value.title.trim()
  && form.value.range.length === 2,
)

async function load() {
  loading.value = true
  try {
    const result = await fetchTasks({
      enterpriseId: enterpriseFilter.value,
      status: statusFilter.value,
      keyword: keyword.value || undefined,
      page: page.value,
      pageSize: pageSize.value,
    })
    tasks.value = result.items
    total.value = result.total
  }
  catch {
    tasks.value = []
    total.value = 0
  }
  finally {
    loading.value = false
  }
}

async function loadOptions() {
  try {
    const result = await fetchEnterprises({ page: 1, pageSize: 200 })
    enterprises.value = result.items.map(e => ({ id: e.id, name: e.name }))
  }
  catch {
    enterprises.value = []
  }
}

async function openCreate() {
  form.value = {
    enterpriseId: enterpriseFilter.value,
    packageId: undefined,
    packageVersionId: undefined,
    title: '',
    range: [],
    assignAll: true,
    employeeIds: [],
  }
  employees.value = []
  await loadPublishedPackages()
  dialogVisible.value = true
}

/** 只列已发布（且未下线）的培训包——新建任务只能选已发布版本。 */
async function loadPublishedPackages() {
  try {
    const result = await fetchPackages({ status: 'PUBLISHED', page: 1, pageSize: 200 })
    publishedPackages.value = result.items
  }
  catch {
    publishedPackages.value = []
  }
}

/** 选择培训包 → 拉详情 → 只保留已发布版本。 */
async function onPackageChange(packageId: number | undefined) {
  form.value.packageVersionId = undefined
  packageVersions.value = []
  if (!packageId) {
    return
  }
  try {
    const detail = await fetchPackageDetail(packageId)
    packageVersions.value = detail.versions
      .filter(v => v.status === 'PUBLISHED')
      .map(v => ({ versionId: v.id, label: `v${v.versionNo} · ${v.title}（${v.courseCount} 门课程）` }))
    const effective = detail.versions.find(v => v.versionNo === detail.publishedVersionNo)
    if (effective && effective.status === 'PUBLISHED') {
      form.value.packageVersionId = effective.id
    }
  }
  catch {
    packageVersions.value = []
  }
}

async function onEnterpriseChange(id: number | undefined) {
  form.value.employeeIds = []
  employees.value = []
  if (!id || form.value.assignAll) {
    return
  }
  try {
    const list = await fetchEmployees({ enterpriseId: id, status: 1, page: 1, pageSize: 200 })
    employees.value = (list.items ?? []).map(e => ({ id: e.id, name: e.name, status: e.status }))
  }
  catch {
    employees.value = []
  }
}

async function onAssignModeChange() {
  if (!form.value.assignAll) {
    await onEnterpriseChange(form.value.enterpriseId)
  }
}

async function submit() {
  if (!canSubmit.value) {
    ElMessage.warning('请填写企业、培训包版本、标题与起止时间')
    return
  }
  if (!form.value.assignAll && form.value.employeeIds.length === 0) {
    ElMessage.warning('请至少选择一个员工')
    return
  }
  const [startAt, learnDeadlineAt] = form.value.range
  const payload: CreateTaskPayload = {
    enterpriseId: form.value.enterpriseId!,
    packageVersionId: form.value.packageVersionId!,
    title: form.value.title.trim(),
    startAt,
    learnDeadlineAt,
    assignAll: form.value.assignAll,
    employeeIds: form.value.assignAll ? undefined : form.value.employeeIds,
  }
  submitting.value = true
  try {
    const task = await createTask(payload)
    ElMessage.success('任务已创建并发布')
    dialogVisible.value = false
    await load()
    await router.push(`/training/tasks/${task.id}`)
  }
  catch {
    // 409/400/403 由拦截器提示
  }
  finally {
    submitting.value = false
  }
}

async function close(row: TrainingTask) {
  try {
    await closeTask(row.id)
    ElMessage.success('任务已关闭')
    await load()
  }
  catch {
    // 已提示
  }
}

onMounted(async () => {
  await Promise.all([load(), loadOptions()])
})
</script>

<template>
  <div>
    <header class="page-header">
      <div class="header-row">
        <h1 class="page-title">培训任务</h1>
        <div>
          <el-button @click="router.push('/training/packages')">培训包</el-button>
          <el-button type="primary" @click="openCreate">新建任务</el-button>
        </div>
      </div>
      <p class="page-desc">
        任务创建即发布，分配是创建时的一次性快照：后续新导入的员工不会自动加入，需在任务详情里追加。
      </p>
    </header>

    <div class="surface-card">
      <div class="toolbar">
        <el-input v-model="keyword" placeholder="任务名称" clearable style="width: 220px"
                  @keyup.enter="page = 1; load()" />
        <el-select v-model="enterpriseFilter" placeholder="企业" clearable filterable style="width: 200px">
          <el-option v-for="e in enterprises" :key="e.id" :label="e.name" :value="e.id" />
        </el-select>
        <el-select v-model="statusFilter" placeholder="状态" clearable style="width: 130px">
          <el-option label="进行中" value="PUBLISHED" />
          <el-option label="已关闭" value="CLOSED" />
        </el-select>
        <el-button type="primary" @click="page = 1; load()">查询</el-button>
      </div>

      <el-table v-loading="loading" :data="tasks" border>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="title" label="任务名称" min-width="200" />
        <el-table-column label="企业" width="120">
          <template #default="{ row }">{{ row.enterpriseId }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusMeta[row.status]?.type ?? 'info'">
              {{ statusMeta[row.status]?.label ?? row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="学习时间窗" min-width="260">
          <template #default="{ row }">
            {{ row.startAt?.replace('T', ' ') }} → {{ row.learnDeadlineAt?.replace('T', ' ') }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button link type="primary" @click="router.push(`/training/tasks/${row.id}`)">详情</el-button>
            <el-button v-if="row.status === 'PUBLISHED'" link type="warning" @click="close(row)">关闭</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        class="pagination"
        layout="total, prev, pager, next"
        :total="total"
        @current-change="load"
      />
    </div>

    <el-dialog v-model="dialogVisible" title="新建培训任务" width="640px">
      <el-form label-width="110px">
        <el-form-item label="目标企业" required>
          <el-select v-model="form.enterpriseId" placeholder="选择企业" filterable style="width: 100%"
                     @change="onEnterpriseChange">
            <el-option v-for="e in enterprises" :key="e.id" :label="e.name" :value="e.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="培训包" required>
          <el-select v-model="form.packageId" placeholder="选择已发布的培训包" filterable style="width: 100%"
                     @change="onPackageChange">
            <el-option v-for="p in publishedPackages" :key="p.id" :label="p.title" :value="p.id" />
          </el-select>
          <span class="muted">仅列已发布且未下线的培训包</span>
        </el-form-item>
        <el-form-item label="包版本" required>
          <el-select v-model="form.packageVersionId" placeholder="选择已发布版本" style="width: 100%"
                     :disabled="!packageVersions.length">
            <el-option v-for="v in packageVersions" :key="v.versionId" :label="v.label" :value="v.versionId" />
          </el-select>
          <span v-if="form.packageId && !packageVersions.length" class="muted">该培训包没有已发布版本</span>
        </el-form-item>
        <el-form-item label="任务名称" required>
          <el-input v-model="form.title" maxlength="200" show-word-limit placeholder="例如：2026 年新员工入职培训" />
        </el-form-item>
        <el-form-item label="学习时间窗" required>
          <el-date-picker
            v-model="form.range"
            type="datetimerange"
            value-format="YYYY-MM-DDTHH:mm:ss"
            start-placeholder="开始时间"
            end-placeholder="学习截止时间"
            style="width: 100%"
          />
          <span class="muted">左闭右开：截止时刻起即不可学</span>
        </el-form-item>
        <el-form-item label="分配方式">
          <el-radio-group v-model="form.assignAll" @change="onAssignModeChange">
            <el-radio :value="true">企业全体（当前启用员工）</el-radio>
            <el-radio :value="false">指定员工</el-radio>
          </el-radio-group>
          <span class="muted">企业全体是创建时快照，后续新员工不会自动加入</span>
        </el-form-item>
        <el-form-item v-if="!form.assignAll" label="选择员工" required>
          <el-select v-model="form.employeeIds" multiple filterable placeholder="选择员工" style="width: 100%">
            <el-option v-for="e in employees" :key="e.id" :label="e.name" :value="e.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="submit">
          创建并发布
        </el-button>
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

.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
}

.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}

.muted {
  color: var(--color-text-secondary, #909399);
  font-size: 12px;
}
</style>
