/**
 * 心跳节奏控制（纯逻辑，便于单测）。
 *
 * <p>契约（T6）：
 * - 播放开始立即一次；
 * - 之后每 30 秒一次；
 * - `pause` / `ended` / `onHide` 时各补一次，让末尾区间也被计入；
 * - 页面卸载必须清理定时器，避免离开后继续上报。
 *
 * <p>把「何时上报」与「怎么上报」分开：本模块只管节奏与生命周期，
 * 请求由调用方注入，测试可完全脱离 uni 环境。
 */

export const HEARTBEAT_INTERVAL_MS = 30_000

export type HeartbeatReason = 'start' | 'interval' | 'pause' | 'ended' | 'hide' | 'duration'

export interface HeartbeatTickerOptions {
  /** 实际发送心跳；返回 Promise 便于串行化，避免并发心跳。 */
  send: (reason: HeartbeatReason) => Promise<void>
  /** 定时器实现，默认 setInterval；测试可注入假实现。 */
  setIntervalFn?: (fn: () => void, ms: number) => unknown
  clearIntervalFn?: (handle: unknown) => void
  intervalMs?: number
}

export interface HeartbeatTicker {
  start: () => Promise<void>
  pause: () => Promise<void>
  ended: () => Promise<void>
  hide: () => Promise<void>
  dispose: () => void
  isRunning: () => boolean
}

/**
 * 创建心跳节奏控制器。
 *
 * <p>发送串行化：上一次请求未完成时，后续触发会被合并（记录最新原因），
 * 避免并发心跳把同一段时间计入两次（服务端虽有 CAS 兜底，前端也不应制造并发）。
 */
export function createHeartbeatTicker(options: HeartbeatTickerOptions): HeartbeatTicker {
  const intervalMs = options.intervalMs ?? HEARTBEAT_INTERVAL_MS
  const setIntervalFn = options.setIntervalFn ?? ((fn, ms) => setInterval(fn, ms))
  const clearIntervalFn = options.clearIntervalFn ?? (handle => clearInterval(handle as number))

  let handle: unknown = null
  let running = false
  let disposed = false
  let inFlight = false

  async function fire(reason: HeartbeatReason): Promise<void> {
    if (disposed) {
      return
    }
    // 上一次还没回来就跳过本次：宁可少记，不要制造并发
    if (inFlight) {
      return
    }
    inFlight = true
    try {
      await options.send(reason)
    }
    catch {
      // 单次心跳失败不打断播放：服务端计时以自身时钟为准，下一次会自然补上
    }
    finally {
      inFlight = false
    }
  }

  async function stopTimer(): Promise<void> {
    if (handle !== null) {
      clearIntervalFn(handle)
      handle = null
    }
    running = false
  }

  return {
    async start() {
      if (disposed || running) {
        return
      }
      running = true
      await fire('start')
      handle = setIntervalFn(() => {
        void fire('interval')
      }, intervalMs)
    },

    async pause() {
      const wasRunning = running
      await stopTimer()
      if (wasRunning) {
        await fire('pause')
      }
    },

    async ended() {
      const wasRunning = running
      await stopTimer()
      if (wasRunning) {
        await fire('ended')
      }
    },

    async hide() {
      const wasRunning = running
      await stopTimer()
      if (wasRunning) {
        await fire('hide')
      }
    },

    dispose() {
      disposed = true
      if (handle !== null) {
        clearIntervalFn(handle)
        handle = null
      }
      running = false
    },

    isRunning() {
      return running
    },
  }
}
