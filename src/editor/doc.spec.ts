import { describe, expect, it } from 'vitest'

import { validateDoc } from './doc'
import type { FlowDoc } from './types'

function validDoc(): FlowDoc {
  return {
    schemaVersion: 1,
    id: 'doc-1',
    name: '测试图',
    updatedAt: '2026-10-12T00:00:00.000Z',
    grid: {
      headerHeight: 40,
      stepColumnWidth: 60,
      rowHeight: 100,
      lanePadX: 10,
      lanePadY: 8,
    },
    lanes: [
      { id: 'l1', name: '甲', color: 'blue', width: 200 },
      { id: 'l2', name: '乙', color: 'red', width: 150 },
    ],
    stepCount: 3,
    nodes: [
      { id: 'A01', text: '一', laneId: 'l1', step: 1, kind: 'process' },
      { id: 'A02', text: '二', laneId: 'l2', step: 2, kind: 'process' },
    ],
    edges: [{ id: 'e1', source: 'A01', target: 'A02', kind: 'main', label: '下一' }],
  }
}

function errorsOf(input: unknown): string[] {
  const result = validateDoc(input)
  expect(result.ok).toBe(false)
  return result.ok ? [] : result.errors
}

describe('validateDoc 通过的情形', () => {
  it('合法文档返回 ok 与文档本身', () => {
    const result = validateDoc(validDoc())
    expect(result.ok).toBe(true)
    if (result.ok) expect(result.doc.nodes).toHaveLength(2)
  })

  it('接受纯 JSON 往返后的对象', () => {
    expect(validateDoc(JSON.parse(JSON.stringify(validDoc()))).ok).toBe(true)
  })
})

describe('validateDoc 拒绝的情形', () => {
  it('非对象 / null / undefined', () => {
    expect(errorsOf(null).length).toBeGreaterThan(0)
    expect(errorsOf(undefined).length).toBeGreaterThan(0)
    expect(errorsOf('不是对象').length).toBeGreaterThan(0)
  })

  it('schemaVersion 不是 1', () => {
    expect(errorsOf({ ...validDoc(), schemaVersion: 2 }).join()).toMatch(/schemaVersion/)
  })

  it('泳道为空', () => {
    expect(errorsOf({ ...validDoc(), lanes: [] }).join()).toMatch(/泳道/)
  })

  it('泳道宽度不足以放下节点（扣掉内边距后 <= 0）', () => {
    const doc = validDoc()
    doc.lanes[0].width = 20
    expect(errorsOf(doc).join()).toMatch(/宽度/)
  })

  it('泳道缺少 name（类型声明它必填，校验也要查）', () => {
    const doc = validDoc()
    // @ts-expect-error 故意造一个不合法的文档来验证校验器
    doc.lanes[0].name = undefined
    expect(errorsOf(doc).join()).toMatch(/name/)
  })

  it('步骤数小于 1', () => {
    expect(errorsOf({ ...validDoc(), stepCount: 0 }).join()).toMatch(/stepCount/)
  })

  it('节点的 laneId 不存在', () => {
    const doc = validDoc()
    doc.nodes[0].laneId = 'nope'
    expect(errorsOf(doc).join()).toMatch(/laneId/)
  })

  it('节点 step 越界（大于 stepCount）', () => {
    const doc = validDoc()
    doc.nodes[0].step = 99
    expect(errorsOf(doc).join()).toMatch(/step/)
  })

  it('两个节点落在同一格', () => {
    const doc = validDoc()
    doc.nodes[1].laneId = 'l1'
    doc.nodes[1].step = 1
    expect(errorsOf(doc).join()).toMatch(/格子/)
  })

  it('节点 id 重复', () => {
    const doc = validDoc()
    doc.nodes[1].id = 'A01'
    expect(errorsOf(doc).join()).toMatch(/id 重复/)
  })

  it('连线指向不存在的节点', () => {
    const doc = validDoc()
    doc.edges[0].target = 'nope'
    expect(errorsOf(doc).join()).toMatch(/target/)
  })

  it('连线自环（两端同一节点）', () => {
    const doc = validDoc()
    doc.edges[0].target = 'A01'
    expect(errorsOf(doc).join()).toMatch(/自环/)
  })

  it('连线 kind 取值非法', () => {
    const doc = validDoc()
    doc.edges[0].kind = 'weird' as FlowDoc['edges'][number]['kind']
    expect(errorsOf(doc).join()).toMatch(/kind/)
  })

  it('一次报出多个错误而不是只报第一个', () => {
    const doc = validDoc()
    doc.schemaVersion = 9 as 1
    doc.stepCount = 0
    expect(errorsOf(doc).length).toBeGreaterThanOrEqual(2)
  })
})