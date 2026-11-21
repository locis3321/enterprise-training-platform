<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { fetchPing } from '@/api/system'
import type { PingResult } from '@/api/system'

type LoadState = 'loading' | 'ready' | 'failed'

const state = ref<LoadState>('loading')
const ping = ref<PingResult | null>(null)
const errorMessage = ref('')

async function loadStatus() {
  state.value = 'loading'
  errorMessage.value = ''
  try {
    ping.value = await fetchPing()
    state.value = 'ready'
  } catch (error) {
    state.value = 'failed'
    errorMessage.value = error instanceof Error ? error.message : '无法连接后端服务'
  }
}

onMounted(loadStatus)

const roadmap = [
  { task: 'Task 02', title: '身份、企业与员工', detail: '管理端登录、企业/员工 CRUD、Excel 导入、数据范围授权' },
  { task: 'Task 03', title: '课程与资源', detail: '课程版本、章节、MinIO 私有存储、短签名播放地址' },
  { task: 'Task 04', title: '培训包与任务', detail: '培训包发布快照、任务分配、起止时间' },
  { task: 'Task 05', title: '题库与考试', detail: '题库、试卷、考试场次、服务端判分' },
  { task: 'Task 06', title: '档案与报表', detail: '学习记录、考试历史、企业维度统计' },
]
</script>

<template>
  <div>
    <header class="page-header">
      <h1 class="page-title">工作台</h1>
      <p class="page-desc">
        当前为工程骨架：本页的状态卡片通过真实调用后端
        <code>GET /api/v1/ping</code> 获取，用于验证前后端连通与时区配置。
      </p>
    </header>

    <section class="status-grid">
      <div class="surface-card">
        <div class="card-head">
          <span class="card-label">后端连通性</span>
          <el-tag v-if="state === 'ready'" type="success" size="small" effect="plain">
            正常
          </el-tag>
          <el-tag v-else-if="state === 'failed'" type="danger" size="small" effect="plain">
            不可用
          </el-tag>
          <el-tag v-else type="info" size="small" effect="plain">检测中</el-tag>
        </div>

        <p v-if="state === 'ready'" class="card-value">{{ ping?.app }}</p>
        <p v-else-if="state === 'failed'" class="card-value card-value--error">
          {{ errorMessage }}
        </p>
        <p v-else class="card-value">正在请求…</p>

        <el-button
          v-if="state === 'failed'"
          class="retry"
          size="small"
          @click="loadStatus"
        >
          重试
        </el-button>
      </div>

      <div class="surface-card">
        <div class="card-head">
          <span class="card-label">服务器时间</span>
        </div>
        <p class="card-value card-value--mono">{{ ping?.serverTime ?? '—' }}</p>
        <p class="card-hint">时区 {{ ping?.timeZone ?? '—' }}（业务时间统一由服务端判定）</p>
      </div>

      <div class="surface-card">
        <div class="card-head">
          <span class="card-label">构建版本</span>
        </div>
        <p class="card-value card-value--mono">{{ ping?.version ?? '本地运行（未打包）' }}</p>
        <p class="card-hint">开发环境启动方式见 services/api/README.md</p>
      </div>
    </section>

    <section class="surface-card roadmap">
      <h2 class="section-title">后续模块</h2>
      <p class="section-desc">按模块顺序推进，每完成一个模块需通过构建、单测与联调验证。</p>
      <el-table :data="roadmap" size="small" :show-header="true">
        <el-table-column prop="task" label="任务" width="96" />
        <el-table-column prop="title" label="模块" width="160" />
        <el-table-column prop="detail" label="范围" />
      </el-table>
    </section>
  </div>
</template>

<style scoped>
.status-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: var(--space-4);
  margin-bottom: var(--space-5);
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-3);
}

.card-label {
  color: var(--color-text-secondary);
  font-size: 12px;
}

.card-value {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}

.card-value--mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 15px;
}

.card-value--error {
  color: var(--color-danger);
  font-size: 13px;
  font-weight: 400;
}

.card-hint {
  margin: var(--space-2) 0 0;
  color: var(--color-text-secondary);
  font-size: 12px;
}

.retry {
  margin-top: var(--space-3);
}

.roadmap {
  padding-bottom: var(--space-4);
}

.section-title {
  margin: 0 0 var(--space-1);
  font-size: 16px;
  font-weight: 600;
}

.section-desc {
  margin: 0 0 var(--space-4);
  color: var(--color-text-secondary);
  font-size: 12px;
}
</style>
