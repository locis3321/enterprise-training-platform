import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { fetchMe } from '@/api/auth'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/login/LoginView.vue'),
    meta: { title: '登录', requiresAuth: false },
  },
  {
    path: '/',
    component: () => import('@/layouts/DefaultLayout.vue'),
    meta: { requiresAuth: true },
    children: [
      {
        path: '',
        name: 'dashboard',
        component: () => import('@/views/dashboard/DashboardView.vue'),
        meta: { title: '工作台' },
      },
      {
        path: 'enterprises',
        name: 'enterprises',
        component: () => import('@/views/enterprise/EnterpriseListView.vue'),
        meta: { title: '组织管理' },
      },
      {
        path: 'courses',
        name: 'courses',
        component: () => import('@/views/course/CourseListView.vue'),
        meta: { title: '课程与资源' },
      },
      {
        path: 'courses/:id',
        name: 'course-edit',
        component: () => import('@/views/course/CourseEditView.vue'),
        meta: { title: '课程编辑' },
      },
      {
        path: 'training/packages',
        name: 'training-packages',
        component: () => import('@/views/training/PackageListView.vue'),
        meta: { title: '培训包' },
      },
      {
        path: 'training/packages/:id',
        name: 'training-package-edit',
        component: () => import('@/views/training/PackageEditView.vue'),
        meta: { title: '培训包编辑' },
      },
      {
        path: 'training/tasks',
        name: 'training-tasks',
        component: () => import('@/views/training/TaskListView.vue'),
        meta: { title: '培训任务' },
      },
      {
        path: 'training/tasks/:id',
        name: 'training-task-detail',
        component: () => import('@/views/training/TaskDetailView.vue'),
        meta: { title: '任务详情' },
      },
      {
        path: 'employees',
        name: 'employees',
        component: () => import('@/views/employee/EmployeeListView.vue'),
        meta: { title: '员工档案' },
      },
      {
        path: 'exams/questions',
        name: 'exam-questions',
        component: () => import('@/views/exam/QuestionBankView.vue'),
        meta: { title: '题库' },
      },
      {
        path: 'exams/papers',
        name: 'exam-papers',
        component: () => import('@/views/exam/ExamPaperView.vue'),
        meta: { title: '试卷' },
      },
      {
        path: 'system/users',
        name: 'system-users',
        component: () => import('@/views/system/AdminUserListView.vue'),
        meta: { title: '账号管理' },
      },
      {
        path: 'reports',
        name: 'reports',
        component: () => import('@/views/report/ReportView.vue'),
        meta: { title: '培训报表' },
      },
      {
        path: 'settings',
        name: 'settings',
        component: () => import('@/views/system/SettingsView.vue'),
        meta: { title: '系统设置' },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/',
  },
]

const router = createRouter({
  // 部署在 /admin 子路径时需与 vite base 一致，否则刷新会解析成站内其它路径
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!to.meta.requiresAuth) {
    return true
  }
  if (!auth.token) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  // 刷新页面后 token 仍在 sessionStorage，但内存中的角色/范围已丢失，
  // 必须回查 /auth/me 重新填充，避免前端按空角色渲染菜单。
  if (!auth.roleCode) {
    try {
      const me = await fetchMe()
      auth.setSession(auth.token, me.displayName, me.roleCode, me.dataScope,
        me.insuranceCompanyId, me.enterpriseId)
    }
    catch {
      auth.clear()
      return { name: 'login', query: { redirect: to.fullPath } }
    }
  }
  return true
})

router.afterEach((to) => {
  const appTitle = import.meta.env.VITE_APP_TITLE || '企业内训平台'
  document.title = to.meta.title ? `${to.meta.title} · ${appTitle}` : appTitle
})

export default router
