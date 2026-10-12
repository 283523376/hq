import { describe, expect, it } from 'vitest'

import { validateDoc } from '../doc'
import { harvestInboundDoc } from './harvestInbound'

describe('示例图 harvestInboundDoc', () => {
  it('通过文档校验', () => {
    const result = validateDoc(harvestInboundDoc)
    expect(result.ok ? [] : result.errors).toEqual([])
  })

  it('6 条泳道、12 个步骤', () => {
    expect(harvestInboundDoc.lanes).toHaveLength(6)
    expect(harvestInboundDoc.stepCount).toBe(12)
  })

  it('21 个节点，编号从 A01 到 A21 连续', () => {
    const ids = harvestInboundDoc.nodes.map((node) => node.id).sort()
    expect(ids).toHaveLength(21)
    expect(ids[0]).toBe('A01')
    expect(ids[20]).toBe('A21')
  })

  it('连线数量落在 20–26 条（对图读数的合理区间，偏离说明漏读或误读）', () => {
    expect(harvestInboundDoc.edges.length).toBeGreaterThanOrEqual(20)
    expect(harvestInboundDoc.edges.length).toBeLessThanOrEqual(26)
  })

  it('同时存在实线主流程与虚线异常流程', () => {
    const kinds = new Set(harvestInboundDoc.edges.map((edge) => edge.kind))
    expect(kinds).toContain('main')
    expect(kinds).toContain('exception')
  })

  it('异常连线都带标签（原图红虚线全部有文字说明）', () => {
    const exceptions = harvestInboundDoc.edges.filter((edge) => edge.kind === 'exception')
    expect(exceptions.length).toBeGreaterThan(0)
    for (const edge of exceptions) {
      expect(edge.label).toBeTruthy()
    }
  })

  it('没有节点落在重复格（validateDoc 之外再断言一次，便于定位）', () => {
    const cells = harvestInboundDoc.nodes.map((node) => `${node.laneId}#${node.step}`)
    expect(new Set(cells).size).toBe(cells.length)
  })
})