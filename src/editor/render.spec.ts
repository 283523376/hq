import { describe, expect, it } from 'vitest'

import {
  buildRenderData,
  isBackgroundType,
  LANE_HEADER_TYPE,
  NODE_TYPE,
  STEP_BAND_TYPE,
} from './render'
import { harvestInboundDoc } from './seed/harvestInbound'
import type { FlowDoc } from './types'

function smallDoc(): FlowDoc {
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
    stepCount: 2,
    nodes: [{ id: 'A01', text: '一', laneId: 'l1', step: 1, kind: 'process' }],
    edges: [],
  }
}

describe('buildRenderData', () => {
  it('为每条泳道生成一个表头节点', () => {
    const { nodes } = buildRenderData(smallDoc())
    const headers = nodes.filter((node) => node.type === LANE_HEADER_TYPE)
    expect(headers.map((node) => node.id)).toEqual(['bg-header-l1', 'bg-header-l2'])
  })

  it('为每个步骤生成一个行带节点，行带横跨整图', () => {
    const { nodes } = buildRenderData(smallDoc())
    const bands = nodes.filter((node) => node.type === STEP_BAND_TYPE)
    expect(bands).toHaveLength(2)
    expect(bands[0].properties.width).toBe(410)
  })

  it('用户节点坐标来自几何推导，不是文档里存的', () => {
    const { nodes } = buildRenderData(smallDoc())
    const node = nodes.find((item) => item.id === 'A01')
    expect(node).toBeDefined()
    expect(node!.type).toBe(NODE_TYPE)
    expect(node!.x).toBe(160)
    expect(node!.y).toBe(90)
    expect(node!.properties.width).toBe(180)
    expect(node!.properties.badge).toBe('A01')
  })

  it('正文可用宽度与几何算高度时用的是同一个值', () => {
    const { nodes } = buildRenderData(smallDoc())
    const node = nodes.find((item) => item.id === 'A01')!
    // 节点宽 180 − 两侧正文内边距 10*2 = 160
    expect(node.properties.textMaxWidth).toBe(160)
  })

  it('背景节点的 zIndex 低于用户节点', () => {
    const { nodes } = buildRenderData(smallDoc())
    const band = nodes.find((item) => item.type === STEP_BAND_TYPE)!
    const node = nodes.find((item) => item.id === 'A01')!
    expect(band.zIndex!).toBeLessThan(node.zIndex!)
  })

  it('背景节点都标记了 isBackground', () => {
    const { nodes } = buildRenderData(smallDoc())
    const backgrounds = nodes.filter((node) => node.type !== NODE_TYPE)
    expect(backgrounds.length).toBeGreaterThan(0)
    for (const node of backgrounds) {
      expect(node.properties.isBackground).toBe(true)
    }
  })

  it('连线带上 kind 与主题，异常线是虚线', () => {
    const doc = smallDoc()
    doc.nodes.push({ id: 'A02', text: '二', laneId: 'l2', step: 2, kind: 'process' })
    doc.edges.push({ id: 'e1', source: 'A01', target: 'A02', kind: 'exception', label: '不合格品' })
    const { edges } = buildRenderData(doc)
    expect(edges).toHaveLength(1)
    expect(edges[0].text).toBe('不合格品')
    expect(edges[0].properties.kind).toBe('exception')
    // properties 是 Record<string, unknown>，取嵌套值要先收窄类型
    const theme = edges[0].properties.theme as { dashArray?: string }
    expect(theme.dashArray).toBeDefined()
  })
})

describe('isBackgroundType', () => {
  it('区分背景与用户元素', () => {
    expect(isBackgroundType('lane-header')).toBe(true)
    expect(isBackgroundType('step-band')).toBe(true)
    expect(isBackgroundType('flow-node')).toBe(false)
    expect(isBackgroundType('flow-edge')).toBe(false)
  })
})

describe('示例图能转成渲染数据', () => {
  it('21 个用户节点 + 6 表头 + 12 行带', () => {
    const { nodes, edges } = buildRenderData(harvestInboundDoc)
    expect(nodes.filter((node) => node.type === NODE_TYPE)).toHaveLength(21)
    expect(nodes.filter((node) => node.type === LANE_HEADER_TYPE)).toHaveLength(6)
    expect(nodes.filter((node) => node.type === STEP_BAND_TYPE)).toHaveLength(12)
    expect(edges).toHaveLength(harvestInboundDoc.edges.length)
  })

  it('所有坐标都是有限数字（不出现 NaN）', () => {
    const { nodes } = buildRenderData(harvestInboundDoc)
    for (const node of nodes) {
      expect(Number.isFinite(node.x)).toBe(true)
      expect(Number.isFinite(node.y)).toBe(true)
      expect(node.properties.width as number).toBeGreaterThan(0)
      expect(node.properties.height as number).toBeGreaterThan(0)
    }
  })

  it('同格并排的 A13/A14 拿到不同的 x（offsetX 生效）', () => {
    const { nodes } = buildRenderData(harvestInboundDoc)
    const a13 = nodes.find((node) => node.id === 'A13')!
    const a14 = nodes.find((node) => node.id === 'A14')!
    expect(a13.x).not.toBe(a14.x)
    expect(Math.abs(a14.x - a13.x)).toBeGreaterThan(0)
  })
})