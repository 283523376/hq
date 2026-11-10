/**
 * 文档 → LogicFlow 渲染配置。这是「数据」与「画布」之间唯一的转换点。
 *
 * 做成纯函数有两个好处：几何/配色/背景压制全部可单测；以及**导出安全由结构保证**
 * —— 背景是渲染时才生成的，不在文档里，所以文档天然干净。
 */
import type LogicFlow from '@logicflow/core'

import {
  FONT_SIZE,
  TEXT_PAD_X,
  docSize,
  laneColorOf,
  laneHeaderBounds,
  nodeGeom,
  stepBandBounds,
} from './geometry'
import { EDGE_THEME, LANE_THEME } from './theme'
import { FLOW_EDGE } from './edges'
import { FLOW_NODE, LANE_HEADER, STEP_BAND } from './nodes'
import type { FlowDoc } from './types'

export const NODE_TYPE = 'flow-node'
export const EDGE_TYPE = 'flow-edge'
export const LANE_HEADER_TYPE = 'lane-header'
export const STEP_BAND_TYPE = 'step-band'

/** 背景压在用户元素下面：行带最低，表头次之，用户节点在其上 */
const Z_STEP_BAND = -3
const Z_LANE_HEADER = -2
const Z_USER_NODE = 1

export interface RenderNodeConfig {
  id: string
  type: string
  x: number
  y: number
  text?: string
  zIndex?: number
  properties: Record<string, unknown>
}

export interface RenderEdgeConfig {
  id: string
  type: string
  sourceNodeId: string
  targetNodeId: string
  text?: string
  properties: Record<string, unknown>
}

export interface RenderData {
  nodes: RenderNodeConfig[]
  edges: RenderEdgeConfig[]
}

/** 背景判定：序列化（阶段 B）与选中逻辑都靠它把背景排除在外 */
export function isBackgroundType(type: string): boolean {
  return type === LANE_HEADER_TYPE || type === STEP_BAND_TYPE
}

export function buildRenderData(doc: FlowDoc): RenderData {
  const { width: docWidth } = docSize(doc)
  const nodes: RenderNodeConfig[] = []

  if (docWidth <= 0) {
    // 防御：空文档也要给出可读错误，而不是产出 0 宽节点
    throw new Error('buildRenderData: 文档宽度为 0，无法渲染')
  }

  // 1. 行带（含左侧步骤号列与斑马纹）
  for (let step = 1; step <= doc.stepCount; step += 1) {
    const band = stepBandBounds(doc, step)
    nodes.push({
      id: `bg-step-${step}`,
      type: STEP_BAND_TYPE,
      x: band.left + band.width / 2,
      y: band.top + band.height / 2,
      zIndex: Z_STEP_BAND,
      properties: {
        isBackground: true,
        width: band.width,
        height: band.height,
        step,
        zebra: step % 2 === 0,
        stepColumnWidth: doc.grid.stepColumnWidth,
        fontSize: 13,
      },
    })
  }

  // 2. 泳道表头
  for (const lane of doc.lanes) {
    const header = laneHeaderBounds(doc, lane.id)
    const theme = LANE_THEME[lane.color]
    nodes.push({
      id: `bg-header-${lane.id}`,
      type: LANE_HEADER_TYPE,
      x: header.left + header.width / 2,
      y: header.top + header.height / 2,
      text: lane.name,
      zIndex: Z_LANE_HEADER,
      properties: {
        isBackground: true,
        width: header.width,
        height: header.height,
        laneId: lane.id,
        color: lane.color,
        headerFill: theme.headerFill,
        headerText: theme.headerText,
      },
    })
  }

  // 3. 用户节点
  for (const node of doc.nodes) {
    const geom = nodeGeom(doc, node)
    const theme = LANE_THEME[laneColorOf(doc, node.laneId)]
    nodes.push({
      id: node.id,
      type: NODE_TYPE,
      x: geom.x,
      y: geom.y,
      text: node.text,
      zIndex: Z_USER_NODE,
      properties: {
        width: geom.width,
        height: geom.height,
        radius: 6,
        badge: node.id,
        nodeFill: theme.nodeFill,
        nodeStroke: theme.nodeStroke,
        nodeText: theme.nodeText,
        // 正文可用宽度：与 nodeGeom 内部算高度时用的是同一个式子
        textMaxWidth: geom.width - TEXT_PAD_X * 2,
        fontSize: FONT_SIZE,
      },
    })
  }

  const edges: RenderEdgeConfig[] = doc.edges.map((edge) => ({
    id: edge.id,
    type: EDGE_TYPE,
    sourceNodeId: edge.source,
    targetNodeId: edge.target,
    text: edge.label,
    properties: {
      kind: edge.kind,
      theme: EDGE_THEME[edge.kind],
    },
  }))

  return { nodes, edges }
}

/** 注册本编辑器用到的四个自定义元素类型 */
export function registerEditorNodes(lf: LogicFlow): void {
  lf.register(FLOW_NODE)
  lf.register(LANE_HEADER)
  lf.register(STEP_BAND)
  lf.register(FLOW_EDGE)
}