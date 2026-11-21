import { http } from '@/http/http'

export interface WechatLoginResult {
  bound: boolean
  token: string | null
  bindTicket: string | null
  displayName: string | null
}

export interface PhoneBindResult {
  token: string
  displayName: string
  enterpriseName: string
}

export interface PasswordLoginResult {
  token: string
  displayName: string
  /** 该账号关联的启用企业数；>1 时前端可提示「你属于 N 家企业」。 */
  enterpriseCount: number
}

export interface EnterpriseBrief {
  enterpriseId: number
  enterpriseName: string
}

export interface StudentProfile {
  accountId: number
  name: string
  phoneMasked: string
  enterpriseId: number
  enterpriseName: string
  status: number
  enterpriseCount: number
  enterprises: EnterpriseBrief[]
  /** 首次登录或被管理员重置后为 true：必须先改密才能使用其它功能（服务端强制拦截）。 */
  mustChangePassword: boolean
}

/** 用 uni.login 的 code 换取 openid；未绑定员工时返回 bindTicket。 */
export function wechatLogin(code: string) {
  return http.post<WechatLoginResult>('/miniapp/auth/wechat-login', { code })
}

/**
 * 手机号绑定。
 *
 * 服务端会用 phoneCode 调微信接口取得真实手机号，客户端不传手机号本身。
 * 本地 Mock 环境下 phoneCode 约定为 `phone-<11位手机号>`，见 backend MockWechatClient。
 */
export function phoneBind(bindTicket: string, phoneCode: string) {
  return http.post<PhoneBindResult>('/miniapp/auth/phone-bind', { bindTicket, phoneCode })
}

/**
 * 手机号 + 密码登录。
 *
 * 手机号是账号标识（按手机号全局唯一，可关联多个企业），凭证是服务端 BCrypt 校验的密码。
 * 该入口不依赖微信手机号授权能力，个人主体小程序也能用。
 */
export function passwordLogin(phone: string, password: string) {
  return http.post<PasswordLoginResult>('/miniapp/auth/password-login', { phone, password })
}

/** 修改密码（需登录）。服务端校验原密码，新密码长度 6-64。 */
export function changePassword(oldPassword: string, newPassword: string) {
  return http.post<void>('/miniapp/auth/password', { oldPassword, newPassword })
}

export function fetchProfile() {
  return http.get<StudentProfile>('/miniapp/user/profile')
}
