import { describe, expect, it } from 'vitest'

import {
  addLane,
  addNode,
  findFreeCell,
  isCellOccupied,
  moveNode,
  nextNodeId,
  pointToCell,
  removeLane,
  removeNodes,
  setEdgeLabel,
  setLaneName,
  setNodeText,
  setStepCount,
} from './ops'
import type { FlowDoc } from './types'

function makeDoc(): FlowDoc {
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
      { id: 'A02', text: '二', laneId: 'l1', step: 2, kind: 'process' },
    ],
    edges: [{ id: 'e1', source: 'A01', target: 'A02', kind: 'main' }],
  }
}

describe('pointToCell', () => {
  it('落在泳道与行带里就换算成格子', () => {
    // x=100 落在 l1（60..260），y=150 落在第 2 行带（40..140 是第 1 带）
    expect(pointToCell(makeDoc(), { x: 100, y: 150 })).toEqual({ laneId: 'l1', step: 2 })
  })

  it('落在左侧步骤号列里返回 null', () => {
    expect(pointToCell(makeDoc(), { x: 30, y: 150 })).toBeNull()
  })

  it('落在表头里返回 null', () => {
    expect(pointToCell(makeDoc(), { x: 100, y: 10 })).toBeNull()
  })

  it('落在行带之外（超出步骤数）返回 null', () => {
    expect(pointToCell(makeDoc(), { x: 100, y: 9999 })).toBeNull()
  })

  it('落在最右泳道之后返回 null', () => {
    expect(pointToCell(makeDoc(), { x: 9999, y: 150 })).toBeNull()
  })
})

describe('findFreeCell', () => {
  it('目标格空着就用它', () => {
    expect(findFreeCell(makeDoc(), { laneId: 'l1', step: 3 })).toEqual({ laneId: 'l1', step: 3 })
  })

  it('目标格被占就向下顺延', () => {
    expect(findFreeCell(makeDoc(), { laneId: 'l1', step: 1 })).toEqual({ laneId: 'l1', step: 3 })
  })

  it('下到头再向上找', () => {
    const doc = makeDoc()
    doc.nodes = [
      { id: 'A01', text: '一', laneId: 'l1', step: 2, kind: 'process' },
      { id: 'A02', text: '二', laneId: 'l1', step: 3, kind: 'process' },
    ]
    expect(findFreeCell(doc, { laneId: 'l1', step: 2 })).toEqual({ laneId: 'l1', step: 1 })
  })

  it('整列占满时返回 null', () => {
    const doc = makeDoc()
    doc.stepCount = 1
    doc.nodes = [{ id: 'A01', text: '一', laneId: 'l1', step: 1, kind: 'process' }]
    expect(findFreeCell(doc, { laneId: 'l1', step: 1 })).toBeNull()
  })
})

describe('isCellOccupied', () => {
  it('区分占用与空闲', () => {
    expect(isCellOccupied(makeDoc(), { laneId: 'l1', step: 1 })).toBe(true)
    expect(isCellOccupied(makeDoc(), { laneId: 'l1', step: 3 })).toBe(false)
  })
})

describe('nextNodeId', () => {
  it('接着最大编号往后排并补零', () => {
    expect(nextNodeId(makeDoc())).toBe('A03')
  })

  it('忽略不匹配的编号格式，从 A01 起', () => {
    const doc = makeDoc()
    doc.nodes = [{ id: '自定义', text: '一', laneId: 'l1', step: 1, kind: 'process' }]
    expect(nextNodeId(doc)).toBe('A01')
  })
})

describe('addNode', () => {
  it('在空格子建节点并自动编号', () => {
    const doc = addNode(makeDoc(), { laneId: 'l2', step: 1 }, '新')
    const added = doc.nodes.find((node) => node.id === 'A03')
    expect(added).toEqual({ id: 'A03', text: '新', laneId: 'l2', step: 1, kind: 'process' })
  })

  it('格子被占时顺延而不是覆盖', () => {
    const doc = addNode(makeDoc(), { laneId: 'l1', step: 1 }, '新')
    expect(doc.nodes).toHaveLength(3)
    expect(doc.nodes[2].step).toBe(3)
  })

  it('不修改原文档', () => {
    const before = makeDoc()
    addNode(before, { laneId: 'l2', step: 1 }, '新')
    expect(before.nodes).toHaveLength(2)
  })

  it('整列占满时抛错而不是丢节点', () => {
    const doc = makeDoc()
    doc.stepCount = 1
    doc.nodes = [{ id: 'A01', text: '一', laneId: 'l1', step: 1, kind: 'process' }]
    expect(() => addNode(doc, { laneId: 'l1', step: 1 }, '新')).toThrow(/放不下/)
  })
})

describe('moveNode', () => {
  it('跨泳道移动即改 laneId 与 step', () => {
    const doc = moveNode(makeDoc(), 'A01', { laneId: 'l2', step: 3 })
    const moved = doc.nodes.find((node) => node.id === 'A01')
    expect(moved).toMatchObject({ laneId: 'l2', step: 3 })
  })

  it('目标格被别的节点占着就顺延，而不是覆盖对方', () => {
    const base = makeDoc()
    base.nodes = [
      { id: 'A01', text: '一', laneId: 'l1', step: 1, kind: 'process' },
      { id: 'A02', text: '二', laneId: 'l1', step: 3, kind: 'process' },
    ]
    const doc = moveNode(base, 'A02', { laneId: 'l1', step: 1 })
    // 被占的格子不覆盖：A01 留在原地，A02 顺延到下一个空格
    expect(doc.nodes.find((node) => node.id === 'A01')!.step).toBe(1)
    expect(doc.nodes.find((node) => node.id === 'A02')!.step).toBe(2)
  })

  it('拖到自己原来那格的紧邻被占格时原地不动（不产生意外跳行）', () => {
    // A02 在 2，拖到被 A01 占着的 1：先腾出自己那格，于是又落回 2 —— 等价于没动
    const doc = moveNode(makeDoc(), 'A02', { laneId: 'l1', step: 1 })
    expect(doc.nodes.find((node) => node.id === 'A02')!.step).toBe(2)
  })

  it('移到自己的格子是原地不动', () => {
    const doc = moveNode(makeDoc(), 'A01', { laneId: 'l1', step: 1 })
    expect(doc.nodes.find((node) => node.id === 'A01')).toMatchObject({ laneId: 'l1', step: 1 })
  })

  it('未知节点 id 抛错', () => {
    expect(() => moveNode(makeDoc(), 'nope', { laneId: 'l1', step: 3 })).toThrow(/未知节点/)
  })
})

describe('setNodeText / setEdgeLabel', () => {
  it('改文字只动那一个节点', () => {
    const doc = setNodeText(makeDoc(), 'A01', '改了')
    expect(doc.nodes.find((node) => node.id === 'A01')!.text).toBe('改了')
    expect(doc.nodes.find((node) => node.id === 'A02')!.text).toBe('二')
  })

  it('改连线标签', () => {
    expect(setEdgeLabel(makeDoc(), 'e1', '合格').edges[0].label).toBe('合格')
  })

  it('未知 id 抛错', () => {
    expect(() => setNodeText(makeDoc(), 'nope', 'x')).toThrow(/未知节点/)
    expect(() => setEdgeLabel(makeDoc(), 'nope', 'x')).toThrow(/未知连线/)
  })
})

describe('removeNodes', () => {
  it('删节点时连带删掉挂在它上面的连线', () => {
    const doc = removeNodes(makeDoc(), ['A01'])
    expect(doc.nodes.map((node) => node.id)).toEqual(['A02'])
    expect(doc.edges).toHaveLength(0)
  })

  it('删不存在的 id 不报错、不改动', () => {
    expect(removeNodes(makeDoc(), ['nope']).nodes).toHaveLength(2)
  })
})

describe('setLaneName / addLane', () => {
  it('改泳道名', () => {
    expect(setLaneName(makeDoc(), 'l1', '新名').lanes[0].name).toBe('新名')
  })

  it('添加泳道追加在末尾，配色按五种循环，宽度取现有泳道平均', () => {
    const doc = addLane(makeDoc())
    expect(doc.lanes).toHaveLength(3)
    expect(doc.lanes[2].width).toBeGreaterThan(0)
    expect(doc.lanes[2].name).toBeTruthy()
  })

  it('未知泳道 id 抛错', () => {
    expect(() => setLaneName(makeDoc(), 'nope', 'x')).toThrow(/未知泳道/)
  })
})

describe('removeLane', () => {
  it('空泳道可以删', () => {
    const result = removeLane(makeDoc(), 'l2')
    expect(result.ok).toBe(true)
    if (result.ok) expect(result.doc.lanes).toHaveLength(1)
  })

  it('泳道内还有节点时拒绝删除，文档不变', () => {
    const result = removeLane(makeDoc(), 'l1')
    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.error).toMatch(/还有节点/)
  })

  it('最后一条泳道不许删', () => {
    const doc = makeDoc()
    doc.nodes = []
    const result = removeLane(doc, 'l2')
    expect(result.ok).toBe(true)
    if (result.ok) expect(removeLane(result.doc, 'l1').ok).toBe(false)
  })

  it('未知泳道 id 抛错', () => {
    expect(() => removeLane(makeDoc(), 'nope')).toThrow(/未知泳道/)
  })
})

describe('setStepCount', () => {
  it('增加步骤不改动任何节点', () => {
    const doc = setStepCount(makeDoc(), 6)
    expect(doc.stepCount).toBe(6)
    expect(doc.nodes.map((node) => node.step)).toEqual([1, 2])
  })

  it('减少步骤时越界节点上移到最后一格', () => {
    const base = makeDoc()
    base.nodes = [{ ...base.nodes[1], step: 3 }]
    const doc = setStepCount(base, 2)
    expect(doc.stepCount).toBe(2)
    expect(doc.nodes[0].step).toBe(2)
  })

  it('减少步骤时绝不删除节点（总数不变）', () => {
    const base = makeDoc()
    base.nodes = [
      { id: 'A01', text: '一', laneId: 'l1', step: 1, kind: 'process' },
      { id: 'A02', text: '二', laneId: 'l2', step: 3, kind: 'process' },
    ]
    const doc = setStepCount(base, 2)
    expect(doc.nodes).toHaveLength(2)
    expect(doc.nodes.find((node) => node.id === 'A02')!.step).toBe(2)
  })

  it('上移后不产生重复格', () => {
    const base = makeDoc()
    base.nodes = [
      { id: 'A01', text: '一', laneId: 'l1', step: 3, kind: 'process' },
      { id: 'A02', text: '二', laneId: 'l1', step: 2, kind: 'process' },
    ]
    const doc = setStepCount(base, 2)
    const cells = doc.nodes.map((node) => `${node.laneId}#${node.step}`)
    expect(new Set(cells).size).toBe(cells.length)
  })

  it('格子确实不够时抛错，而不是把节点堆成重复格', () => {
    // 1 条泳道 × 1 步 = 1 格，却有 2 个节点
    const base = makeDoc()
    base.nodes = [
      { id: 'A01', text: '一', laneId: 'l1', step: 1, kind: 'process' },
      { id: 'A02', text: '二', laneId: 'l1', step: 2, kind: 'process' },
    ]
    expect(() => setStepCount(base, 1)).toThrow(/放不下/)
  })

  it('至少保留 1 步', () => {
    const base = makeDoc()
    base.nodes = [base.nodes[0]]
    expect(setStepCount(base, 0).stepCount).toBe(1)
    expect(setStepCount(base, -5).stepCount).toBe(1)
  })
})