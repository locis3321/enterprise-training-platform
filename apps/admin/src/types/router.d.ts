export {}

/**
 * 扩展 vue-router 的路由元信息类型。
 * 必须放在独立模块文件里做「类型增强」，否则会覆盖 vue-router 的原有导出
 * （典型症状：TS2305 Module '"vue-router"' has no exported member 'createRouter'）。
 */
declare module 'vue-router' {
  interface RouteMeta {
    /** 浏览器标题与页头标题 */
    title?: string
    /** 是否需要登录态；骨架阶段仅工作台为 false，Task 02 起改为 true */
    requiresAuth?: boolean
  }
}
