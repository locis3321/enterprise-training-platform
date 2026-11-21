<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { changeAdminSelfPassword, updateAdminSelfProfile } from '@/api/adminUser'
import { useAuthStore } from '@/stores/auth'
import { roleLabel, scopeLabel as scopeText } from '@/constants/roles'
import {
  DataAnalysis,
  Document,
  EditPen,
  HomeFilled,
  OfficeBuilding,
  Setting,
  Tickets,
  User,
} from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

/** 菜单按角色展示；权限仍以后端校验为准，此处仅控制可见性。 */
const canManageOrganization = computed(() => auth.roleCode !== 'ENTERPRISE_ADMIN')

const menuGroups = computed(() => [
  {
    title: '总览',
    items: [{ path: '/', label: '工作台', icon: HomeFilled, enabled: true }],
  },
  {
    title: '组织与人员',
    items: [
      { path: '/enterprises', label: '组织管理', icon: OfficeBuilding, enabled: canManageOrganization.value },
      { path: '/employees', label: '员工档案', icon: User, enabled: true },
    ],
  },
  {
    title: '培训内容',
    items: [
      { path: '/courses', label: '课程与资源', icon: Document, enabled: true },
      { path: '/training/packages', label: '培训包与任务', icon: Tickets, enabled: true },
    ],
  },
  {
    title: '考核与统计',
    items: [
      { path: '/exams/questions', label: '题库', icon: EditPen, enabled: true },
      { path: '/exams/papers', label: '试卷', icon: EditPen, enabled: true },
      { path: '/reports', label: '培训报表', icon: DataAnalysis, enabled: true },
    ],
  },
  {
    title: '系统',
    items: [
      { path: '/system/users', label: '账号管理', icon: User, enabled: true },
      { path: '/settings', label: '系统设置', icon: Setting, enabled: auth.roleCode === 'SUPER_ADMIN' },
    ],
  },
])

const currentScopeLabel = computed(() => scopeText(auth.dataScope))

const profileVisible = ref(false)
const profileTab = ref<'profile' | 'password'>('profile')
const profileFormRef = ref<FormInstance>()
const saving = ref(false)
const profileForm = reactive({ displayName: '' })
const passwordForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

const passwordRules: FormRules = {
  oldPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (value !== passwordForm.newPassword) {
          callback(new Error('两次输入的新密码不一致'))
          return
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
}

function openProfile() {
  profileTab.value = 'profile'
  profileForm.displayName = auth.displayName
  passwordForm.oldPassword = ''
  passwordForm.newPassword = ''
  passwordForm.confirmPassword = ''
  profileVisible.value = true
}

async function saveProfile() {
  if (!profileForm.displayName.trim()) {
    ElMessage.warning('显示名不能为空')
    return
  }
  saving.value = true
  try {
    const me = await updateAdminSelfProfile(profileForm.displayName.trim())
    auth.displayName = me.displayName
    ElMessage.success('显示名已更新')
    profileVisible.value = false
  }
  catch {
    // 拦截器已提示
  }
  finally {
    saving.value = false
  }
}

async function savePassword() {
  if (!profileFormRef.value) {
    return
  }
  const valid = await profileFormRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  saving.value = true
  try {
    await changeAdminSelfPassword(passwordForm.oldPassword, passwordForm.newPassword)
    ElMessage.success('密码已修改，请使用新密码重新登录')
    profileVisible.value = false
    auth.clear()
    router.replace('/login')
  }
  catch {
    // 拦截器已提示
  }
  finally {
    saving.value = false
  }
}

function handleLogout() {
  auth.clear()
  router.replace('/login')
}
</script>

<template>
  <el-container class="layout">
    <el-aside class="layout-aside" width="var(--layout-aside-width)">
      <div class="brand">
        <span class="brand-mark" aria-hidden="true">训</span>
        <div class="brand-text">
          <span class="brand-name">企业内训平台</span>
          <span class="brand-sub">管理后台</span>
        </div>
      </div>

      <el-scrollbar>
        <el-menu :default-active="route.path" class="layout-menu" router>
          <template v-for="group in menuGroups" :key="group.title">
            <div class="menu-group-title">{{ group.title }}</div>
            <el-menu-item
              v-for="item in group.items"
              :key="item.path"
              :index="item.path"
              :disabled="!item.enabled"
            >
              <el-icon><component :is="item.icon" /></el-icon>
              <span>{{ item.label }}</span>
              <span v-if="!item.enabled" class="menu-tag">待开发</span>
            </el-menu-item>
          </template>
        </el-menu>
      </el-scrollbar>
    </el-aside>

    <el-container>
      <el-header class="layout-header">
        <div class="header-left">
          <span class="header-title">{{ route.meta.title ?? '工作台' }}</span>
          <el-tag size="small" type="info" effect="plain">{{ roleLabel(auth.roleCode) }}</el-tag>
        </div>
        <div class="header-right">
          <span class="env-badge">{{ currentScopeLabel }}</span>
          <el-button link type="primary" class="profile-entry" @click="openProfile">
            {{ auth.displayName || '未登录' }}
          </el-button>
          <el-button link type="primary" @click="handleLogout">退出登录</el-button>
        </div>
      </el-header>

      <el-main class="layout-main">
        <router-view />
      </el-main>

      <el-dialog v-model="profileVisible" title="个人设置" width="460px">
        <el-tabs v-model="profileTab">
          <el-tab-pane label="修改显示名" name="profile">
            <el-form label-width="88px" @submit.prevent>
              <el-form-item label="显示名">
                <el-input v-model="profileForm.displayName" placeholder="界面展示名称" />
              </el-form-item>
            </el-form>
          </el-tab-pane>
          <el-tab-pane label="修改密码" name="password">
            <el-form ref="profileFormRef" :model="passwordForm" :rules="passwordRules" label-width="88px">
              <el-form-item label="当前密码" prop="oldPassword">
                <el-input v-model="passwordForm.oldPassword" type="password" show-password />
              </el-form-item>
              <el-form-item label="新密码" prop="newPassword">
                <el-input v-model="passwordForm.newPassword" type="password" show-password placeholder="至少 6 位" />
              </el-form-item>
              <el-form-item label="确认密码" prop="confirmPassword">
                <el-input v-model="passwordForm.confirmPassword" type="password" show-password />
              </el-form-item>
            </el-form>
          </el-tab-pane>
        </el-tabs>
        <template #footer>
          <el-button @click="profileVisible = false">取消</el-button>
          <el-button
            v-if="profileTab === 'profile'"
            type="primary"
            :loading="saving"
            @click="saveProfile"
          >保存</el-button>
          <el-button v-else type="primary" :loading="saving" @click="savePassword">修改密码</el-button>
        </template>
      </el-dialog>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100%;
}

.layout-aside {
  display: flex;
  flex-direction: column;
  background: var(--color-bg-surface);
  border-right: 1px solid var(--color-border);
}

.brand {
  display: flex;
  gap: var(--space-3);
  align-items: center;
  height: var(--layout-header-height);
  padding: 0 var(--space-4);
  border-bottom: 1px solid var(--color-border);
}

.brand-mark {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: var(--radius-sm);
  background: var(--color-primary);
  color: var(--color-text-inverse);
  font-size: 14px;
  font-weight: 600;
}

.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}

.brand-name {
  font-size: 14px;
  font-weight: 600;
}

.brand-sub {
  color: var(--color-text-secondary);
  font-size: 11px;
}

.layout-menu {
  border-right: none;
  padding-bottom: var(--space-5);
}

.menu-group-title {
  padding: var(--space-4) var(--space-4) var(--space-2);
  color: var(--color-text-secondary);
  font-size: 11px;
  letter-spacing: 0.04em;
}

.menu-tag {
  margin-left: auto;
  color: var(--color-text-secondary);
  font-size: 11px;
}

.layout-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: var(--layout-header-height);
  background: var(--color-bg-surface);
  border-bottom: 1px solid var(--color-border);
}

.header-left {
  display: flex;
  gap: var(--space-3);
  align-items: center;
}

.header-title {
  font-size: 15px;
  font-weight: 600;
}

.env-badge {
  color: var(--color-text-secondary);
  font-size: 12px;
}

.profile-entry {
  font-size: 12px;
}


.layout-main {
  padding: var(--space-5);
}
</style>
