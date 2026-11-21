<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createPackage,
  fetchPackages,
  offlinePackage,
  onlinePackage,
} from '@/api/training'
import type { TrainingPackage } from '@/api/training'
import { fetchCompanies } from '@/api/tenancy'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()
const packages = ref<TrainingPackage[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
const keyword = ref('')
const statusFilter = ref<string | undefined>()
const loading = ref(false)

/** 超管可跨公司建包，必须显式指定归属公司；其他角色由后端固定为本公司。 */
const isSuperAdmin = computed(() => auth.dataScope === 'ALL')
const companies = ref<{ id: number, name: string }[]>([])
const createVisible = ref(false)
const createForm = ref({ title: '', insuranceCompanyId: undefined as number | undefined })

const statusMeta: Record<string, { label: string, type: 'success' | 'info' | 'warning' }> = {
  DRAFT: { label: '草稿', type: 'info' },
  PUBLISHED: { label: '已发布', type: 'success' },
  OFFLINE: { label: '已下线', type: 'warning' },
}

async function load() {
  loading.value = true
  try {
    const result = await fetchPackages({
      keyword: keyword.value || undefined,
      status: statusFilter.value,
      page: page.value,
      pageSize: pageSize.value,
    })
    packages.value = result.items
    total.value = result.total
  }
  catch {
    packages.value = []
    total.value = 0
  }
  finally {
    loading.value = false
  }
}

async function openCreate() {
  createForm.value = { title: '', insuranceCompanyId: undefined }
  createVisible.value = true
  if (isSuperAdmin.value && !companies.value.length) {
    try {
      const result = await fetchCompanies({ page: 1, pageSize: 200 })
      companies.value = result.items.map(c => ({ id: c.id, name: c.name }))
    }
    catch {
      companies.value = []
    }
  }
}

async function submitCreate() {
  if (!createForm.value.title.trim()) {
    ElMessage.warning('请输入培训包名称')
    return
  }
  if (isSuperAdmin.value && !createForm.value.insuranceCompanyId) {
    ElMessage.warning('超级管理员需指定归属集团')
    return
  }
  try {
    const pkg = await createPackage(createForm.value.title.trim(), undefined,
      isSuperAdmin.value ? createForm.value.insuranceCompanyId : undefined)
    ElMessage.success('培训包已创建，已自动生成 v1 草稿')
    createVisible.value = false
    await router.push(`/training/packages/${pkg.id}`)
  }
  catch {
    // 已由拦截器提示
  }
}

async function toggleOffline(row: TrainingPackage) {
  const action = row.status === 'PUBLISHED' ? '下线' : '恢复上线'
  try {
    await ElMessageBox.confirm(
      row.status === 'PUBLISHED'
        ? '下线后不能再用于新建任务；已发布版本与已下发的任务不受影响。'
        : '恢复上线后可再次用于新建任务。',
      `确认${action}`,
      { type: 'warning' },
    )
    if (row.status === 'PUBLISHED') {
      await offlinePackage(row.id)
    }
    else {
      await onlinePackage(row.id)
    }
    ElMessage.success(`${action}成功`)
    await load()
  }
  catch {
    // 取消或已提示
  }
}

onMounted(load)
</script>

<template>
  <div>
    <header class="page-header">
      <h1 class="page-title">培训包</h1>
      <p class="page-desc">
        培训包按集团共享；发布后版本不可修改，任务绑定具体版本。下线只影响新建任务，不影响已下发培训。
      </p>
    </header>

    <div class="surface-card">
      <div class="toolbar">
        <el-input
          v-model="keyword"
          placeholder="培训包名称"
          clearable
          style="width: 240px"
          @keyup.enter="page = 1; load()"
        />
        <el-select v-model="statusFilter" placeholder="状态" clearable style="width: 130px">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已发布" value="PUBLISHED" />
          <el-option label="已下线" value="OFFLINE" />
        </el-select>
        <el-button type="primary" @click="page = 1; load()">查询</el-button>
        <el-button @click="openCreate">新建培训包</el-button>
        <el-button @click="router.push('/training/tasks')">培训任务</el-button>
      </div>

      <el-table v-loading="loading" :data="packages" border>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="title" label="培训包名称" min-width="220" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusMeta[row.status]?.type ?? 'info'" size="small">
              {{ statusMeta[row.status]?.label ?? row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="latestVersionNo" label="最新版本" width="100" />
        <el-table-column label="生效版本" width="100">
          <template #default="{ row }">
            {{ row.publishedVersionNo ? `v${row.publishedVersionNo}` : '—' }}
          </template>
        </el-table-column>
        <el-table-column label="归属" width="120">
          <template #default="{ row }">
            {{ row.ownerEnterpriseId ? `企业 ${row.ownerEnterpriseId}` : '公司级' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button link type="primary" @click="router.push(`/training/packages/${row.id}`)">编辑</el-button>
            <el-button
              v-if="row.publishedVersionNo"
              link
              type="warning"
              @click="toggleOffline(row)"
            >
              {{ row.status === 'PUBLISHED' ? '下线' : '上线' }}
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

    <el-dialog v-model="createVisible" title="新建培训包" width="480px">
      <el-form label-width="110px">
        <el-form-item v-if="isSuperAdmin" label="归属集团" required>
          <el-select v-model="createForm.insuranceCompanyId" placeholder="选择集团" filterable style="width: 100%">
            <el-option v-for="c in companies" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="培训包名称" required>
          <el-input v-model="createForm.title" maxlength="200" show-word-limit
                    placeholder="例如：2026 年新员工入职培训" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">创建</el-button>
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
</style>
