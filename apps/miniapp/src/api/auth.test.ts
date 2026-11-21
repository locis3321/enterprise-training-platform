import { beforeEach, describe, expect, it, vi } from 'vitest'

const post = vi.fn()
const get = vi.fn()

vi.mock('@/http/http', () => ({
  http: { post, get },
}))

describe('小程序认证接口', () => {
  beforeEach(() => {
    post.mockReset()
    get.mockReset()
  })

  it('微信登录只提交 code，不提交 openid/手机号', async () => {
    post.mockResolvedValue({ bound: false, token: null, bindTicket: 't-1', displayName: null })
    const { wechatLogin } = await import('@/api/auth')

    await wechatLogin('0123456789abcdef0123456789abcdef')

    expect(post).toHaveBeenCalledWith('/miniapp/auth/wechat-login', {
      code: '0123456789abcdef0123456789abcdef',
    })
  })

  it('手机号绑定提交 bindTicket 与 phoneCode，不提交手机号本身', async () => {
    post.mockResolvedValue({ token: 'jwt', displayName: '张三', enterpriseName: '企业甲' })
    const { phoneBind } = await import('@/api/auth')

    await phoneBind('t-1', 'phone-code-from-wechat')

    expect(post).toHaveBeenCalledWith('/miniapp/auth/phone-bind', {
      bindTicket: 't-1',
      phoneCode: 'phone-code-from-wechat',
    })
  })

  it('个人档案走 GET /miniapp/user/profile', async () => {
    get.mockResolvedValue({ employeeId: 1, name: '张三' })
    const { fetchProfile } = await import('@/api/auth')

    await fetchProfile()

    expect(get).toHaveBeenCalledWith('/miniapp/user/profile')
  })
})
