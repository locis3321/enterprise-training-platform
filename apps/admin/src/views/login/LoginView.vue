<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { login } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

const formRef = ref<FormInstance>()
const submitting = ref(false)
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const form = reactive({
  account: '',
  password: '',
})

const rules: FormRules = {
  account: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function handleSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const result = await login(form.account.trim(), form.password)
    auth.setSession(result.token, result.displayName, result.roleCode, result.dataScope,
      result.insuranceCompanyId, result.enterpriseId)
    ElMessage.success(`欢迎，${result.displayName}`)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.replace(redirect)
  }
  catch {
    // request.ts 已统一提示后端返回的错误文案（账号或密码不正确 / 频率超限）
  }
  finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <section class="login-intro">
      <div class="intro-brand">
        <span class="brand-mark" aria-hidden="true">训</span>
        <span>企业内训平台</span>
      </div>

      <h1 class="intro-title">集团组织培训、<br />子公司员工完成学习与考核</h1>

      <ul class="intro-list">
        <li>
          <strong>组织与人员</strong>
          <span>集团、子公司部门、员工档案与 Excel 批量导入</span>
        </li>
        <li>
          <strong>培训内容</strong>
          <span>课程版本、视频与资料私有存储、培训包发布快照</span>
        </li>
        <li>
          <strong>任务与考核</strong>
          <span>培训任务起止时间、学习完成判定、考试准入与多次考试记录</span>
        </li>
      </ul>

      <p class="intro-note">
        账号与数据范围由平台统一管理；企业管理员只能查看本企业数据。
      </p>
    </section>

    <section class="login-panel">
      <div class="panel-card">
        <h2 class="panel-title">管理端登录</h2>
        <p class="panel-desc">账号由平台超级管理员创建</p>

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          size="large"
          @submit.prevent="handleSubmit"
        >
          <el-form-item label="账号" prop="account">
            <el-input v-model="form.account" placeholder="手机号或邮箱" autocomplete="username" />
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="请输入密码"
              show-password
              autocomplete="current-password"
            />
          </el-form-item>

          <el-button
            class="submit-button"
            type="primary"
            native-type="submit"
            :loading="submitting"
          >
            登录
          </el-button>
        </el-form>

        <el-alert
          class="panel-alert"
          type="info"
          :closable="false"
          show-icon
          title="账号由平台超级管理员创建"
          description="本地开发默认账号 admin / Admin@12345（生产环境必须修改）。"
        />
      </div>
    </section>
  </div>
</template>

<style scoped>
.login-page {
  display: grid;
  grid-template-columns: 1.1fr 1fr;
  min-height: 100%;
  background: var(--color-bg-page);
}

.login-intro {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: var(--space-5);
  padding: 64px clamp(32px, 6vw, 96px);
  background: var(--color-bg-surface);
  border-right: 1px solid var(--color-border);
}

.intro-brand {
  display: flex;
  gap: var(--space-2);
  align-items: center;
  color: var(--color-text-secondary);
  font-size: 13px;
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

.intro-title {
  margin: 0;
  font-size: 28px;
  font-weight: 600;
  line-height: 1.45;
}

.intro-list {
  display: grid;
  gap: var(--space-4);
  max-width: 520px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.intro-list li {
  display: grid;
  gap: var(--space-1);
  padding-left: var(--space-4);
  border-left: 2px solid var(--color-primary-soft);
}

.intro-list strong {
  font-size: 13px;
  font-weight: 600;
}

.intro-list span {
  color: var(--color-text-secondary);
  font-size: 13px;
}

.intro-note {
  max-width: 520px;
  margin: 0;
  color: var(--color-text-secondary);
  font-size: 12px;
}

.login-panel {
  display: grid;
  place-items: center;
  padding: var(--space-6);
}

.panel-card {
  width: min(380px, 100%);
  padding: var(--space-6);
  background: var(--color-bg-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-md);
}

.panel-title {
  margin: 0 0 var(--space-1);
  font-size: 20px;
  font-weight: 600;
}

.panel-desc {
  margin: 0 0 var(--space-5);
  color: var(--color-text-secondary);
  font-size: 13px;
}

.submit-button {
  width: 100%;
  margin-top: var(--space-2);
}

.panel-alert {
  margin-top: var(--space-5);
}

@media (max-width: 960px) {
  .login-page {
    grid-template-columns: 1fr;
  }

  .login-intro {
    display: none;
  }
}
</style>
