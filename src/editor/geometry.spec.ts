import { describe, expect, it } from 'vitest'

import {
  docSize,
  laneBounds,
  laneColorOf,
  laneHeaderBounds,
  laneLeft,
  nodeGeom,
  stepBandBounds,
} from './geometry'
import type { FlowDoc } from './types'

function makeDoc(nodes: FlowDoc['nodes'] = []): FlowDoc {
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
    nodes,
    edges: [],
  }
}

const oneNode: FlowDoc['nodes'] = [
  { id: 'A01', text: '甲', laneId: 'l1', step: 1, kind: 'process' },
]

describe('laneLeft', () => {
  it('第一条泳道从步骤号列右边开始', () => {
    expect(laneLeft(makeDoc(), 'l1')).toBe(60)
  })

  it('后续泳道按前一条的宽度累进', () => {
    expect(laneLeft(makeDoc(), 'l2')).toBe(260)
  })

  it('未知泳道 id 显式抛错，而不是静默放到最右边', () => {
    expect(() => laneLeft(makeDoc(), 'nope')).toThrow(/未知泳道/)
  })
})

describe('增删泳道后自动重排', () => {
  it('在前一条泳道左边插一条更宽的泳道，会把后面的列整体右移', () => {
    const doc = makeDoc()
    const shifted: FlowDoc = {
      ...doc,
      lanes: [{ id: 'l0', name: '新', color: 'green', width: 300 }, ...doc.lanes],
    }
    expect(laneLeft(shifted, 'l1')).toBe(360)
    expect(laneLeft(shifted, 'l2')).toBe(560)
    // 纯函数不改入参
    expect(laneLeft(doc, 'l1')).toBe(60)
  })
})

describe('laneBounds / laneHeaderBounds', () => {
  it('泳道体在表头之下，高度 = 行数 × 行高', () => {
    expect(laneBounds(makeDoc(), 'l2')).toEqual({
      left: 260,
      top: 40,
      width: 150,
      height: 300,
    })
  })

  it('表头在顶部，高度 = headerHeight', () => {
    expect(laneHeaderBounds(makeDoc(), 'l1')).toEqual({
      left: 60,
      top: 0,
      width: 200,
      height: 40,
    })
  })
})

describe('stepBandBounds', () => {
  it('第 2 行带的 top = 表头高 + 1 个行高', () => {
    expect(stepBandBounds(makeDoc(), 2)).toEqual({
      left: 0,
      top: 140,
      width: 410,
      height: 100,
    })
  })
})

describe('nodeGeom', () => {
  it('宽度 = 列宽 − 两侧内边距，x 落在列中心，y 落在行带中线', () => {
    const geom = nodeGeom(makeDoc(oneNode), oneNode[0])
    expect(geom.width).toBe(180)
    expect(geom.x).toBe(160)
    expect(geom.y).toBe(90)
  })

  it('高度由文字行数决定：单行 = 行高 + 上下内边距', () => {
    const geom = nodeGeom(makeDoc(oneNode), oneNode[0])
    expect(geom.height).toBe(18 + 20)
  })

  it('文字变多则节点变高（不是手调的）', () => {
    const tall: FlowDoc['nodes'] = [
      { id: 'A02', text: '甲\n甲\n甲', laneId: 'l1', step: 2, kind: 'process' },
    ]
    const geom = nodeGeom(makeDoc(tall), tall[0])
    expect(geom.height).toBe(18 * 3 + 20)
  })

  it('节点落在所属泳道中心，换泳道即换 x 与配色', () => {
    const node: FlowDoc['nodes'][number] = {
      id: 'A03',
      text: '甲',
      laneId: 'l2',
      step: 1,
      kind: 'process',
    }
    const geom = nodeGeom(makeDoc([node]), node)
    expect(geom.x).toBe(335)
    expect(laneColorOf(makeDoc([node]), 'l2')).toBe('red')
  })

  it('未知泳道 id 抛错', () => {
    const bad: FlowDoc['nodes'] = [
      { id: 'A04', text: '甲', laneId: 'nope', step: 1, kind: 'process' },
    ]
    expect(() => nodeGeom(makeDoc(bad), bad[0])).toThrow(/未知泳道/)
  })
})

describe('docSize', () => {
  it('宽度 = 步骤号列 + 所有列宽；高度 = 表头 + 行数 × 行高', () => {
    expect(docSize(makeDoc())).toEqual({ width: 410, height: 340 })
  })
})