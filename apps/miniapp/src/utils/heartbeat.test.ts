import { describe, expect, it, vi } from 'vitest'
import { createHeartbeatTicker, HEARTBEAT_INTERVAL_MS } from '@/utils/heartbeat'
import type { HeartbeatReason } from '@/utils/heartbeat'

/** 可控定时器：手动触发 tick，便于断言「每 30 秒一次」。 */
function fakeTimer() {
  let fn: (() => void) | null = null
  let ms = 0
  let cleared = 0
  return {
    setIntervalFn: ((callback: () => void, interval: number) => {
      fn = callback
      ms = interval
      return 1
    }) as unknown as (fn: () => void, ms: number) => unknown,
    clearIntervalFn: ((_handle: unknown) => {
      fn = null
      cleared++
    }) as unknown as (handle: unknown) => void,
    tick() {
      fn?.()
    },
    get intervalMs() {
      return ms
    },
    get clearedTimes() {
      return cleared
    },
    get active() {
      return fn !== null
    },
  }
}

function setup(sendImpl?: (reason: HeartbeatReason) => Promise<void>) {
  const reasons: HeartbeatReason[] = []
  const timer = fakeTimer()
  const send = vi.fn(sendImpl ?? (async (reason: HeartbeatReason) => {
    reasons.push(reason)
  }))
  const ticker = createHeartbeatTicker({
    send,
    setIntervalFn: timer.setIntervalFn,
    clearIntervalFn: timer.clearIntervalFn,
  })
  return { ticker, timer, send, reasons }
}

describe('心跳节奏契约', () => {
  it('播放开始立即上报一次 start', async () => {
    const { ticker, reasons } = setup()
    await ticker.start()
    expect(reasons).toEqual(['start'])
    ticker.dispose()
  })

  it('间隔为 30 秒', async () => {
    const { ticker, timer } = setup()
    await ticker.start()
    expect(timer.intervalMs).toBe(HEARTBEAT_INTERVAL_MS)
    expect(HEARTBEAT_INTERVAL_MS).toBe(30_000)
    ticker.dispose()
  })

  it('定时器每触发一次上报一次 interval', async () => {
    const { ticker, timer, reasons } = setup()
    await ticker.start()
    timer.tick()
    await Promise.resolve()
    await Promise.resolve()
    expect(reasons).toEqual(['start', 'interval'])
    ticker.dispose()
  })

  it('暂停时补发一次 pause 并停止定时器', async () => {
    const { ticker, timer, reasons } = setup()
    await ticker.start()
    await ticker.pause()
    expect(reasons).toEqual(['start', 'pause'])
    expect(timer.active).toBe(false)
    expect(ticker.isRunning()).toBe(false)
  })

  it('播放结束时补发一次 ended', async () => {
    const { ticker, reasons } = setup()
    await ticker.start()
    await ticker.ended()
    expect(reasons).toEqual(['start', 'ended'])
  })

  it('页面隐藏时补发一次 hide', async () => {
    const { ticker, reasons } = setup()
    await ticker.start()
    await ticker.hide()
    expect(reasons).toEqual(['start', 'hide'])
  })

  it('未开始播放时不补发（避免空上报）', async () => {
    const { ticker, reasons } = setup()
    await ticker.pause()
    await ticker.ended()
    await ticker.hide()
    expect(reasons).toEqual([])
  })

  it('dispose 后定时器被清理，且不再上报', async () => {
    const { ticker, timer, reasons } = setup()
    await ticker.start()
    ticker.dispose()
    expect(timer.active).toBe(false)
    timer.tick()
    await Promise.resolve()
    expect(reasons).toEqual(['start'])
  })

  it('重复 start 不会叠加定时器或重复上报', async () => {
    const { ticker, reasons } = setup()
    await ticker.start()
    await ticker.start()
    expect(reasons).toEqual(['start'])
    ticker.dispose()
  })

  it('上一次心跳未返回时跳过本次，不制造并发', async () => {
    let release: (() => void) | null = null
    const order: string[] = []
    const { ticker, timer } = setup(async (reason) => {
      order.push(reason)
      await new Promise<void>((resolve) => {
        release = resolve
      })
    })
    const starting = ticker.start()
    // 此时第一次心跳仍在飞
    timer.tick()
    await Promise.resolve()
    expect(order).toEqual(['start'])
    release?.()
    await starting
    ticker.dispose()
  })

  it('单次心跳失败不抛出，且不中断后续节奏', async () => {
    let calls = 0
    const { ticker, timer } = setup(async () => {
      calls++
      if (calls === 1) {
        throw new Error('network down')
      }
    })
    await ticker.start()
    timer.tick()
    await Promise.resolve()
    await Promise.resolve()
    expect(calls).toBe(2)
    ticker.dispose()
  })
})
