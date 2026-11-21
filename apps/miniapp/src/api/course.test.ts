import { describe, expect, it } from 'vitest'
import { toPageResult } from '@/api/course'

describe('课程分页适配（P2）', () => {
  it('后端 items 映射为 unibest 的 list', () => {
    const result = toPageResult({
      items: [{ id: 1, title: '课程A' }],
      total: 1,
      page: 1,
      pageSize: 10,
    })

    expect(result.list).toHaveLength(1)
    expect(result.list[0].title).toBe('课程A')
    expect(result.total).toBe(1)
  })

  it('缺字段时返回安全默认值，不抛错', () => {
    const result = toPageResult({} as any)

    expect(result.list).toEqual([])
    expect(result.total).toBe(0)
  })
})
