<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  createEmployee,
  downloadTemplate,
  fetchEmployees,
  importEmployees,
  resetEmployeePassword,
  updateEmployee,
} from '@/api/employee'
import type { Employee, ImportResult } from '@/api/employee'
import { fetchEnterprises } from '@/api/tenancy'
import type { Enterprise } from '@/api/tenancy'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const employees = ref<Employee[]>([])
const enterprises = ref<Enterprise[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
const keyword = ref('')
const statusFilter = ref<number | undefined>()
const enterpriseFilter = ref<number | undefined>()
const loading = ref(false)

const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()
const submitting = ref(false)

const form = reactive({
  enterpriseId: 0,
  name: '',
  phone: '',
  idCardNo: '',
  status: 1,
})

const rules: FormRules = {
  enterpriseId: [{ required: true, message: '请选择企业', trigger: 'change' }],
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' },
  ],
}

const importVisible = ref(false)
const importing = ref(false)
const importResult = ref<ImportResult | null>(null)
const selectedFile = ref<File | null>(null)

const canSeeAllEnterprises = computed(() => auth.dataScope !== 'ENTERPRISE')

async function loadEnterprises() {
  try {
    const result = await fetchEnterprises({ page: 1, pageSize: 200 })
    enterprises.value = result.items
  }
  catch {
    enterprises.value = []
  }
}

async function loadEmployees() {
  loading.value = true
  try {
    const result = await fetchEmployees({
      keyword: keyword.value || undefined,
      status: statusFilter.value,
      enterpriseId: enterpriseFilter.value,
      page: page.value,
      pageSize: pageSize.value,
    })
    employees.value = result.items
    total.value = result.total
  }
  catch {
    employees.value = []
    total.value = 0
  }
  finally {
    loading.value = false
  }
}

function openCreate() {
  dialogMode.value = 'create'
  editingId.value = null
  form.enterpriseId = enterpriseFilter.value ?? enterprises.value[0]?.id ?? 0
  form.name = ''
  form.phone = ''
  form.idCardNo = ''
  form.status = 1
  dialogVisible.value = true
}

function openEdit(row: Employee) {
  dialogMode.value = 'edit'
  editingId.value = row.id
  form.enterpriseId = row.enterpriseId
  form.name = row.name
  // 列表返回脱敏手机号，编辑时必须重新输入，避免把掩码写回数据库
  form.phone = ''
  form.idCardNo = ''
  form.status = row.status
  dialogVisible.value = true
}

/**
 * 重置学员密码。
 *
 * 账号按手机号全局唯一，重置会影响该学员在**所有企业**的登录；
 * 必须先在确认文案里说明，再用后端返回的 affectedEnterpriseCount 复核。
 */
async function resetPassword(row: Employee) {
  try {
    await ElMessageBox.confirm(
      `将把「${row.name}」的登录密码重置为初始密码（anzhi + 手机号后 6 位）。\n`
      + '该账号按手机号全局唯一，若其关联多个企业，重置会同时影响其在所有企业的登录。',
      '确认重置密码',
      { type: 'warning', confirmButtonText: '确认重置' },
    )
  }
  catch {
    return
  }
  try {
    const result = await resetEmployeePassword(row.id)
    const scope = result.affectedEnterpriseCount > 1
      ? `已重置，影响该账号在 ${result.affectedEnterpriseCount} 家企业的登录`
      : '已重置为初始密码'
    ElMessage.success(scope)
  }
  catch {
    // 错误由 request 拦截器提示
  }
}

async function submit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    if (dialogMode.value === 'create') {
      await createEmployee({ ...form, idCardNo: form.idCardNo || undefined })
      ElMessage.success('员工已创建')
    }
    else if (editingId.value) {
      await updateEmployee(editingId.value, { ...form, idCardNo: form.idCardNo || undefined })
      ElMessage.success('员工已更新')
    }
    dialogVisible.value = false
    await loadEmployees()
  }
  catch {
    // 统一提示
  }
  finally {
    submitting.value = false
  }
}

function openImport() {
  selectedFile.value = null
  importResult.value = null
  importVisible.value = true
}

function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  selectedFile.value = input.files?.[0] ?? null
}

async function doImport() {
  if (!selectedFile.value) {
    ElMessage.warning('请先选择 .xlsx 文件')
    return
  }
  importing.value = true
  try {
    importResult.value = await importEmployees(selectedFile.value)
    ElMessage.success(`导入完成：成功 ${importResult.value.success} 行，失败 ${importResult.value.failed} 行`)
    await loadEmployees()
  }
  catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '导入失败')
  }
  finally {
    importing.value = false
  }
}

async function downloadTpl() {
  try {
    await downloadTemplate()
  }
  catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '模板下载失败')
  }
}

onMounted(async () => {
  await loadEnterprises()
  await loadEmployees()
})
</script>

<template>
  <div>
    <header class="page-header">
      <h1 class="page-title">员工档案</h1>
      <p class="page-desc">
        手机号在同一子公司内唯一；员工由子公司管理员或集团管理员维护，学员端只能通过微信手机号绑定访问自己的档案。
      </p>
    </header>

    <div class="surface-card">
      <div class="toolbar">
        <el-input
          v-model="keyword"
          placeholder="姓名或手机号"
          clearable
          style="width: 220px"
          @keyup.enter="page = 1; loadEmployees()"
        />
        <el-select
          v-if="canSeeAllEnterprises"
          v-model="enterpriseFilter"
          placeholder="所属企业"
          clearable
          style="width: 180px"
        >
          <el-option v-for="e in enterprises" :key="e.id" :label="e.name" :value="e.id" />
        </el-select>
        <el-select v-model="statusFilter" placeholder="状态" clearable style="width: 120px">
          <el-option label="启用" :value="1" />
          <el-option label="停用" :value="0" />
        </el-select>
        <el-button type="primary" @click="page = 1; loadEmployees()">查询</el-button>
        <el-button @click="openCreate">新增员工</el-button>
        <el-button @click="openImport">批量导入</el-button>
      </div>

      <el-table v-loading="loading" :data="employees" border>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="姓名" width="120" />
        <el-table-column prop="phoneMasked" label="手机号" width="150" />
        <el-table-column prop="enterpriseId" label="企业ID" width="100" />
        <el-table-column label="身份证" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.idCardProvided" size="small" effect="plain">已录入</el-tag>
            <span v-else class="muted">未录入</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="warning" @click="resetPassword(row)">重置密码</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        class="pagination"
        layout="total, prev, pager, next"
        :total="total"
        @current-change="loadEmployees"
      />
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'create' ? '新增员工' : '编辑员工'"
      width="520px"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="所属企业" prop="enterpriseId">
          <el-select v-model="form.enterpriseId" placeholder="选择企业" style="width: 100%">
            <el-option v-for="e in enterprises" :key="e.id" :label="`${e.name}（${e.code}）`" :value="e.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="姓名" prop="name">
          <el-input v-model="form.name" maxlength="64" />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input
            v-model="form.phone"
            maxlength="11"
            :placeholder="dialogMode === 'edit' ? '编辑时需重新输入完整手机号' : '11 位手机号'"
          />
        </el-form-item>
        <el-form-item label="身份证号">
          <el-input v-model="form.idCardNo" maxlength="18" placeholder="可选，仅服务端保存" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="importVisible" title="批量导入员工" width="640px">
      <div class="import-bar">
        <el-button @click="downloadTpl">下载模板</el-button>
        <input type="file" accept=".xlsx" @change="onFileChange" />
        <el-button type="primary" :loading="importing" @click="doImport">开始导入</el-button>
      </div>
      <p class="import-hint">
        模板列：姓名、手机号、企业编码、身份证号（可选）。手机号在同一企业内唯一，未知企业编码的行会被标记失败。
      </p>

      <template v-if="importResult">
        <el-alert
          :type="importResult.failed > 0 ? 'warning' : 'success'"
          :closable="false"
          show-icon
          :title="`共 ${importResult.total} 行，成功 ${importResult.success} 行，失败 ${importResult.failed} 行`"
        />
        <el-table :data="importResult.rows" border size="small" class="import-table">
          <el-table-column prop="rowNo" label="行号" width="70" />
          <el-table-column prop="name" label="姓名" width="110" />
          <el-table-column prop="phone" label="手机号" width="130" />
          <el-table-column label="结果" width="90">
            <template #default="{ row }">
              <el-tag :type="row.ok ? 'success' : 'danger'" size="small">
                {{ row.ok ? row.action : '失败' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="error" label="原因" min-width="200" />
        </el-table>
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

.muted {
  color: var(--color-text-secondary);
}

.import-bar {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 12px;
}

.import-hint {
  margin: 0 0 12px;
  color: var(--color-text-secondary);
  font-size: 12px;
}

.import-table {
  margin-top: 12px;
}
</style>
