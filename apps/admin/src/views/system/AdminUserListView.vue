<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  createAdminUser,
  deleteAdminUser,
  fetchAdminUsers,
  resetAdminPassword,
  updateAdminUser,
} from '@/api/adminUser'
import type { AdminUser } from '@/api/adminUser'
import { fetchCompanies, fetchEnterprises } from '@/api/tenancy'
import type { Enterprise, InsuranceCompany } from '@/api/tenancy'
import { useAuthStore } from '@/stores/auth'
import { ROLE_LABELS, roleLabel, scopeLabel } from '@/constants/roles'

const auth = useAuthStore()

const rows = ref<AdminUser[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
const keyword = ref('')
const loading = ref(false)

const companies = ref<InsuranceCompany[]>([])
const enterprises = ref<Enterprise[]>([])

const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()
const submitting = ref(false)

const form = reactive({
  username: '',
  password: '',
  displayName: '',
  roleCode: 'ENTERPRISE_ADMIN',
  insuranceCompanyId: undefined as number | undefined,
  enterpriseId: undefined as number | undefined,
  status: 1,
})

const roleOptions = computed(() => {
  const entries = Object.entries(ROLE_LABELS)
  if (auth.roleCode === 'SUPER_ADMIN') {
    return entries
  }
  if (auth.roleCode === 'INSURANCE_ADMIN') {
    return entries.filter(([code]) => code !== 'SUPER_ADMIN')
  }
  return entries.filter(([code]) => code === 'ENTERPRISE_ADMIN')
})

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入初始密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' },
  ],
  displayName: [{ required: true, message: '请输入显示名', trigger: 'blur' }],
  roleCode: [{ required: true, message: '请选择角色', trigger: 'change' }],
}

const needsCompany = computed(() => form.roleCode !== 'SUPER_ADMIN')
const needsEnterprise = computed(() => form.roleCode === 'ENTERPRISE_ADMIN')

const visibleEnterprises = computed(() => {
  if (!form.insuranceCompanyId) {
    return enterprises.value
  }
  return enterprises.value.filter((e) => e.insuranceCompanyId === form.insuranceCompanyId)
})

async function loadRows() {
  loading.value = true
  try {
    const result = await fetchAdminUsers({
      keyword: keyword.value || undefined,
      page: page.value,
      pageSize: pageSize.value,
    })
    rows.value = result.items
    total.value = result.total
  }
  catch {
    rows.value = []
    total.value = 0
  }
  finally {
    loading.value = false
  }
}

async function loadOptions() {
  if (auth.roleCode === 'ENTERPRISE_ADMIN') {
    return
  }
  try {
    const companyPage = await fetchCompanies({ page: 1, pageSize: 200 })
    companies.value = companyPage.items
  }
  catch {
    companies.value = []
  }
  try {
    const enterprisePage = await fetchEnterprises({ page: 1, pageSize: 200 })
    enterprises.value = enterprisePage.items
  }
  catch {
    enterprises.value = []
  }
}

function resetForm() {
  form.username = ''
  form.password = ''
  form.displayName = ''
  form.roleCode = 'ENTERPRISE_ADMIN'
  form.insuranceCompanyId = auth.insuranceCompanyId ?? undefined
  form.enterpriseId = auth.enterpriseId ?? undefined
  form.status = 1
}

function openCreate() {
  dialogMode.value = 'create'
  editingId.value = null
  resetForm()
  dialogVisible.value = true
}

function openEdit(row: AdminUser) {
  dialogMode.value = 'edit'
  editingId.value = row.id
  form.username = row.username
  form.password = ''
  form.displayName = row.displayName
  form.roleCode = row.roleCode
  form.insuranceCompanyId = row.insuranceCompanyId ?? undefined
  form.enterpriseId = row.enterpriseId ?? undefined
  form.status = row.status
  dialogVisible.value = true
}

function onCompanyChange() {
  form.enterpriseId = undefined
}

async function submit() {
  if (!formRef.value) {
    return
  }
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  if (needsCompany.value && !form.insuranceCompanyId) {
    ElMessage.warning('请选择所属集团')
    return
  }
  if (needsEnterprise.value && !form.enterpriseId) {
    ElMessage.warning('请选择所属子公司')
    return
  }
  submitting.value = true
  try {
    if (dialogMode.value === 'create') {
      await createAdminUser({
        username: form.username,
        password: form.password,
        displayName: form.displayName,
        roleCode: form.roleCode,
        insuranceCompanyId: needsCompany.value ? form.insuranceCompanyId ?? null : null,
        enterpriseId: needsEnterprise.value ? form.enterpriseId ?? null : null,
      })
      ElMessage.success('账号已创建')
    }
    else if (editingId.value != null) {
      await updateAdminUser(editingId.value, {
        displayName: form.displayName,
        roleCode: form.roleCode,
        insuranceCompanyId: needsCompany.value ? form.insuranceCompanyId ?? null : null,
        enterpriseId: needsEnterprise.value ? form.enterpriseId ?? null : null,
        status: form.status,
      })
      ElMessage.success('账号已更新')
    }
    dialogVisible.value = false
    await loadRows()
  }
  catch {
    // 错误消息已由请求拦截器统一提示，此处不回滚表单以便用户修正
  }
  finally {
    submitting.value = false
  }
}

async function onResetPassword(row: AdminUser) {
  const input = await ElMessageBox.prompt(
    `为「${row.displayName}」设置新密码。该密码仅本次显示，请自行转达。`,
    '重置密码',
    {
      inputType: 'password',
      inputPlaceholder: '至少 6 位',
      inputValidator: (value: string) => (value && value.length >= 6) || '密码至少 6 位',
      confirmButtonText: '确认重置',
      cancelButtonText: '取消',
    },
  ).catch(() => null)
  if (!input) {
    return
  }
  try {
    await resetAdminPassword(row.id, input.value)
    ElMessage.success('密码已重置，请把新密码交给该用户')
  }
  catch {
    // 拦截器已提示
  }
}

async function onDelete(row: AdminUser) {
  const confirmed = await ElMessageBox.confirm(
    `确认删除账号「${row.displayName}（${row.username}）」？删除后该用户名仍会被占用，不能重新注册同名账号。`,
    '删除账号',
    { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' },
  ).catch(() => false)
  if (!confirmed) {
    return
  }
  try {
    await deleteAdminUser(row.id)
    ElMessage.success('账号已删除')
    await loadRows()
  }
  catch {
    // 拦截器已提示
  }
}

function scopeOfRow(row: AdminUser): string {
  if (row.roleCode === 'SUPER_ADMIN') {
    return scopeLabel('ALL')
  }
  if (row.roleCode === 'INSURANCE_ADMIN') {
    return scopeLabel('INSURANCE_COMPANY')
  }
  return scopeLabel('ENTERPRISE')
}

function onSearch() {
  page.value = 1
  loadRows()
}

onMounted(() => {
  loadRows()
  loadOptions()
})
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div>
        <h1 class="page-title">账号管理</h1>
        <p class="page-desc">
          权限逐级自治：平台管理员管全部，集团管理员管本集团，子公司管理员只管本子公司。
          数据范围由后端强制校验，界面仅按角色隐藏入口。
        </p>
      </div>
      <el-button type="primary" @click="openCreate">新建账号</el-button>
    </header>

    <el-card shadow="never" class="filter-card">
      <el-input
        v-model="keyword"
        placeholder="搜索用户名或显示名"
        clearable
        style="max-width: 260px"
        @keyup.enter="onSearch"
        @clear="onSearch"
      />
      <el-button type="primary" @click="onSearch">搜索</el-button>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" element-loading-text="加载中" style="width: 100%">
        <el-table-column prop="username" label="用户名" min-width="140" />
        <el-table-column prop="displayName" label="显示名" min-width="120" />
        <el-table-column label="角色" min-width="120">
          <template #default="{ row }">
            <el-tag size="small" type="info">{{ roleLabel(row.roleCode) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="数据范围" min-width="110">
          <template #default="{ row }">{{ scopeOfRow(row) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastLoginAt" label="最后登录" min-width="170">
          <template #default="{ row }">{{ row.lastLoginAt || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="primary" @click="onResetPassword(row)">重置密码</el-button>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="当前范围内还没有账号" />
        </template>
      </el-table>

      <el-pagination
        v-if="total > 0"
        v-model:current-page="page"
        v-model:page-size="pageSize"
        class="pager"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        @current-change="loadRows"
        @size-change="onSearch"
      />
    </el-card>

    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'create' ? '新建账号' : '编辑账号'"
      width="520px"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="96px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" :disabled="dialogMode === 'edit'" placeholder="登录用户名" />
        </el-form-item>
        <el-form-item v-if="dialogMode === 'create'" label="初始密码" prop="password">
          <el-input v-model="form.password" type="password" show-password placeholder="至少 6 位" />
        </el-form-item>
        <el-form-item label="显示名" prop="displayName">
          <el-input v-model="form.displayName" placeholder="界面展示名称" />
        </el-form-item>
        <el-form-item label="角色" prop="roleCode">
          <el-select v-model="form.roleCode" style="width: 100%">
            <el-option v-for="[code, label] in roleOptions" :key="code" :label="label" :value="code" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="needsCompany" label="所属集团">
          <el-select
            v-model="form.insuranceCompanyId"
            placeholder="选择集团"
            filterable
            style="width: 100%"
            :disabled="auth.roleCode !== 'SUPER_ADMIN'"
            @change="onCompanyChange"
          >
            <el-option v-for="c in companies" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="needsEnterprise" label="所属子公司">
          <el-select
            v-model="form.enterpriseId"
            placeholder="选择子公司/部门"
            filterable
            style="width: 100%"
            :disabled="auth.roleCode === 'ENTERPRISE_ADMIN'"
          >
            <el-option v-for="e in visibleEnterprises" :key="e.id" :label="e.name" :value="e.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="dialogMode === 'edit'" label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
          <span class="hint">停用后该账号立即无法登录</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
}

.page-title {
  margin: 0 0 var(--space-2);
  font-size: 18px;
  font-weight: 600;
}

.page-desc {
  margin: 0;
  max-width: 720px;
  color: var(--color-text-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.filter-card :deep(.el-card__body) {
  display: flex;
  gap: var(--space-3);
  align-items: center;
}

.pager {
  margin-top: var(--space-4);
  justify-content: flex-end;
}

.hint {
  margin-left: var(--space-3);
  color: var(--color-text-secondary);
  font-size: 12px;
}
</style>
