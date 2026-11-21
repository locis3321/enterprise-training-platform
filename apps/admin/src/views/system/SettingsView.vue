<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchPrivacyPolicy,
  fetchSettings,
  refreshSettings,
  resetSetting,
  updateSetting,
  uploadPrivacyPolicy,
} from '@/api/settings'
import type { PrivacyPolicy, SystemSetting, SystemSettingsView } from '@/api/settings'

const view = ref<SystemSettingsView | null>(null)
const loading = ref(false)
const savingKey = ref<string | null>(null)
/** 每项的编辑中值，逐项保存，避免整表提交把未编辑项也写成覆盖值。 */
const drafts = ref<Record<string, string>>({})

const groups = computed(() => {
  const map = new Map<string, SystemSetting[]>()
  for (const item of view.value?.items ?? []) {
    const list = map.get(item.group) ?? []
    list.push(item)
    map.set(item.group, list)
  }
  return Array.from(map.entries())
})

function typeHint(type: SystemSetting['valueType']) {
  switch (type) {
    case 'INT':
      return '整数'
    case 'DURATION':
      return 'ISO-8601 时长，如 PT5M、PT5S'
    case 'CSV':
      return '逗号分隔；支持精确 IP 与 CIDR，留空表示不限制'
    default:
      return ''
  }
}

async function load() {
  loading.value = true
  try {
    view.value = await fetchSettings()
    drafts.value = Object.fromEntries(
      (view.value.items ?? []).map((i) => [i.key, i.effectiveValue ?? '']),
    )
  }
  catch {
    view.value = null
  }
  finally {
    loading.value = false
  }
}

async function save(item: SystemSetting) {
  const value = drafts.value[item.key] ?? ''
  if (item.key === 'wechat.appid') {
    const confirmed = await ElMessageBox.confirm(
      '修改 AppID 后，原 AppID 下的微信绑定关系将不再匹配，学员需要重新绑定。确认修改？',
      '修改小程序 AppID',
      { type: 'warning', confirmButtonText: '确认修改', cancelButtonText: '取消' },
    ).catch(() => false)
    if (!confirmed) {
      return
    }
  }
  savingKey.value = item.key
  try {
    await updateSetting(item.key, value)
    ElMessage.success('已保存，本节点立即生效')
    await load()
  }
  catch (error) {
    // 防自锁 400 等消息原样展示，不改写成笼统失败，也不自动重试
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  }
  finally {
    savingKey.value = null
  }
}

async function reset(item: SystemSetting) {
  const confirmed = await ElMessageBox.confirm(
    `恢复「${item.label}」为出厂默认值？`,
    '恢复出厂默认',
    { type: 'warning', confirmButtonText: '确认恢复', cancelButtonText: '取消' },
  ).catch(() => false)
  if (!confirmed) {
    return
  }
  savingKey.value = item.key
  try {
    await resetSetting(item.key)
    ElMessage.success('已恢复出厂默认')
    await load()
  }
  catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '恢复失败')
  }
  finally {
    savingKey.value = null
  }
}

async function manualRefresh() {
  try {
    view.value = await refreshSettings()
    ElMessage.success('已刷新')
  }
  catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '刷新失败')
  }
}

// ---------- 隐私协议 PDF ----------
const policy = ref<PrivacyPolicy | null>(null)
const policyLoading = ref(false)
const policyUploading = ref(false)
const policyInput = ref<HTMLInputElement | null>(null)

const policyUpdatedText = computed(() => {
  const value = policy.value?.updatedAt
  return value ? value.replace('T', ' ').slice(0, 16) : '—'
})

async function loadPolicy() {
  policyLoading.value = true
  try {
    policy.value = await fetchPrivacyPolicy()
  }
  catch {
    policy.value = null
  }
  finally {
    policyLoading.value = false
  }
}

function pickPolicyFile() {
  policyInput.value?.click()
}

async function onPolicyFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  // 立刻清空，保证连续选择同一文件也能触发 change
  input.value = ''
  if (!file)
    return
  if (!file.name.toLowerCase().endsWith('.pdf')) {
    ElMessage.error('只支持 PDF 文件')
    return
  }
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.error('隐私协议 PDF 不能超过 10MB')
    return
  }
  const confirmed = await ElMessageBox.confirm(
    '上传后小程序端将立即读取新版协议（每次进入都会重新获取地址）。确认替换当前版本？',
    '替换隐私协议',
    { type: 'warning', confirmButtonText: '确认替换', cancelButtonText: '取消' },
  ).catch(() => false)
  if (!confirmed)
    return

  policyUploading.value = true
  try {
    policy.value = await uploadPrivacyPolicy(file)
    ElMessage.success('隐私协议已更新')
  }
  catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '上传失败')
  }
  finally {
    policyUploading.value = false
  }
}

function previewPolicy() {
  const url = policy.value?.url
  if (!url) {
    ElMessage.warning('协议地址不可用，请刷新后重试')
    return
  }
  window.open(url, '_blank')
}

onMounted(() => {
  load()
  loadPolicy()
})
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div>
        <h1 class="page-title">系统设置</h1>
        <p class="page-desc">
          保存后<strong>本节点立即生效</strong>，其他节点最长 30 秒刷新。
          每项标注当前值来源（数据库 / 出厂默认）。
        </p>
      </div>
      <el-button @click="manualRefresh">手动刷新</el-button>
    </header>

    <el-alert
      v-if="view?.lastRefreshError"
      type="error"
      show-icon
      :closable="false"
      :title="`配置刷新失败：${view.lastRefreshError}`"
      :description="`继续使用上一份可用配置（最后成功刷新 ${view.lastRefreshAt || '—'}）`"
    />

    <div v-loading="loading">
      <el-card v-for="[group, items] in groups" :key="group" shadow="never" class="group-card">
        <template #header>
          <span class="group-title">{{ group }}</span>
        </template>

        <div v-for="item in items" :key="item.key" class="setting-row">
          <div class="setting-meta">
            <div class="setting-label">
              {{ item.label }}
              <el-tag
                size="small"
                :type="item.source === 'DATABASE' ? 'success' : 'info'"
                effect="plain"
              >
                {{ item.source === 'DATABASE' ? '数据库' : '出厂默认' }}
              </el-tag>
            </div>
            <div class="setting-desc">{{ item.description }}</div>
            <div class="setting-sub">
              出厂默认：<code>{{ item.defaultValue ?? '（无）' }}</code>
              <span v-if="item.updatedAt"> · 更新于 {{ item.updatedAt }}</span>
            </div>
          </div>

          <div class="setting-edit">
            <el-input
              v-if="item.valueType === 'CSV'"
              v-model="drafts[item.key]"
              type="textarea"
              :rows="2"
              :placeholder="typeHint(item.valueType)"
            />
            <el-input
              v-else
              v-model="drafts[item.key]"
              :placeholder="typeHint(item.valueType)"
            />
            <div class="setting-actions">
              <el-button
                type="primary"
                size="small"
                :loading="savingKey === item.key"
                @click="save(item)"
              >保存</el-button>
              <el-button
                v-if="item.source === 'DATABASE'"
                size="small"
                :loading="savingKey === item.key"
                @click="reset(item)"
              >恢复出厂默认</el-button>
            </div>
          </div>
        </div>
      </el-card>

      <el-empty v-if="!loading && !view" description="设置加载失败，请稍后重试" />
    </div>

    <el-card shadow="never" class="group-card">
      <template #header>
        <span class="group-title">法务</span>
      </template>

      <div class="setting-row">
        <div class="setting-meta">
          <div class="setting-label">
            隐私协议 PDF
            <el-tag
              size="small"
              :type="policy?.available ? 'success' : 'info'"
              effect="plain"
            >
              {{ policy?.available ? '已发布' : '未发布' }}
            </el-tag>
          </div>
          <div class="setting-desc">
            小程序「我的 → 隐私协议」实时读取此文件。替换后无需客户端发版即可生效。
          </div>
          <div class="setting-sub">
            当前文件：<code>{{ policy?.filename ?? '（未上传）' }}</code>
            · 更新于 {{ policyUpdatedText }}
          </div>
        </div>

        <div class="setting-edit">
          <div v-loading="policyLoading" class="policy-actions">
            <input
              ref="policyInput"
              type="file"
              accept="application/pdf,.pdf"
              class="policy-input"
              @change="onPolicyFileChange"
            >
            <el-button
              type="primary"
              size="small"
              :loading="policyUploading"
              @click="pickPolicyFile"
            >
              {{ policy?.available ? '替换 PDF' : '上传 PDF' }}
            </el-button>
            <el-button
              v-if="policy?.available"
              size="small"
              @click="previewPolicy"
            >
              预览当前版本
            </el-button>
            <el-button size="small" @click="loadPolicy">
              刷新
            </el-button>
          </div>
          <div class="policy-hint">
            仅支持 PDF，最大 10MB。服务端校验文件头，非 PDF 会被拒绝。
          </div>
        </div>
      </div>
    </el-card>

    <p class="footer-note">
      数据库、Redis、对象存储凭证与 JWT 密钥、微信 AppSecret 仍由环境变量管理，不在此处配置。
    </p>
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

.group-card {
  margin-bottom: var(--space-4);
}

.group-title {
  font-weight: 600;
}

.setting-row {
  display: flex;
  gap: var(--space-5);
  padding: var(--space-4) 0;
  border-bottom: 1px solid var(--color-border);
}

.setting-row:last-child {
  border-bottom: none;
}

.setting-meta {
  flex: 0 0 42%;
}

.setting-label {
  display: flex;
  gap: var(--space-2);
  align-items: center;
  font-weight: 600;
  margin-bottom: var(--space-2);
}

.setting-desc {
  color: var(--color-text-secondary);
  font-size: 12px;
  line-height: 1.6;
  margin-bottom: var(--space-1);
}

.setting-sub {
  color: var(--color-text-secondary);
  font-size: 12px;
}

.setting-edit {
  flex: 1;
}

.setting-actions {
  margin-top: var(--space-2);
  display: flex;
  gap: var(--space-2);
}

.policy-actions {
  display: flex;
  gap: var(--space-2);
  align-items: center;
  flex-wrap: wrap;
}

.policy-input {
  display: none;
}

.policy-hint {
  margin-top: var(--space-2);
  color: var(--color-text-secondary);
  font-size: 12px;
}

.footer-note {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: 12px;
}
</style>
