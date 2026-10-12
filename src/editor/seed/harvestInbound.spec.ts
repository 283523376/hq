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

  it('异常连线里的文字标签非空（原图三条红虚线带文字说明）', () => {
    const exceptions = harvestInboundDoc.edges.filter((edge) => edge.kind === 'exception')
    const labelled = exceptions.filter((edge) => edge.label)
    expect(labelled).toHaveLength(3)
    for (const edge of labelled) {
      expect(edge.label!.length).toBeGreaterThan(0)
    }
  })

  it('A13/A14 是同一格并排的两个节点，且不重叠', () => {
    const cell = harvestInboundDoc.nodes.filter(
      (node) => node.laneId === 'lane-prep' && node.step === 6,
    )
    expect(cell.map((node) => node.id).sort()).toEqual(['A13', 'A14'])
    for (const node of cell) {
      expect(node.width).toBeGreaterThan(0)
      expect(typeof node.offsetX).toBe('number')
    }
    // 两个节点各自收窄又左右错开，横向范围不相交
    const [a, b] = cell
    expect(Math.abs((a.offsetX ?? 0) - (b.offsetX ?? 0))).toBeGreaterThan(
      ((a.width ?? 0) + (b.width ?? 0)) / 2,
    )
  })
})