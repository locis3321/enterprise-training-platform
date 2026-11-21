<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  assignTaskEmployees,
  closeTask,
  createSuccessorTask,
  fetchPackageDetail,
  fetchPackages,
  fetchTaskAssignments,
  fetchTaskDetail,
} from '@/api/training'
import type { TaskAssignmentRow, TaskDetail } from '@/api/training'
import { fetchEmployees } from '@/api/employee'

const route = useRoute()
const router = useRouter()
const taskId = Number(route.params.id)

const detail = ref<TaskDetail | null>(null)
const rows = ref<TaskAssignmentRow[]>([])
const loading = ref(false)

const assignVisible = ref(false)
const candidates = ref<{ id: number, name: string }[]>([])
const pickedIds = ref<number[]>([])
const assigning = ref(false)

const statusMeta: Record<string, { label: string, type: 'success' | 'info' | 'warning' }> = {
  NOT_STARTED: { label: '未开始', type: 'info' },
  IN_PROGRESS: { label: '进行中', type: 'warning' },
  COMPLETED: { label: '已完成', type: 'success' },
}

const percent = computed(() => {
  const p = detail.value?.progress
  if (!p || p.total === 0) {
    return 0
  }
  return Math.round((p.completed / p.total) * 100)
})

async function load() {
  loading.value = true
  try {
    const [d, list] = await Promise.all([
      fetchTaskDetail(taskId),
      fetchTaskAssignments(taskId),
    ])
    detail.value = d
    rows.value = list
  }
  catch {
    detail.value = null
    rows.value = []
  }
  finally {
    loading.value = false
  }
}

async function openAssign() {
  pickedIds.value = []
  assignVisible.value = true
  if (!detail.value) {
    return
  }
  try {
    const list = await fetchEmployees({
      enterpriseId: detail.value.task.enterpriseId, status: 1, page: 1, pageSize: 200,
    })
    candidates.value = (list.items ?? []).map(e => ({ id: e.id, name: e.name }))
  }
  catch {
    candidates.value = []
  }
}

async function submitAssign() {
  if (!pickedIds.value.length) {
    ElMessage.warning('请至少选择一个员工')
    return
  }
  assigning.value = true
  try {
    const result = await assignTaskEmployees(taskId, pickedIds.value)
    ElMessage.success(result.inserted > 0
      ? `已追加 ${result.inserted} 人`
      : '所选员工已在任务中，未新增')
    assignVisible.value = false
    await load()
  }
  catch {
    // 409（任务已关闭）等由拦截器提示
  }
  finally {
    assigning.value = false
  }
}

async function close() {
  try {
    await ElMessageBox.confirm('关闭后学员不可继续学习，且不能追加分配。', '确认关闭任务', {
      type: 'warning',
    })
    await closeTask(taskId)
    ElMessage.success('任务已关闭')
    await load()
  }
  catch {
    // 取消或已提示
  }
}

function fmt(value: string | null) {
  return value ? value.replace('T', ' ') : '—'
}

function fmtSeconds(seconds: number) {
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return m > 0 ? `${m} 分 ${s} 秒` : `${s} 秒`
}

/* ---------- 追加新版本任务 ---------- */

const successorVisible = ref(false)
const creatingSuccessor = ref(false)
const versionOptions = ref<{ id: number, versionNo: number, label: string }[]>([])
const successorForm = reactive({
  title: '',
  packageVersionId: 0,
  startAt: '' as string | Date,
  learnDeadlineAt: '' as string | Date,
})

/** 打开对话框前加载该培训包的已发布版本（同版本禁用，因为无新内容）。 */
async function openSuccessor() {
  if (!detail.value) {
    return
  }
  successorForm.title = `${detail.value.task.title}（补充）`
  successorForm.packageVersionId = 0
  successorForm.startAt = ''
  successorForm.learnDeadlineAt = ''
  versionOptions.value = []
  successorVisible.value = true
  try {
    // 任务只保存 packageVersionId，需先反查所属培训包再取其版本列表
    const packages = await fetchPackages({ page: 1, pageSize: 200 })
    for (const pkg of packages.items) {
      const d = await fetchPackageDetail(pkg.id)
      const matched = d.versions.some(v => v.id === detail.value?.task.packageVersionId)
      if (!matched) {
        continue
      }
      versionOptions.value = d.versions
        .filter(v => v.status === 'PUBLISHED')
        .map(v => ({ id: v.id, versionNo: v.versionNo, label: `${d.title} · ${v.title || '未命名'}` }))
      break
    }
  }
  catch {
    versionOptions.value = []
  }
}

async function submitSuccessor() {
  if (!detail.value) {
    return
  }
  if (!successorForm.title.trim()) {
    ElMessage.warning('请填写任务名称')
    return
  }
  if (!successorForm.packageVersionId) {
    ElMessage.warning('请选择培训包版本')
    return
  }
  if (!successorForm.startAt || !successorForm.learnDeadlineAt) {
    ElMessage.warning('请填写开始时间与学习截止时间')
    return
  }
  creatingSuccessor.value = true
  try {
    await createSuccessorTask(detail.value.task.id, {
      enterpriseId: detail.value.task.enterpriseId,
      packageVersionId: successorForm.packageVersionId,
      title: successorForm.title.trim(),
      startAt: toIso(new Date(successorForm.startAt)),
      learnDeadlineAt: toIso(new Date(successorForm.learnDeadlineAt)),
      assignAll: false,
    })
    ElMessage.success('已创建新版本任务，并分配给原任务的学员')
    successorVisible.value = false
  }
  catch {
    // 错误由 request 拦截器提示（同版本 409 等）
  }
  finally {
    creatingSuccessor.value = false
  }
}

/** 本地时间 → ISO 字符串（后端按 Asia/Shanghai 解析，不带时区后缀）。 */
function toIso(d: Date) {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <header class="page-header">
      <div class="header-row">
        <h1 class="page-title">{{ detail?.task.title ?? '培训任务' }}</h1>
        <div>
          <el-button @click="router.push('/training/tasks')">返回列表</el-button>
          <el-button v-if="detail?.task.status === 'PUBLISHED'" @click="openAssign">追加分配</el-button>
          <el-button @click="openSuccessor">追加新版本任务</el-button>
          <el-button v-if="detail?.task.status === 'PUBLISHED'" type="warning" @click="close">关闭任务</el-button>
        </div>
      </div>
      <p class="page-desc">
        <el-tag size="small" :type="statusMeta[detail?.task.status ?? '']?.type ?? 'info'">
          {{ statusMeta[detail?.task.status ?? '']?.label ?? detail?.task.status }}
        </el-tag>
        企业 {{ detail?.task.enterpriseId }} · 包版本 {{ detail?.task.packageVersionId }}
        · 学习时间窗 {{ fmt(detail?.task.startAt ?? null) }} → {{ fmt(detail?.task.learnDeadlineAt ?? null) }}
      </p>
    </header>

    <div class="surface-card">
      <h2 class="section-title">进度</h2>
      <div class="progress-row">
        <el-progress :percentage="percent" :stroke-width="16" style="flex: 1" />
        <span class="progress-text">
          {{ detail?.progress.completed ?? 0 }} / {{ detail?.progress.total ?? 0 }} 人已完成
        </span>
      </div>
      <div class="stat-row">
        <el-tag type="info">未开始 {{ detail?.progress.notStarted ?? 0 }}</el-tag>
        <el-tag type="warning">进行中 {{ detail?.progress.inProgress ?? 0 }}</el-tag>
        <el-tag type="success">已完成 {{ detail?.progress.completed ?? 0 }}</el-tag>
      </div>
    </div>

    <div class="surface-card">
      <h2 class="section-title">员工分配明细</h2>
      <p class="dialog-hint">
        「累计有效观看」是服务端记录的观看时长（可因重复观看超出章节时长）；
        完成与否以「状态」为准——视频播完才算完成该章节，两者不是同一口径。
      </p>
      <el-table :data="rows" border size="small">
        <el-table-column prop="employeeId" label="员工 ID" width="100" />
        <el-table-column prop="name" label="姓名" min-width="120" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="statusMeta[row.status]?.type ?? 'info'">
              {{ statusMeta[row.status]?.label ?? row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="累计有效观看" width="150">
          <template #default="{ row }">{{ fmtSeconds(row.effectiveSeconds) }}</template>
        </el-table-column>
        <el-table-column label="开始时间" width="170">
          <template #default="{ row }">{{ fmt(row.startedAt) }}</template>
        </el-table-column>
        <el-table-column label="完成时间" width="170">
          <template #default="{ row }">{{ fmt(row.completedAt) }}</template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!rows.length" description="该任务还没有分配员工" :image-size="80" />
    </div>

    <el-dialog v-model="assignVisible" title="追加分配员工" width="520px">
      <p class="dialog-hint">
        任务创建时的「企业全体」是当时快照，之后新导入的员工不会自动加入，需要在这里显式追加。
      </p>
      <el-select v-model="pickedIds" multiple filterable placeholder="选择员工" style="width: 100%">
        <el-option v-for="e in candidates" :key="e.id" :label="e.name" :value="e.id" />
      </el-select>
      <template #footer>
        <el-button @click="assignVisible = false">取消</el-button>
        <el-button type="primary" :loading="assigning" @click="submitAssign">确认追加</el-button>
      </template>
    </el-dialog>

    <!-- 追加新版本任务：新建任务而不改原任务版本，旧进度与完成记录保持不变 -->
    <el-dialog v-model="successorVisible" title="追加新版本任务" width="560px">
      <p class="dialog-hint">
        培训任务绑定的是不可变版本快照。要给这批学员补充新版培训包的内容，请新建一个任务：
        原任务与其完成记录保持不变，学员端会同时看到旧任务（已完成）和新任务。
      </p>
      <el-form label-width="96px">
        <el-form-item label="任务名称">
          <el-input v-model="successorForm.title" placeholder="例如：2026 年度入职培训（补充）" />
        </el-form-item>
        <el-form-item label="培训包版本">
          <el-select v-model="successorForm.packageVersionId" filterable placeholder="选择已发布版本" style="width: 100%">
            <el-option
              v-for="v in versionOptions"
              :key="v.id"
              :label="`${v.label}（v${v.versionNo}）`"
              :value="v.id"
              :disabled="v.id === detail?.task.packageVersionId"
            />
          </el-select>
          <div class="field-hint">
            与原任务相同的版本已禁用（无新内容）。
          </div>
        </el-form-item>
        <el-form-item label="开始时间">
          <el-date-picker v-model="successorForm.startAt" type="datetime" style="width: 100%" />
        </el-form-item>
        <el-form-item label="学习截止">
          <el-date-picker v-model="successorForm.learnDeadlineAt" type="datetime" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="successorVisible = false">取消</el-button>
        <el-button type="primary" :loading="creatingSuccessor" @click="submitSuccessor">创建并分配</el-button>
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

.section-title {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 600;
}

.progress-row {
  display: flex;
  gap: 16px;
  align-items: center;
}

.progress-text {
  color: var(--color-text-secondary, #606266);
  font-size: 13px;
  white-space: nowrap;
}

.stat-row {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.dialog-hint {
  margin: 0 0 12px;
  color: var(--color-text-secondary, #909399);
  font-size: 12px;
}
</style>
