import { defineStore } from 'pinia'
import { ref } from 'vue'

export const TOKEN_KEY = 'it_admin_token'

/**
 * 管理端登录态。
 *
 * 存储选择 sessionStorage（复审 F3 结论）：
 * - 关闭标签页即失效，相比 localStorage 减少 token 持久暴露面；
 * - 刷新页面仍保持登录，不引入 refresh token 的额外复杂度。
 * 该方案默认同源脚本可信；后续若引入富文本等第三方脚本，需复核 XSS 风险。
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(sessionStorage.getItem(TOKEN_KEY) ?? '')
  const displayName = ref<string>('')
  const roleCode = ref<string>('')
  const dataScope = ref<string>('')
  const insuranceCompanyId = ref<number | null>(null)
  const enterpriseId = ref<number | null>(null)

  function setSession(nextToken: string, name: string, role: string, scope: string,
                      companyId: number | null, entId: number | null) {
    token.value = nextToken
    displayName.value = name
    roleCode.value = role
    dataScope.value = scope
    insuranceCompanyId.value = companyId
    enterpriseId.value = entId
    sessionStorage.setItem(TOKEN_KEY, nextToken)
  }

  function clear() {
    token.value = ''
    displayName.value = ''
    roleCode.value = ''
    dataScope.value = ''
    insuranceCompanyId.value = null
    enterpriseId.value = null
    sessionStorage.removeItem(TOKEN_KEY)
  }

  return {
    token,
    displayName,
    roleCode,
    dataScope,
    insuranceCompanyId,
    enterpriseId,
    setSession,
    clear,
  }
})
