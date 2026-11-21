<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchCourses } from '@/api/course'
import type { Course } from '@/api/course'
import {
  changeQuestionStatus,
  createQuestion,
  deleteQuestion,
  downloadQuestionTemplate,
  fetchQuestions,
  importQuestions,
  updateQuestion,
} from '@/api/exam'
import type { Question, QuestionForm, QuestionImportResult } from '@/api/exam'

const courses = ref<Course[]>([])
const courseId = ref<number>()
const questions = ref<Question[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
const loading = ref(false)

const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const editingId = ref<number | null>(null)
const submitting = ref(false)
const form = reactive<QuestionForm>({ stem: '', type: 'SINGLE', options: [] })

const importVisible = ref(false)
const importing = ref(false)
const importResult = ref<QuestionImportResult | null>(null)

const canSubmit = computed(() => {
  if (!form.stem.trim())
    return false
  if (form.options.length < 2 || form.options.length > 5)
    return false
  if (form.options.some(o => !o.content.trim()))
    return false
  const correct = form.options.filter(o => o.correct).length
  return form.type === 'SINGLE' ? correct === 1 : correct >= 2
})

async function loadCourses() {
  const result = await fetchCourses({ page: 1, pageSize: 100 })
    .catch(() => ({ items: [] as Course[], total: 0, page: 1, pageSize: 0 }))
  courses.value = result.items
  if (!courseId.value && courses.value.length) {
    courseId.value = courses.value[0].id
  }
}

async function load() {
  if (!courseId.value) {
    questions.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const result = await fetchQuestions(courseId.value, {
      page: page.value,
      pageSize: pageSize.value,
    })
    questions.value = result.items
    total.value = result.total
  }
  finally {
    loading.value = false
  }
}

function resetForm() {
  form.stem = ''
  form.type = 'SINGLE'
  form.options = [
    { content: '', correct: true },
    { content: '', correct: false },
  ]
}

function openCreate() {
  dialogMode.value = 'create'
  editingId.value = null
  resetForm()
  dialogVisible.value = true
}

function openEdit(row: Question) {
  // 被已发布试卷引用的题目禁止编辑，按钮已禁用；这里再兜一层
  if (row.referencedByPublished) {
    ElMessage.warning('该题已被已发布试卷引用，不能修改；可停用后新建')
    return
  }
  dialogMode.value = 'edit'
  editingId.value = row.id
  form.stem = row.stem
  form.type = row.type
  form.options = row.options.map(o => ({ content: o.content, correct: o.correct }))
  dialogVisible.value = true
}

function addOption() {
  if (form.options.length >= 5) {
    ElMessage.warning('最多 5 个选项')
    return
  }
  form.options.push({ content: '', correct: false })
}

function removeOption(index: number) {
  if (form.options.length <= 2) {
    ElMessage.warning('至少需要 2 个选项')
    return
  }
  form.options.splice(index, 1)
}

/** 单选强制互斥：勾选新项时清掉其他正确项。 */
function onCorrectChange(index: number) {
  if (form.type === 'SINGLE') {
    form.options.forEach((o, i) => {
      o.correct = i === index
    })
  }
}

function onTypeChange() {
  if (form.type === 'SINGLE') {
    // 切回单选时只保留第一个正确项
    let kept = false
    form.options.forEach((o) => {
      if (o.correct && !kept) {
        kept = true
      }
      else {
        o.correct = false
      }
    })
  }
}

async function submit() {
  if (!courseId.value)
    return
  submitting.value = true
  try {
    if (dialogMode.value === 'create') {
      await createQuestion(courseId.value, { ...form, options: [...form.options] })
      ElMessage.success('题目已创建')
    }
    else if (editingId.value) {
      await updateQuestion(courseId.value, editingId.value, { ...form, options: [...form.options] })
      ElMessage.success('题目已更新')
    }
    dialogVisible.value = false
    await load()
  }
  finally {
    submitting.value = false
  }
}

async function remove(row: Question) {
  if (row.referencedByPublished) {
    ElMessage.warning('该题已被已发布试卷引用，不能删除；可改为停用')
    return
  }
  try {
    await ElMessageBox.confirm('删除后不可恢复。', '确认删除', { type: 'warning' })
  }
  catch {
    return
  }
  await deleteQuestion(courseId.value!, row.id)
  ElMessage.success('已删除')
  await load()
}

async function toggleStatus(row: Question) {
  const next = row.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
  // 停用不影响已发布试卷的判分（走 attempt 快照），因此被引用时也允许
  await changeQuestionStatus(courseId.value!, row.id, next)
  ElMessage.success(next === 'ACTIVE' ? '已启用' : '已停用，不再被新抽题选中')
  await load()
}

async function downloadTemplate() {
  const response = await downloadQuestionTemplate(courseId.value!)
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = '题库导入模板.xlsx'
  a.click()
  URL.revokeObjectURL(url)
}

async function onFileChange(file: { raw: File }) {
  importing.value = true
  importResult.value = null
  try {
    importResult.value = await importQuestions(courseId.value!, file.raw)
    ElMessage.success(`成功 ${importResult.value.success} 行，失败 ${importResult.value.failed} 行`)
    await load()
  }
  catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '导入失败')
  }
  finally {
    importing.value = false
  }
}

onMounted(async () => {
  await loadCourses()
  await load()
})
</script>

<template>
  <div>
    <header class="page-header">
      <h1 class="page-title">题库</h1>
      <p class="page-desc">
        试题按课程归属。题型只有单选与多选，选项 2–5 个、标签 A–E 自动生成，正确答案必填。
        被已发布试卷引用的题目不能编辑或删除，可停用后新建。
      </p>
    </header>

    <div class="surface-card">
      <div class="toolbar">
        <el-select v-model="courseId" placeholder="选择课程" style="width: 260px" @change="page = 1; load()">
          <el-option v-for="c in courses" :key="c.id" :label="c.title" :value="c.id" />
        </el-select>
        <el-button type="primary" :disabled="!courseId" @click="openCreate">新增题目</el-button>
        <el-button :disabled="!courseId" @click="downloadTemplate">下载模板</el-button>
        <el-button :disabled="!courseId" @click="importVisible = true">Excel 导入</el-button>
      </div>

      <el-table v-loading="loading" :data="questions" border>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="题型" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.type === 'SINGLE' ? 'info' : 'warning'">
              {{ row.type === 'SINGLE' ? '单选' : '多选' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="stem" label="题干" min-width="240" show-overflow-tooltip />
        <el-table-column label="选项数" width="90">
          <template #default="{ row }">{{ row.options.length }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
              {{ row.status === 'ACTIVE' ? '启用' : '已停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="被已发布试卷引用" width="170">
          <template #default="{ row }">
            <el-tag v-if="row.referencedByPublished" size="small" type="warning">已被试卷引用</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220">
          <template #default="{ row }">
            <!-- 编辑不设限：判分用开考时的题目快照，改题库不影响已考完的成绩 -->
            <el-button
              link
              type="primary"
              :title="row.referencedByPublished
                ? '已被试卷引用；修改只影响之后的新考试，已开考/已交卷用快照' : ''"
              @click="openEdit(row)"
            >
              编辑
            </el-button>
            <el-button
              link
              type="warning"
              :disabled="row.referencedByPublished"
              :title="row.referencedByPublished
                ? '已被已发布试卷引用，不能删除；可先取消发布试卷或改为停用' : ''"
              @click="remove(row)"
            >
              删除
            </el-button>
            <el-button link type="info" @click="toggleStatus(row)">
              {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
            </el-button>
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

    <el-dialog v-model="dialogVisible" :title="dialogMode === 'create' ? '新增题目' : '编辑题目'" width="640px">
      <el-form label-width="80px">
        <el-form-item label="题型">
          <el-radio-group v-model="form.type" @change="onTypeChange">
            <el-radio value="SINGLE">单选</el-radio>
            <el-radio value="MULTIPLE">多选</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="题干">
          <el-input v-model="form.stem" type="textarea" :rows="2" placeholder="请输入题干" />
        </el-form-item>
        <el-form-item label="选项">
          <div class="option-list">
            <div v-for="(opt, index) in form.options" :key="index" class="option-row">
              <span class="option-label">{{ String.fromCharCode(65 + index) }}</span>
              <el-input v-model="opt.content" placeholder="选项内容" />
              <el-checkbox
                v-model="opt.correct"
                :label="form.type === 'SINGLE' ? '正确' : '正确'"
                @change="onCorrectChange(index)"
              />
              <el-button link type="danger" @click="removeOption(index)">删除</el-button>
            </div>
            <el-button size="small" :disabled="form.options.length >= 5" @click="addOption">
              添加选项（最多 5 个）
            </el-button>
          </div>
        </el-form-item>
        <el-form-item v-if="!canSubmit" label=" ">
          <span class="form-hint">
            单选需恰好 1 个正确答案；多选至少 2 个；选项 2–5 个且内容必填
          </span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="submit">
          保存
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="importVisible" title="Excel 导入题目" width="640px">
      <p class="dialog-hint">
        列顺序：题目 / 类型 / 选项A–E / 正确答案。类型填 SINGLE 或 MULTIPLE，
        正确答案填 A 或 A,C（逗号分隔）。任一行的错误只影响该行。
      </p>
      <el-upload :auto-upload="false" :show-file-list="false" accept=".xlsx" :on-change="onFileChange">
        <el-button type="primary" :loading="importing">选择 .xlsx 文件</el-button>
      </el-upload>

      <template v-if="importResult">
        <p class="import-summary">
          共 {{ importResult.total }} 行，成功 {{ importResult.success }}，失败 {{ importResult.failed }}
        </p>
        <el-table :data="importResult.rows" border size="small" max-height="320">
          <el-table-column prop="rowNo" label="行号" width="80" />
          <el-table-column prop="stem" label="题目" min-width="200" show-overflow-tooltip />
          <el-table-column label="结果" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.ok ? 'success' : 'danger'">
                {{ row.ok ? '成功' : '失败' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="原因" min-width="200">
            <template #default="{ row }">
              <span class="import-error">{{ row.error || '' }}</span>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <template #footer>
        <el-button @click="importVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
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

.option-list {
  width: 100%;
}

.option-row {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 8px;
}

.option-label {
  width: 20px;
  color: #606266;
}

.form-hint {
  color: #e6a23c;
  font-size: 12px;
}

.import-summary {
  margin: 16px 0 8px;
  color: #303133;
}

.import-error {
  color: #f56c6c;
  font-size: 12px;
}
</style>
