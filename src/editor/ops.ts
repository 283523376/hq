/**
 * 编辑操作。**全部是纯函数：文档进、文档出**，不改入参、不碰 DOM、不碰 LogicFlow。
 *
 * 这样安排的理由：「增删泳道自动重排」「步骤数调小不丢节点」这些正确性，
 * 用手点很难覆盖全，而纯函数可以直接单测。UI 只负责把事件翻译成这些调用。
 *
 * 一条贯穿全部操作的原则：**绝不静默删除或覆盖用户的节点**。放不下就抛错、
 * 越界就上移、泳道里还有节点就拒绝删除，把决定权留给用户。
 */
import { laneLeft } from './geometry'
import type { FlowDoc, FlowNode, Lane, LaneColor } from './types'

export interface Cell {
  laneId: string
  step: number
}

export interface Point {
  x: number
  y: number
}

const LANE_COLORS: LaneColor[] = ['blue', 'orange', 'green', 'purple', 'red']

function findLane(doc: FlowDoc, laneId: string): Lane {
  const lane = doc.lanes.find((item) => item.id === laneId)
  if (!lane) throw new Error(`未知泳道 id: ${laneId}`)
  return lane
}

function findNode(doc: FlowDoc, nodeId: string): FlowNode {
  const node = doc.nodes.find((item) => item.id === nodeId)
  if (!node) throw new Error(`未知节点 id: ${nodeId}`)
  return node
}

/** 画布坐标 → 格子。落在步骤号列、表头、行带之外或最右泳道之后都返回 null */
export function pointToCell(doc: FlowDoc, point: Point): Cell | null {
  if (point.x < doc.grid.stepColumnWidth) return null
  const lane = doc.lanes.find((item) => {
    const left = laneLeft(doc, item.id)
    return point.x >= left && point.x < left + item.width
  })
  if (!lane) return null
  const step = Math.floor((point.y - doc.grid.headerHeight) / doc.grid.rowHeight) + 1
  if (step < 1 || step > doc.stepCount) return null
  return { laneId: lane.id, step }
}

export function isCellOccupied(doc: FlowDoc, cell: Cell): boolean {
  return doc.nodes.some((node) => node.laneId === cell.laneId && node.step === cell.step)
}

/**
 * 从目标格子往下找第一个空格子，下到头再往上找。返回 null 表示该列已满。
 * 「优先往下」是已确认的交互决定：拖拽不该被打断。
 */
export function findFreeCell(doc: FlowDoc, cell: Cell): Cell | null {
  for (let step = cell.step; step <= doc.stepCount; step += 1) {
    if (!isCellOccupied(doc, { laneId: cell.laneId, step })) return { laneId: cell.laneId, step }
  }
  for (let step = cell.step - 1; step >= 1; step -= 1) {
    if (!isCellOccupied(doc, { laneId: cell.laneId, step })) return { laneId: cell.laneId, step }
  }
  return null
}

/** 接着现有最大编号排出下一个可用编号：A21 → A22 */
export function nextNodeId(doc: FlowDoc): string {
  let max = 0
  for (const node of doc.nodes) {
    const matched = /^A(\d+)$/.exec(node.id)
    if (matched) max = Math.max(max, Number(matched[1]))
  }
  return `A${String(max + 1).padStart(2, '0')}`
}

export function addNode(doc: FlowDoc, cell: Cell, text: string): FlowDoc {
  const free = findFreeCell(doc, cell)
  if (!free) throw new Error(`泳道 ${cell.laneId} 已满，放不下新节点`)
  return {
    ...doc,
    nodes: [
      ...doc.nodes,
      { id: nextNodeId(doc), text, laneId: free.laneId, step: free.step, kind: 'process' },
    ],
  }
}

export function moveNode(doc: FlowDoc, nodeId: string, cell: Cell): FlowDoc {
  const node = findNode(doc, nodeId)
  if (node.laneId === cell.laneId && node.step === cell.step) return doc
  // 先把该节点原位置腾出来再找空格，否则会把自己算成占用
  const without: FlowDoc = { ...doc, nodes: doc.nodes.filter((item) => item.id !== nodeId) }
  const free = findFreeCell(without, cell)
  if (!free) throw new Error(`泳道 ${cell.laneId} 已满，放不下这个节点`)
  return {
    ...without,
    nodes: [...without.nodes, { ...node, laneId: free.laneId, step: free.step }],
  }
}

export function setNodeText(doc: FlowDoc, nodeId: string, text: string): FlowDoc {
  findNode(doc, nodeId)
  return {
    ...doc,
    nodes: doc.nodes.map((node) => (node.id === nodeId ? { ...node, text } : node)),
  }
}

export function setEdgeLabel(doc: FlowDoc, edgeId: string, label: string): FlowDoc {
  if (!doc.edges.some((edge) => edge.id === edgeId)) throw new Error(`未知连线 id: ${edgeId}`)
  return {
    ...doc,
    edges: doc.edges.map((edge) => (edge.id === edgeId ? { ...edge, label } : edge)),
  }
}

/** 删节点时连带删掉挂在它上面的连线——否则会留下指向不存在节点的悬空边 */
export function removeNodes(doc: FlowDoc, nodeIds: string[]): FlowDoc {
  const doomed = new Set(nodeIds)
  return {
    ...doc,
    nodes: doc.nodes.filter((node) => !doomed.has(node.id)),
    edges: doc.edges.filter((edge) => !doomed.has(edge.source) && !doomed.has(edge.target)),
  }
}

export function setLaneName(doc: FlowDoc, laneId: string, name: string): FlowDoc {
  findLane(doc, laneId)
  return {
    ...doc,
    lanes: doc.lanes.map((lane) => (lane.id === laneId ? { ...lane, name } : lane)),
  }
}

/** 追加一条泳道：配色按五种循环，宽度取现有泳道平均值，名字给个可改的默认值 */
export function addLane(doc: FlowDoc): FlowDoc {
  const average =
    doc.lanes.length > 0
      ? Math.round(doc.lanes.reduce((sum, lane) => sum + lane.width, 0) / doc.lanes.length)
      : 200
  const index = doc.lanes.length
  return {
    ...doc,
    lanes: [
      ...doc.lanes,
      {
        id: `lane-new-${index + 1}`,
        name: `新泳道${index + 1}`,
        color: LANE_COLORS[index % LANE_COLORS.length],
        width: average,
      },
    ],
  }
}

/**
 * 删泳道。泳道内还有节点时**拒绝**，不连带删除节点；最后一条也不许删。
 */
export function removeLane(
  doc: FlowDoc,
  laneId: string,
): { ok: true; doc: FlowDoc } | { ok: false; error: string } {
  findLane(doc, laneId)
  if (doc.lanes.length <= 1) return { ok: false, error: '至少要保留一条泳道' }
  const occupied = doc.nodes.filter((node) => node.laneId === laneId)
  if (occupied.length > 0) {
    return {
      ok: false,
      error: `该泳道还有节点（${occupied.map((node) => node.id).join('、')}），请先移走或删除它们`,
    }
  }
  return { ok: true, doc: { ...doc, lanes: doc.lanes.filter((lane) => lane.id !== laneId) } }
}

/**
 * 改步骤数。减少时越界节点自动上移到最后一格；同格冲突继续向上顺延。
 * **绝不删除节点**——这是硬约束，用户的数据不由我们替他丢弃。
 *
 * 格子确实不够时**拒绝这次操作并抛错**，而不是把节点堆成重复格留下坏状态：
 * 拒绝是可恢复的，坏数据不是。
 * 注意容量要**按泳道逐条**算，不能只看总数——节点不会跨泳道移动，
 * 所以「2 条泳道各 3 个节点、步骤减到 2」是放不下的（每列只有 2 格却各有 3 个）。
 */
export function setStepCount(doc: FlowDoc, stepCount: number): FlowDoc {
  const next = Math.max(1, Math.floor(stepCount))
  if (next >= doc.stepCount) return { ...doc, stepCount: next }

  for (const lane of doc.lanes) {
    const count = doc.nodes.filter((node) => node.laneId === lane.id).length
    if (count > next) {
      throw new Error(`放不下：泳道「${lane.name}」里有 ${count} 个节点，步骤数却要减到 ${next}`)
    }
  }

  const taken = new Set<string>()
  const nodes = doc.nodes.map((node) => {
    let step = Math.min(node.step, next)
    while (step > 1 && taken.has(`${node.laneId}#${step}`)) step -= 1
    taken.add(`${node.laneId}#${step}`)
    return { ...node, step }
  })
  return { ...doc, stepCount: next, nodes }
}