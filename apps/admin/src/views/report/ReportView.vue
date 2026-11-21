<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  exportEnterpriseSummary,
  exportLearner,
  exportTaskLearners,
  exportTaskSummary,
  fetchEnterpriseSummary,
  fetchLearnerReport,
  fetchTaskAssignments,
  fetchTaskSummary,
} from '@/api/report'
import type {
  EnterpriseSummary,
  LearnerReport,
  TaskAssignmentRow,
  TaskSummary,
} from '@/api/report'
import { fetchEmployees } from '@/api/employee'
import type { Employee } from '@/api/employee'
import { useAuthStore } from '@/stores/auth'
import { scopeLabel } from '@/constants/roles'

const auth = useAuthStore()
const activeTab = ref<'enterprise' | 'task' | 'learner'>('enterprise')

// 子公司汇总
const enterpriseRows = ref<EnterpriseSummary[]>([])
const enterpriseTotal = ref(0)
const enterprisePage = ref(1)
const enterpriseKeyword = ref('')
const enterpriseLoading = ref(false)

// 任务汇总
const taskRows = ref<TaskSummary[]>([])
const taskTotal = ref(0)
const taskPage = ref(1)
const taskKeyword = ref('')
const taskLoading = ref(false)
const taskEnterpriseFilter = ref<number | undefined>()

// 逐人明细抽屉
const rosterVisible = ref(false)
const rosterLoading = ref(false)
const rosterRows = ref<TaskAssignmentRow[]>([])
const rosterTask = ref<TaskSummary | null>(null)

// 个人明细
const employees = ref<Employee[]>([])
const learnerId = ref<number | undefined>()
const learner = ref<LearnerReport | null>(null)
const learnerLoading = ref(false)
/** 已展开的培训包（默认全折叠）。 */
const expandedTasks = ref<number[]>([])
/** 已展开章节明细的课程，key 为 `${taskId}-${courseVersionId}`。 */
const expandedCourses = ref<string[]>([])
/** 已展开考试记录的培训包。 */
const expandedExams = ref<string[]>([])

function courseKey(taskId: number, courseVersionId: number) {
  return `${taskId}-${courseVersionId}`
}

function isCourseExpanded(taskId: number, courseVersionId: number) {
  return expandedCourses.value.includes(courseKey(taskId, courseVersionId))
}

function toggleCourse(taskId: number, courseVersionId: number) {
  const key = courseKey(taskId, courseVersionId)
  const index = expandedCourses.value.indexOf(key)
  if (index >= 0) {
    expandedCourses.value.splice(index, 1)
  }
  else {
    expandedCourses.value.push(key)
  }
}

/** 章节数与该课程已完成章节数。 */
function sectionStats(course: LearnerReport['tasks'][number]['courses'][number]) {
  const total = course.sections.length
  const done = course.sections.filter((s) => s.completed).length
  return { total, done }
}

/** 课程应看学时合计（章节 requiredSeconds 之和）。 */
function courseRequiredSeconds(course: LearnerReport['tasks'][number]['courses'][number]) {
  return course.sections.reduce((sum, s) => sum + (s.requiredSeconds || 0), 0)
}

/** 培训包考试摘要：次数 + 最佳成绩。 */
function examSummary(task: LearnerReport['tasks'][number]) {
  const best = task.attempts.find((a) => a.best)
  return {
    count: task.attempts.length,
    bestText: best ? `${best.scoreSum ?? '—'}/${best.totalScore ?? '—'}` : '',
    passed: best ? best.passed : false,
  }
}

/** 培训包汇总行：课程数 · 学时 · 考试结果。 */
function taskSummaryText(task: LearnerReport['tasks'][number]) {
  const parts = [`${task.courses.length} 门课`, `${hours(task.totalSeconds)} h`]
  const exam = examSummary(task)
  if (exam.count === 0) {
    parts.push('无考试记录')
  }
  else {
    parts.push(`考试 ${exam.count} 次 · 最佳 ${exam.bestText} ${exam.passed ? '通过' : '未通过'}`)
  }
  return parts.join(' · ')
}

function statusTagType(status: string) {
  return { COMPLETED: 'success', IN_PROGRESS: 'warning', NOT_STARTED: 'info' }[status] ?? 'info'
}

const pageSize = ref(10)
const exporting = ref(false)

const currentScope = computed(() => scopeLabel(auth.dataScope))

async function loadEnterprises() {
  enterpriseLoading.value = true
  try {
    const result = await fetchEnterpriseSummary({
      keyword: enterpriseKeyword.value || undefined,
      page: enterprisePage.value,
      pageSize: pageSize.value,
    })
    enterpriseRows.value = result.items
    enterpriseTotal.value = result.total
  }
  catch {
    enterpriseRows.value = []
    enterpriseTotal.value = 0
  }
  finally {
    enterpriseLoading.value = false
  }
}

async function loadTasks() {
  taskLoading.value = true
  try {
    const result = await fetchTaskSummary({
      enterpriseId: taskEnterpriseFilter.value,
      keyword: taskKeyword.value || undefined,
      page: taskPage.value,
      pageSize: pageSize.value,
    })
    taskRows.value = result.items
    taskTotal.value = result.total
  }
  catch {
    taskRows.value = []
    taskTotal.value = 0
  }
  finally {
    taskLoading.value = false
  }
}

async function loadEmployees() {
  try {
    const result = await fetchEmployees({ page: 1, pageSize: 200 })
    employees.value = result.items
  }
  catch {
    employees.value = []
  }
}

async function openRoster(row: TaskSummary) {
  rosterTask.value = row
  rosterVisible.value = true
  rosterLoading.value = true
  try {
    rosterRows.value = await fetchTaskAssignments(row.taskId)
  }
  catch {
    rosterRows.value = []
  }
  finally {
    rosterLoading.value = false
  }
}

async function loadLearner() {
  if (!learnerId.value) {
    learner.value = null
    return
  }
  learnerLoading.value = true
  expandedTasks.value = []
  expandedCourses.value = []
  expandedExams.value = []
  try {
    learner.value = await fetchLearnerReport(learnerId.value)
  }
  catch {
    learner.value = null
  }
  finally {
    learnerLoading.value = false
  }
}

/** 从子公司行下钻到任务汇总，带上该子公司筛选。 */
function drillToTasks(row: EnterpriseSummary) {
  taskEnterpriseFilter.value = row.enterpriseId
  taskKeyword.value = ''
  taskPage.value = 1
  activeTab.value = 'task'
  loadTasks()
}

async function doExport(fn: () => Promise<void>) {
  exporting.value = true
  try {
    await fn()
    ElMessage.success('导出已开始')
  }
  catch (error) {
    // 后端超行数或越权时返回具体消息，原样展示，不改写成笼统失败
    ElMessage.error(error instanceof Error ? error.message : '导出失败')
  }
  finally {
    exporting.value = false
  }
}

function statusText(status: string) {
  return { COMPLETED: '已完成', IN_PROGRESS: '进行中', NOT_STARTED: '未开始' }[status] ?? status
}

function hours(seconds: number) {
  return (seconds / 3600).toFixed(1)
}

onMounted(() => {
  loadEnterprises()
  loadTasks()
  loadEmployees()
})
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div>
        <h1 class="page-title">培训报表</h1>
        <p class="page-desc">
          当前数据范围：<strong>{{ currentScope }}</strong>。所有数字来自服务端聚合，
          范围由后端强制裁剪；导出内容与当前筛选一致。
        </p>
      </div>
    </header>

    <el-tabs v-model="activeTab" class="report-tabs">
      <el-tab-pane label="子公司汇总" name="enterprise">
        <el-card shadow="never">
          <div class="toolbar">
            <el-input
              v-model="enterpriseKeyword"
              placeholder="搜索子公司名称"
              clearable
              style="max-width: 240px"
              @keyup.enter="enterprisePage = 1; loadEnterprises()"
              @clear="enterprisePage = 1; loadEnterprises()"
            />
            <el-button type="primary" @click="enterprisePage = 1; loadEnterprises()">查询</el-button>
            <el-button
              :loading="exporting"
              @click="doExport(() => exportEnterpriseSummary({ keyword: enterpriseKeyword || undefined }))"
            >导出 Excel</el-button>
          </div>

          <el-table v-loading="enterpriseLoading" :data="enterpriseRows" style="width: 100%">
            <el-table-column prop="enterpriseName" label="子公司/部门" min-width="140" />
            <el-table-column prop="companyName" label="集团/公司" min-width="130" />
            <el-table-column prop="assignedCount" label="应培人数" width="100" />
            <el-table-column prop="completedCount" label="已完成" width="90" />
            <el-table-column label="完成率" width="90">
              <template #default="{ row }">{{ row.completionRate }}%</template>
            </el-table-column>
            <el-table-column label="有效学时" width="100">
              <template #default="{ row }">{{ row.studyHours }} h</template>
            </el-table-column>
            <el-table-column prop="examTakers" label="考试人数" width="100" />
            <el-table-column prop="examPassed" label="考试通过" width="100" />
            <el-table-column label="通过率" width="90">
              <template #default="{ row }">{{ row.examPassRate }}%</template>
            </el-table-column>
            <el-table-column label="逾期未完" width="100">
              <template #default="{ row }">
                <el-tag v-if="row.overdueCount > 0" size="small" type="danger">{{ row.overdueCount }}</el-tag>
                <span v-else>0</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="100" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="drillToTasks(row)">查看任务</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="当前范围内还没有培训任务数据" />
            </template>
          </el-table>

          <el-pagination
            v-if="enterpriseTotal > 0"
            v-model:current-page="enterprisePage"
            class="pager"
            :page-size="pageSize"
            :total="enterpriseTotal"
            layout="total, prev, pager, next"
            @current-change="loadEnterprises"
          />
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="任务汇总" name="task">
        <el-card shadow="never">
          <div class="toolbar">
            <el-input
              v-model="taskKeyword"
              placeholder="搜索任务标题"
              clearable
              style="max-width: 220px"
              @keyup.enter="taskPage = 1; loadTasks()"
              @clear="taskPage = 1; loadTasks()"
            />
            <el-tag v-if="taskEnterpriseFilter" closable @close="taskEnterpriseFilter = undefined; taskPage = 1; loadTasks()">
              已筛选子公司
            </el-tag>
            <el-button type="primary" @click="taskPage = 1; loadTasks()">查询</el-button>
            <el-button
              :loading="exporting"
              @click="doExport(() => exportTaskSummary({
                enterpriseId: taskEnterpriseFilter,
                keyword: taskKeyword || undefined,
              }))"
            >导出 Excel</el-button>
          </div>

          <el-table v-loading="taskLoading" :data="taskRows" style="width: 100%">
            <el-table-column prop="taskTitle" label="任务标题" min-width="180" />
            <el-table-column prop="enterpriseName" label="子公司/部门" min-width="130" />
            <el-table-column prop="learnDeadlineAt" label="学习截止" min-width="160">
              <template #default="{ row }">{{ row.learnDeadlineAt || '—' }}</template>
            </el-table-column>
            <el-table-column prop="assignedCount" label="下发" width="80" />
            <el-table-column prop="learningCompleted" label="完成" width="80" />
            <el-table-column label="完成率" width="90">
              <template #default="{ row }">{{ row.completionRate }}%</template>
            </el-table-column>
            <el-table-column label="通过率" width="90">
              <template #default="{ row }">{{ row.examPassRate }}%</template>
            </el-table-column>
            <el-table-column label="逾期" width="80">
              <template #default="{ row }">
                <el-tag v-if="row.overdueCount > 0" size="small" type="danger">{{ row.overdueCount }}</el-tag>
                <span v-else>0</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openRoster(row)">逐人明细</el-button>
                <el-button link type="primary" @click="doExport(() => exportTaskLearners(row.taskId))">
                  导出名单
                </el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="当前范围内还没有培训任务" />
            </template>
          </el-table>

          <el-pagination
            v-if="taskTotal > 0"
            v-model:current-page="taskPage"
            class="pager"
            :page-size="pageSize"
            :total="taskTotal"
            layout="total, prev, pager, next"
            @current-change="loadTasks"
          />
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="人员明细" name="learner">
        <el-card shadow="never">
          <div class="toolbar">
            <el-select
              v-model="learnerId"
              placeholder="选择员工"
              filterable
              style="width: 280px"
              @change="loadLearner"
            >
              <el-option
                v-for="e in employees"
                :key="e.id"
                :label="`${e.name}（${e.phoneMasked}）`"
                :value="e.id"
              />
            </el-select>
            <el-button
              v-if="learner"
              :loading="exporting"
              @click="doExport(() => exportLearner(learner!.employeeId))"
            >导出 Excel</el-button>
          </div>

          <el-empty v-if="!learner && !learnerLoading" description="请选择员工查看学习与考试记录" />

          <div v-else v-loading="learnerLoading" class="learner-body">
            <template v-if="learner">
              <div class="learner-head">
                <strong>{{ learner.employeeName }}</strong>
                <span class="muted">{{ learner.phoneMasked }}</span>
                <span class="muted">{{ learner.enterpriseName }}</span>
              </div>

              <el-empty v-if="learner.tasks.length === 0" description="该员工暂无培训任务记录" />

              <el-collapse v-else v-model="expandedTasks" class="task-collapse">
                <el-collapse-item
                  v-for="task in learner.tasks"
                  :key="task.taskId"
                  :name="task.taskId"
                >
                  <template #title>
                    <div class="task-title-row">
                      <span class="task-name">{{ task.taskTitle }}</span>
                      <el-tag size="small" :type="statusTagType(task.assignmentStatus)">
                        {{ statusText(task.assignmentStatus) }}
                      </el-tag>
                      <span class="task-summary">{{ taskSummaryText(task) }}</span>
                    </div>
                  </template>

                  <el-empty
                    v-if="task.courses.length === 0"
                    :image-size="60"
                    description="该培训包暂无课程"
                  />
                  <el-table v-else :data="task.courses" size="small" class="course-table">
                    <el-table-column prop="courseTitle" label="课程" min-width="200" />
                    <el-table-column label="章节" width="110">
                      <template #default="{ row }">
                        {{ sectionStats(row).done }} / {{ sectionStats(row).total }}
                      </template>
                    </el-table-column>
                    <el-table-column label="有效/应看" width="150">
                      <template #default="{ row }">
                        {{ hours(row.totalSeconds) }} / {{ hours(courseRequiredSeconds(row)) }} h
                      </template>
                    </el-table-column>
                    <el-table-column label="操作" width="110">
                      <template #default="{ row }">
                        <el-button
                          link
                          type="primary"
                          @click="toggleCourse(task.taskId, row.courseVersionId)"
                        >
                          {{ isCourseExpanded(task.taskId, row.courseVersionId) ? '收起章节' : '展开章节' }}
                        </el-button>
                      </template>
                    </el-table-column>
                  </el-table>

                  <div
                    v-for="course in task.courses.filter((c) => isCourseExpanded(task.taskId, c.courseVersionId))"
                    :key="'sec-' + course.courseVersionId"
                    class="section-panel"
                  >
                    <div class="section-panel-title">{{ course.courseTitle }} · 章节明细</div>
                    <el-table :data="course.sections" size="small">
                      <el-table-column prop="title" label="章节" min-width="180" />
                      <el-table-column label="状态" width="100">
                        <template #default="{ row }">
                          <el-tag size="small" :type="row.completed ? 'success' : 'info'">
                            {{ row.completed ? '已完成' : '未完成' }}
                          </el-tag>
                        </template>
                      </el-table-column>
                      <el-table-column label="有效/应看" width="150">
                        <template #default="{ row }">
                          {{ hours(row.effectiveSeconds) }} / {{ hours(row.requiredSeconds) }} h
                        </template>
                      </el-table-column>
                      <template #empty>
                        <span class="muted">该课程暂无章节</span>
                      </template>
                    </el-table>
                  </div>

                  <el-collapse v-model="expandedExams" class="exam-collapse">
                    <el-collapse-item :name="'exam-' + task.taskId">
                      <template #title>
                        <span class="exam-title">
                          考试记录（{{ task.attempts.length }} 次<template
                            v-if="examSummary(task).count > 0"
                          >，最佳 {{ examSummary(task).bestText }} {{ examSummary(task).passed ? '通过' : '未通过' }}</template>）
                        </span>
                      </template>
                      <el-empty
                        v-if="task.attempts.length === 0"
                        :image-size="60"
                        description="暂无交卷记录"
                      />
                      <el-table v-else :data="task.attempts" size="small">
                        <el-table-column prop="paperTitle" label="试卷" min-width="160" />
                        <el-table-column label="得分" width="110">
                          <template #default="{ row }">
                            {{ row.scoreSum ?? '—' }} / {{ row.totalScore ?? '—' }}
                          </template>
                        </el-table-column>
                        <el-table-column label="结果" width="100">
                          <template #default="{ row }">
                            <el-tag size="small" :type="row.passed ? 'success' : 'danger'">
                              {{ row.passed ? '通过' : '未通过' }}
                            </el-tag>
                          </template>
                        </el-table-column>
                        <el-table-column prop="submittedAt" label="交卷时间" min-width="160">
                          <template #default="{ row }">{{ row.submittedAt || '—' }}</template>
                        </el-table-column>
                        <el-table-column label="最佳" width="80">
                          <template #default="{ row }">
                            <el-tag v-if="row.best" size="small" type="warning">最佳</el-tag>
                          </template>
                        </el-table-column>
                      </el-table>
                    </el-collapse-item>
                  </el-collapse>
                </el-collapse-item>
              </el-collapse>
            </template>
          </div>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <el-drawer v-model="rosterVisible" :title="`逐人明细 · ${rosterTask?.taskTitle ?? ''}`" size="640px">
      <el-table v-loading="rosterLoading" :data="rosterRows" size="small">
        <el-table-column prop="name" label="姓名" min-width="110" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" type="info">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="有效学时" width="110">
          <template #default="{ row }">{{ hours(row.effectiveSeconds) }} h</template>
        </el-table-column>
        <el-table-column prop="startedAt" label="开始时间" min-width="160">
          <template #default="{ row }">{{ row.startedAt || '—' }}</template>
        </el-table-column>
        <el-table-column prop="completedAt" label="完成时间" min-width="160">
          <template #default="{ row }">{{ row.completedAt || '—' }}</template>
        </el-table-column>
        <template #empty>
          <el-empty description="该任务暂无分配记录" />
        </template>
      </el-table>
      <template #footer>
        <el-button
          :loading="exporting"
          @click="rosterTask && doExport(() => exportTaskLearners(rosterTask!.taskId))"
        >导出本任务名单</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.page-title {
  margin: 0 0 var(--space-2);
  font-size: 18px;
  font-weight: 600;
}

.page-desc {
  margin: 0;
  max-width: 780px;
  color: var(--color-text-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.toolbar {
  display: flex;
  gap: var(--space-3);
  align-items: center;
  margin-bottom: var(--space-4);
  flex-wrap: wrap;
}

.pager {
  margin-top: var(--space-4);
  justify-content: flex-end;
}

.learner-body {
  min-height: 120px;
}

.learner-head {
  display: flex;
  gap: var(--space-3);
  align-items: baseline;
  margin-bottom: var(--space-4);
}

.task-collapse {
  border-top: none;
}

.task-title-row {
  display: flex;
  gap: var(--space-3);
  align-items: center;
  min-width: 0;
  padding-right: var(--space-3);
}

.task-name {
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-summary {
  margin-left: auto;
  color: var(--color-text-secondary);
  font-size: 12px;
  white-space: nowrap;
}

.course-table {
  margin-bottom: var(--space-3);
}

.section-panel {
  margin: 0 0 var(--space-3) var(--space-4);
  padding-left: var(--space-3);
  border-left: 2px solid var(--color-border);
}

.section-panel-title {
  margin-bottom: var(--space-2);
  font-size: 12px;
  color: var(--color-text-secondary);
}

.exam-collapse {
  border-top: 1px solid var(--color-border);
}

.exam-title {
  font-size: 13px;
  color: var(--color-text-secondary);
}

.muted {
  color: var(--color-text-secondary);
  font-size: 12px;
}
</style>
