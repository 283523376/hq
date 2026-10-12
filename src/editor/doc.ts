/**
 * 文档校验。
 *
 * 导入、本地加载、示例图完整性测试共用这一道门。目标是把坏数据挡在渲染之前
 * 并给出**可读的中文错误**，而不是让画布白屏或静默把节点画错位置。
 *
 * 一次收集所有错误（不是遇到第一个就返回），因为改 JSON 的人希望一次看全。
 */
import type { EdgeKind, FlowDoc, LaneColor } from './types'

export type ValidateResult = { ok: true; doc: FlowDoc } | { ok: false; errors: string[] }

const LANE_COLORS: LaneColor[] = ['blue', 'orange', 'green', 'purple', 'red']
const EDGE_KINDS: EdgeKind[] = ['main', 'exception']

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isPositiveNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0
}

export function validateDoc(input: unknown): ValidateResult {
  const errors: string[] = []

  if (!isObject(input)) {
    return { ok: false, errors: ['文档必须是一个对象'] }
  }

  if (input.schemaVersion !== 1) {
    errors.push(`schemaVersion 必须是 1，实际是 ${String(input.schemaVersion)}`)
  }

  // --- 网格参数 ---
  const grid = input.grid
  if (!isObject(grid)) {
    errors.push('缺少 grid 网格参数')
  } else {
    for (const key of ['headerHeight', 'stepColumnWidth', 'rowHeight', 'lanePadX', 'lanePadY']) {
      if (!isPositiveNumber(grid[key])) {
        errors.push(`grid.${key} 必须是正数`)
      }
    }
  }
  const lanePadX = isObject(grid) && isPositiveNumber(grid.lanePadX) ? grid.lanePadX : 0

  // --- 泳道 ---
  const lanes = input.lanes
  if (!Array.isArray(lanes) || lanes.length === 0) {
    errors.push('至少要有一条泳道')
  }
  const laneIds = new Set<string>()
  if (Array.isArray(lanes)) {
    lanes.forEach((lane, index) => {
      if (!isObject(lane)) {
        errors.push(`第 ${index + 1} 条泳道不是对象`)
        return
      }
      if (typeof lane.id !== 'string' || lane.id === '') {
        errors.push(`第 ${index + 1} 条泳道缺少 id`)
      } else if (laneIds.has(lane.id)) {
        errors.push(`泳道 id 重复：${lane.id}`)
      } else {
        laneIds.add(lane.id)
      }
      // name 是必填：缺了它表头会渲染成空白，而类型却在说谎
      if (typeof lane.name !== 'string') {
        errors.push(`泳道 ${String(lane.id)} 缺少 name`)
      }
      if (!LANE_COLORS.includes(lane.color as LaneColor)) {
        errors.push(`泳道 ${String(lane.id)} 的 color 非法：${String(lane.color)}`)
      }
      if (!isPositiveNumber(lane.width)) {
        errors.push(`泳道 ${String(lane.id)} 的宽度必须是正数`)
      } else if (lane.width - lanePadX * 2 <= 0) {
        errors.push(`泳道 ${String(lane.id)} 宽度 ${lane.width} 扣掉内边距后放不下节点`)
      }
    })
  }

  // --- 步骤数 ---
  const stepCount = input.stepCount
  if (typeof stepCount !== 'number' || !Number.isInteger(stepCount) || stepCount < 1) {
    errors.push('stepCount 必须是不小于 1 的整数')
  }
  const maxStep = typeof stepCount === 'number' ? stepCount : 0

  // --- 节点 ---
  const nodes = input.nodes
  const nodeIds = new Set<string>()
  const occupied = new Set<string>()
  if (!Array.isArray(nodes)) {
    errors.push('nodes 必须是数组')
  } else {
    nodes.forEach((node, index) => {
      if (!isObject(node)) {
        errors.push(`第 ${index + 1} 个节点不是对象`)
        return
      }
      if (typeof node.id !== 'string' || node.id === '') {
        errors.push(`第 ${index + 1} 个节点缺少 id`)
      } else if (nodeIds.has(node.id)) {
        errors.push(`节点 id 重复：${node.id}`)
      } else {
        nodeIds.add(node.id)
      }
      if (typeof node.text !== 'string') {
        errors.push(`节点 ${String(node.id)} 缺少 text`)
      }
      if (typeof node.laneId !== 'string' || !laneIds.has(node.laneId)) {
        errors.push(`节点 ${String(node.id)} 的 laneId 不存在：${String(node.laneId)}`)
      }
      if (typeof node.step !== 'number' || !Number.isInteger(node.step)) {
        errors.push(`节点 ${String(node.id)} 的 step 必须是整数`)
      } else if (maxStep > 0 && (node.step < 1 || node.step > maxStep)) {
        errors.push(`节点 ${String(node.id)} 的 step ${node.step} 超出 1..${maxStep}`)
      } else if (typeof node.laneId === 'string') {
        const cell = `${node.laneId}#${node.step}`
        if (occupied.has(cell)) {
          errors.push(`格子被重复占用（同一泳道同一格放了多个节点）：${cell}`)
        } else {
          occupied.add(cell)
        }
      }
      if (node.kind !== 'process') {
        errors.push(`节点 ${String(node.id)} 的 kind 非法：${String(node.kind)}`)
      }
    })
  }

  // --- 连线 ---
  const edges = input.edges
  const edgeIds = new Set<string>()
  if (!Array.isArray(edges)) {
    errors.push('edges 必须是数组')
  } else {
    edges.forEach((edge, index) => {
      if (!isObject(edge)) {
        errors.push(`第 ${index + 1} 条连线不是对象`)
        return
      }
      if (typeof edge.id !== 'string' || edge.id === '') {
        errors.push(`第 ${index + 1} 条连线缺少 id`)
      } else if (edgeIds.has(edge.id)) {
        errors.push(`连线 id 重复：${edge.id}`)
      } else {
        edgeIds.add(edge.id)
      }
      if (typeof edge.source !== 'string' || !nodeIds.has(edge.source)) {
        errors.push(`连线 ${String(edge.id)} 的 source 不存在：${String(edge.source)}`)
      }
      if (typeof edge.target !== 'string' || !nodeIds.has(edge.target)) {
        errors.push(`连线 ${String(edge.id)} 的 target 不存在：${String(edge.target)}`)
      }
      if (typeof edge.source === 'string' && edge.source === edge.target) {
        errors.push(`连线 ${String(edge.id)} 是自环（source 与 target 相同）`)
      }
      if (!EDGE_KINDS.includes(edge.kind as EdgeKind)) {
        errors.push(`连线 ${String(edge.id)} 的 kind 非法：${String(edge.kind)}`)
      }
      if (edge.label !== undefined && typeof edge.label !== 'string') {
        errors.push(`连线 ${String(edge.id)} 的 label 必须是字符串`)
      }
    })
  }

  if (errors.length > 0) return { ok: false, errors }
  return { ok: true, doc: input as unknown as FlowDoc }
}