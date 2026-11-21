import { describe, expect, it } from 'vitest'
import { ratioPercent, recordPercent } from './archive'

describe('recordPercent', () => {
  it('按得分与总分算百分比', () => {
    expect(recordPercent({ scoreSum: 90, totalScore: 100 })).toBe(90)
    expect(recordPercent({ scoreSum: 1, totalScore: 3 })).toBe(33)
  })

  it('总分为 0 或缺失时返回 0，不出现 NaN', () => {
    expect(recordPercent({ scoreSum: 10, totalScore: 0 })).toBe(0)
    expect(recordPercent({ scoreSum: 10, totalScore: null })).toBe(0)
  })

  it('得分缺失按 0 计', () => {
    expect(recordPercent({ scoreSum: null, totalScore: 100 })).toBe(0)
  })

  it('满分返回 100', () => {
    expect(recordPercent({ scoreSum: 50, totalScore: 50 })).toBe(100)
  })
})

describe('ratioPercent', () => {
  it('正常比例四舍五入为整数', () => {
    expect(ratioPercent(3, 4)).toBe(75)
    expect(ratioPercent(1, 3)).toBe(33)
  })

  it('分母为 0 返回 0，避免除零', () => {
    expect(ratioPercent(0, 0)).toBe(0)
    expect(ratioPercent(5, 0)).toBe(0)
  })

  it('全部完成返回 100', () => {
    expect(ratioPercent(8, 8)).toBe(100)
  })

  it('未开始返回 0', () => {
    expect(ratioPercent(0, 8)).toBe(0)
  })
})
