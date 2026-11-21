<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchPackages } from '@/api/training'
import type { TrainingPackage } from '@/api/training'
import { createPaper, copyPaper, fetchPaper, fetchPaperByPackage, fetchPaperQuestions, publishPaper, unpublishPaper, updatePaperScores, updatePaperSettings } from '@/api/exam'
import type { PaperDetail, PaperQuestionItem } from '@/api/exam'
import { fetchCourseDetail } from '@/api/course'

const packages = ref<TrainingPackage[]>([])
const packageId = ref<number>()
const detail = ref<PaperDetail | null>(null)
const loading = ref(false)

/** 题目分页：题目可能很多，整包下发既慢又难浏览。 */
const questions = ref<PaperQuestionItem[]>([])
const questionTotal = ref(0)
const questionPage = ref(1)
const questionPageSize = ref(20)

const createVisible = ref(false)
const creating = ref(false)
const form = reactive({
  title: '',
  durationMinutes: 60,
  passScore: 90,
  draws: [] as { courseId: number, courseName: string, count: number }[],
})

/** 复制：因为一个包只允许一套试卷，必须选另一个包作为目标。 */
const copyVisible = ref(false)
const copying = ref(false)
const copyForm = reactive({ targetPackageId: undefined as number | undefined, title: '' })

/** 及格是百分比；界面同时展示「得分/总分」与百分比，避免 45/50 被误判不及格。 */
const passHint = computed(() => {
  if (!detail.value)
    return ''
  const d = detail.value
  return `总分 ${d.totalScore} 分，及格线 ${d.paper.passScore}%（即需 ≥ ${Math.ceil((d.totalScore * d.paper.passScore) / 100)} 分）`
})

async function loadPackages() {
  const result = await fetchPackages({ page: 1, pageSize: 100 })
    .catch(() => ({ items: [] as TrainingPackage[], total: 0, page: 1, pageSize: 0 }))
  packages.value = result.items
  if (!packageId.value && packages.value.length) {
    packageId.value = packages.value[0].id
  }
}

/** 打开组卷对话框：加载包内课程以便按课抽题。 */
async function openCreate() {
  if (!packageId.value) {
    ElMessage.warning('请先选择培训包')
    return
  }
  form.title = ''
  form.durationMinutes = 60
  form.passScore = 90
  form.draws = []
  creating.value = false
  createVisible.value = true

  // 用培训包详情拿到包内课程（取当前生效版本）
  const pkg = packages.value.find((p: TrainingPackage) => p.id === packageId.value)
  if (!pkg) {
    return
  }
  try {
    const courses = await fetchPackageCourses(packageId.value)
    form.draws = courses.map(c => ({ courseId: c.id, courseName: c.title, count: 0 }))
  }
  catch {
    ElMessage.error('加载包内课程失败')
  }
}

/** 培训包详情 → 课程列表（复用现有接口，避免再造一个课程清单接口）。 */
async function fetchPackageCourses(id: number): Promise<{ id: number, title: string }[]> {
  const result = await fetchCourseDetail(0).catch(() => null)
  // 该接口不适用时回退：使用包详情接口
  void result
  const resp = await fetch(`/api/v1/admin/training/packages/${id}`, {
    headers: { Authorization: `Bearer ${sessionStorage.getItem('it_admin_token') ?? ''}` },
  })
  const json = await resp.json()
  const courses = json?.data?.version?.courses ?? []
  return courses.map((c: { courseId: number, courseTitle: string }) => ({
    id: c.courseId,
    title: c.courseTitle,
  }))
}

async function submitCreate() {
  if (!packageId.value)
    return
  if (!form.title.trim()) {
    ElMessage.warning('请填写试卷名称')
    return
  }
  const draws = form.draws.filter(d => d.count > 0).map(d => ({ courseId: d.courseId, count: d.count }))
  if (!draws.length) {
    ElMessage.warning('请至少为一门课程指定抽题数量')
    return
  }
  creating.value = true
  try {
    const paper = await createPaper({
      packageId: packageId.value,
      title: form.title.trim(),
      draws,
      durationMinutes: form.durationMinutes,
      passScore: form.passScore,
    })
    createVisible.value = false
    ElMessage.success('试卷已创建（草稿）')
    await load(paper.id)
  }
  finally {
    creating.value = false
  }
}

async function load(paperId?: number) {
  const id = paperId ?? detail.value?.paper.id
  loading.value = true
  try {
    if (id) {
      detail.value = await fetchPaper(id)
    }
    else if (packageId.value) {
      // 切包时按包定位试卷：一个包最多一套，没有就展示空态
      detail.value = await fetchPaperByPackage(packageId.value)
    }
    else {
      detail.value = null
    }
  }
  catch {
    detail.value = null
  }
  finally {
    loading.value = false
  }
  if (detail.value) {
    await loadQuestions()
  }
  else {
    questions.value = []
    questionTotal.value = 0
  }
}

/** 按页取题目（带题干等展示字段）。 */
async function loadQuestions() {
  if (!detail.value)
    return
  const result = await fetchPaperQuestions(detail.value.paper.id, {
    page: questionPage.value,
    pageSize: questionPageSize.value,
  }).catch(() => null)
  questions.value = result?.items ?? []
  questionTotal.value = result?.total ?? 0
}

function onQuestionPageChange() {
  void loadQuestions()
}

async function saveScores() {
  if (!detail.value)
    return
  // 只提交当前页：未翻到的页沿用服务端已有分值
  const items = questions.value.map(q => ({ questionId: q.questionId, score: q.score }))
  await updatePaperScores(detail.value.paper.id, items)
  ElMessage.success('本页分值已保存')
  await loadQuestions()
}

async function saveSettings() {
  if (!detail.value)
    return
  await updatePaperSettings(detail.value.paper.id, detail.value.paper.durationMinutes,
    detail.value.paper.passScore)
  ElMessage.success('设置已保存')
  await load()
}

async function publish() {
  if (!detail.value)
    return
  await publishPaper(detail.value.paper.id)
  ElMessage.success('试卷已发布（此后题目与分值锁定）')
  await load()
}

/** 取消发布：恢复可编辑。进行中的考试仍可考完，历史成绩保留。 */
async function unpublish() {
  if (!detail.value)
    return
  await unpublishPaper(detail.value.paper.id)
  ElMessage.success('已取消发布，现在可以修改题目与分值；进行中的考试仍可正常考完')
  await load()
}

const copyTargets = computed(() =>
  packages.value.filter(p => p.id !== detail.value?.paper.packageId))

function openCopy() {
  if (!detail.value)
    return
  copyForm.targetPackageId = copyTargets.value[0]?.id
  copyForm.title = `${detail.value.paper.title}（副本）`
  copying.value = false
  copyVisible.value = true
}

async function submitCopy() {
  if (!detail.value || !copyForm.targetPackageId) {
    ElMessage.warning('请选择目标培训包')
    return
  }
  copying.value = true
  try {
    const created = await copyPaper(detail.value.paper.id, copyForm.targetPackageId,
      copyForm.title.trim() || undefined)
    copyVisible.value = false
    packageId.value = created.packageId
    ElMessage.success('已复制为草稿，可在目标培训包中继续编辑')
    await load(created.id)
  }
  finally {
    copying.value = false
  }
}

/** 切换培训包：先丢掉上一套试卷，否则 load() 会沿旧试卷 id 继续读取。 */
async function onPackageChange() {
  detail.value = null
  await load()
}

onMounted(async () => {
  await loadPackages()
  if (packageId.value) {
    await load()
  }
})
</script>

<template>
  <div>
    <header class="page-header">
      <h1 class="page-title">试卷</h1>
      <p class="page-desc">
        试卷按培训包创建：从包内各课程题库抽题，可逐题设分值、设考试时长与及格百分比。
        允许多次考试；发布后题目与分值锁定，改题请复制后重编。
      </p>
    </header>

    <div class="surface-card">
      <div class="toolbar">
        <el-select v-model="packageId" placeholder="选择培训包" style="width: 280px" @change="onPackageChange">
          <el-option v-for="p in packages" :key="p.id" :label="p.title" :value="p.id" />
        </el-select>
        <el-button type="primary" @click="openCreate">新建试卷（组卷）</el-button>
        <el-button :disabled="!detail" @click="load()">刷新</el-button>
      </div>

      <div v-if="!detail" class="empty-hint">
        该培训包还没有试卷。点击「新建试卷」从中抽题组卷。
      </div>

      <template v-else>
        <div class="paper-head">
          <div>
            <span class="paper-title">{{ detail.paper.title }}</span>
            <el-tag size="small" :type="detail.paper.status === 'DRAFT' ? 'info' : 'success'">
              {{ detail.paper.status === 'DRAFT' ? '草稿' : '已发布' }}
            </el-tag>
          </div>
          <div class="head-actions">
            <el-button
              v-if="detail.paper.status === 'DRAFT'"
              type="primary"
              @click="publish"
            >
              发布
            </el-button>
            <el-button
              v-else
              @click="unpublish"
            >
              取消发布
            </el-button>
            <el-button :disabled="!copyTargets.length" @click="openCopy">
              复制到其他培训包
            </el-button>
          </div>
        </div>

        <el-form label-width="110px" class="settings-form">
          <el-form-item label="考试时长(分钟)">
            <el-input-number
              v-model="detail.paper.durationMinutes"
              :min="1"
              :max="600"
              :disabled="detail.paper.status !== 'DRAFT'"
            />
          </el-form-item>
          <el-form-item label="及格百分比(%)">
            <el-input-number
              v-model="detail.paper.passScore"
              :min="1"
              :max="100"
              :disabled="detail.paper.status !== 'DRAFT'"
            />
            <span class="form-hint">{{ passHint }}</span>
          </el-form-item>
          <el-form-item v-if="detail.paper.status === 'DRAFT'" label=" ">
            <el-button @click="saveSettings">保存设置</el-button>
          </el-form-item>
        </el-form>

        <h3 class="section-title">题目（共 {{ questionTotal }} 题，总分 {{ detail.totalScore }}）</h3>
        <el-table v-loading="loading" :data="questions" border size="small">
          <el-table-column prop="sortNo" label="题号" width="70" />
          <el-table-column label="题干" min-width="280" show-overflow-tooltip>
            <template #default="{ row }">{{ row.stem }}</template>
          </el-table-column>
          <el-table-column label="题型" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.type" size="small" :type="row.type === 'SINGLE' ? 'info' : 'warning'">
                {{ row.type === 'SINGLE' ? '单选' : '多选' }}
              </el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="选项数" width="90">
            <template #default="{ row }">{{ row.optionsCount }}</template>
          </el-table-column>
          <el-table-column label="分值" width="160">
            <template #default="{ row }">
              <el-input-number
                v-model="row.score"
                :min="1"
                :max="100"
                size="small"
                :disabled="detail.paper.status !== 'DRAFT'"
              />
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-model:current-page="questionPage"
          v-model:page-size="questionPageSize"
          class="question-pagination"
          :total="questionTotal"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @current-change="onQuestionPageChange"
          @size-change="onQuestionPageChange"
        />
        <div v-if="detail.paper.status === 'DRAFT'" class="score-actions">
          <el-button type="primary" @click="saveScores">保存本页分值</el-button>
          <span class="form-hint">分值按页保存，未显示的页沿用原分值</span>
        </div>
      </template>
    </div>

    <el-dialog v-model="createVisible" title="新建试卷（组卷）" width="640px">
      <p class="dialog-hint">
        选择培训包内课程并填写抽题数量。抽题只取「启用」状态的题目；
        题库不足时会明确告知可用数量。每题默认 1 分，组卷后可再调整。
      </p>
      <el-form label-width="110px">
        <el-form-item label="试卷名称">
          <el-input v-model="form.title" placeholder="例如：2026 年度安全考核" />
        </el-form-item>
        <el-form-item label="考试时长(分钟)">
          <el-input-number v-model="form.durationMinutes" :min="1" :max="600" />
        </el-form-item>
        <el-form-item label="及格百分比(%)">
          <el-input-number v-model="form.passScore" :min="1" :max="100" />
        </el-form-item>
        <el-form-item label="抽题">
          <div class="draw-list">
            <div v-for="(d, idx) in form.draws" :key="d.courseId" class="draw-row">
              <span class="draw-name">{{ d.courseName }}</span>
              <el-input-number v-model="form.draws[idx].count" :min="0" :max="200" size="small" />
              <span class="draw-unit">题</span>
            </div>
            <div v-if="!form.draws.length" class="form-hint">
              该培训包当前生效版本没有课程
            </div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="copyVisible" title="复制试卷到其他培训包" width="560px">
      <p class="dialog-hint">
        一个培训包只能有一套试卷，因此复制需要选择另一个包作为目标。
        新试卷为草稿，题目与分值照抄，但与源试卷完全独立——之后改任一方向都不影响另一份。
      </p>
      <el-form label-width="110px">
        <el-form-item label="目标培训包">
          <el-select v-model="copyForm.targetPackageId" placeholder="选择培训包" style="width: 100%">
            <el-option v-for="p in copyTargets" :key="p.id" :label="p.title" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="试卷名称">
          <el-input v-model="copyForm.title" placeholder="留空则自动命名为「原名（副本）」" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="copyVisible = false">取消</el-button>
        <el-button type="primary" :loading="copying" @click="submitCopy">复制</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 16px;
}

.paper-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 12px;
  border-bottom: 1px solid #ebeef5;
}

.paper-title {
  margin-right: 10px;
  font-size: 16px;
  font-weight: 600;
}

.settings-form {
  margin-top: 16px;
}

.form-hint {
  margin-left: 12px;
  color: #e6a23c;
  font-size: 12px;
}

.section-title {
  margin: 20px 0 10px;
  font-size: 15px;
}

.score-actions {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 12px;
}

.head-actions {
  display: flex;
  gap: 8px;
}

.question-pagination {
  margin-top: 12px;
}

.empty-hint {
  padding: 40px 0;
  color: #909399;
  text-align: center;
}

.draw-list {
  width: 100%;
}

.draw-row {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 8px;
}

.draw-name {
  width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.draw-unit {
  color: #909399;
  font-size: 12px;
}
</style>
