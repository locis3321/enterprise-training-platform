<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  createCompany,
  createEnterprise,
  fetchCompanies,
  fetchEnterprises,
  updateEnterprise,
} from '@/api/tenancy'
import type { Enterprise, EnterpriseForm, InsuranceCompany } from '@/api/tenancy'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const activeTab = ref<'companies' | 'enterprises'>('enterprises')

const companies = ref<InsuranceCompany[]>([])
const enterprises = ref<Enterprise[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
const keyword = ref('')
const loading = ref(false)

const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()
const submitting = ref(false)

const form = reactive<EnterpriseForm>({
  insuranceCompanyId: 0,
  name: '',
  code: '',
  contactName: '',
  contactPhone: '',
  status: 1,
})

const rules: FormRules = {
  insuranceCompanyId: [{ required: true, message: '请选择集团', trigger: 'change' }],
  name: [{ required: true, message: '请输入企业名称', trigger: 'blur' }],
  code: [{ required: true, message: '请输入企业编码', trigger: 'blur' }],
}

async function loadCompanies() {
  const result = await fetchCompanies({ page: 1, pageSize: 200 })
  companies.value = result.items
  if (!form.insuranceCompanyId && auth.insuranceCompanyId) {
    form.insuranceCompanyId = auth.insuranceCompanyId
  }
}

async function loadEnterprises() {
  loading.value = true
  try {
    const result = await fetchEnterprises({
      keyword: keyword.value || undefined,
      page: page.value,
      pageSize: pageSize.value,
    })
    enterprises.value = result.items
    total.value = result.total
  }
  catch {
    enterprises.value = []
    total.value = 0
  }
  finally {
    loading.value = false
  }
}

function openCreate() {
  dialogMode.value = 'create'
  editingId.value = null
  form.insuranceCompanyId = auth.insuranceCompanyId ?? companies.value[0]?.id ?? 0
  form.name = ''
  form.code = ''
  form.contactName = ''
  form.contactPhone = ''
  form.status = 1
  dialogVisible.value = true
}

function openEdit(row: Enterprise) {
  dialogMode.value = 'edit'
  editingId.value = row.id
  form.insuranceCompanyId = row.insuranceCompanyId
  form.name = row.name
  form.code = row.code
  form.contactName = row.contactName ?? ''
  form.contactPhone = row.contactPhone ?? ''
  form.status = row.status
  dialogVisible.value = true
}

async function submit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    if (dialogMode.value === 'create') {
      await createEnterprise({ ...form })
      ElMessage.success('企业已创建')
    }
    else if (editingId.value) {
      await updateEnterprise(editingId.value, { ...form })
      ElMessage.success('企业已更新')
    }
    dialogVisible.value = false
    await loadEnterprises()
  }
  catch {
    // 错误提示由 request 拦截器统一处理
  }
  finally {
    submitting.value = false
  }
}

async function createCompanyQuick() {
  const name = window.prompt('集团名称')
  if (!name) return
  const code = window.prompt('集团编码（字母/数字/下划线）')
  if (!code) return
  try {
    await createCompany({ name, code })
    ElMessage.success('集团已创建')
    await loadCompanies()
  }
  catch {
    // 已统一提示
  }
}

onMounted(async () => {
  try {
    await loadCompanies()
    await loadEnterprises()
  }
  catch {
    // 已统一提示
  }
})
</script>

<template>
  <div>
    <header class="page-header">
      <h1 class="page-title">组织管理</h1>
      <p class="page-desc">
        子公司归属集团，是员工与培训数据的隔离边界。子公司管理员只能维护本子公司信息。
      </p>
    </header>

    <el-tabs v-model="activeTab" class="surface-card">
      <el-tab-pane label="子公司/部门" name="enterprises">
        <div class="toolbar">
          <el-input
            v-model="keyword"
            placeholder="企业名称或编码"
            clearable
            style="width: 240px"
            @keyup.enter="page = 1; loadEnterprises()"
          />
          <el-button type="primary" @click="page = 1; loadEnterprises()">查询</el-button>
          <el-button @click="openCreate">新建企业</el-button>
        </div>

        <el-table v-loading="loading" :data="enterprises" border>
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="name" label="企业名称" min-width="160" />
          <el-table-column prop="code" label="企业编码" width="140" />
          <el-table-column prop="contactName" label="联系人" width="110" />
          <el-table-column prop="contactPhone" label="联系电话" width="140" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
                {{ row.status === 1 ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          class="pagination"
          layout="total, prev, pager, next"
          :total="total"
          @current-change="loadEnterprises"
        />
      </el-tab-pane>

      <el-tab-pane label="集团/公司" name="companies">
        <div class="toolbar">
          <el-button type="primary" @click="createCompanyQuick">新建集团</el-button>
          <span class="toolbar-hint">仅平台超级管理员可新建</span>
        </div>
        <el-table :data="companies" border>
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="name" label="名称" min-width="160" />
          <el-table-column prop="code" label="编码" width="140" />
          <el-table-column prop="contactName" label="联系人" width="110" />
          <el-table-column prop="contactPhone" label="联系电话" width="140" />
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'create' ? '新建企业' : '编辑企业'"
      width="520px"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="集团" prop="insuranceCompanyId">
          <el-select v-model="form.insuranceCompanyId" placeholder="选择集团" style="width: 100%">
            <el-option
              v-for="item in companies"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="企业名称" prop="name">
          <el-input v-model="form.name" maxlength="128" />
        </el-form-item>
        <el-form-item label="企业编码" prop="code">
          <el-input v-model="form.code" maxlength="64" placeholder="导入时按此编码匹配" />
        </el-form-item>
        <el-form-item label="联系人">
          <el-input v-model="form.contactName" maxlength="64" />
        </el-form-item>
        <el-form-item label="联系电话">
          <el-input v-model="form.contactPhone" maxlength="11" />
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
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 16px;
}

.toolbar-hint {
  color: var(--color-text-secondary);
  font-size: 12px;
}

.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
