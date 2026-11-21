import { describe, expect, it, vi } from 'vitest'

vi.mock('@/utils', () => ({
  getLastPage: () => ({ route: 'pages/course/course' }),
}))

vi.mock('@/utils/debounce', () => ({
  debounce: (fn: any) => {
    const wrapped = (...args: any[]) => fn(...args)
    ;(wrapped as any).flush = () => {}
    return wrapped
  },
}))

describe('登录跳转路径（P1）', () => {
  it('登录页常量必须与真实页面一致，避免未登录跳转 404', async () => {
    const { LOGIN_PAGE } = await import('@/utils/toLoginPage')

    expect(LOGIN_PAGE).toBe('/pages/login/login')
  })

  it('toLoginPage 实际调用 uni.navigateTo 且 URL 指向登录页', async () => {
    const navigateTo = vi.fn()
    ;(globalThis as any).uni = { navigateTo, reLaunch: vi.fn() }

    const { toLoginPage, LOGIN_PAGE } = await import('@/utils/toLoginPage')
    toLoginPage()

    expect(navigateTo).toHaveBeenCalled()
    expect(navigateTo.mock.calls[0][0].url).toBe(LOGIN_PAGE)
  })
})
