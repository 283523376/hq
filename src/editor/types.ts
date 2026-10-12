/**
 * 泳道流程图文档模型。
 *
 * 唯一事实源，**不存坐标**：节点只记 {laneId, step}，像素位置由 geometry.ts 推导。
 * 模型对业务内容完全无知：text 与 label 都是自由文本，不预设取值、不做校验。
 */

export type LaneColor = 'blue' | 'orange' | 'green' | 'purple' | 'red'

export interface GridConfig {
  /** 泳道表头行高 */
  headerHeight: number
  /** 左侧「步骤」号列宽度 */
  stepColumnWidth: number
  /** 每个步骤行的高度 */
  rowHeight: number
  /** 节点相对泳道左右的内边距：节点宽 = lane.width - 2 * lanePadX */
  lanePadX: number
  /** 节点相对行带上下的呼吸空间 */
  lanePadY: number
}

export interface Lane {
  id: string
  name: string
  /** 决定表头与该列节点配色，不逐节点存色 */
  color: LaneColor
  width: number
}

/** 预留扩展，本期只此一种 */
export type NodeKind = 'process'

export interface FlowNode {
  /** A01 风格编号，可手改 */
  id: string
  /** 换行由宽度自动计算；含换行符则硬换行优先 */
  text: string
  laneId: string
  /** 1-based 行号 */
  step: number
  kind: NodeKind
}

export type EdgeKind = 'main' | 'exception'

export interface FlowEdge {
  id: string
  source: string
  target: string
  /** 连线上的文字标签，自由文本 */
  label?: string
  /** main = 棕色实线；exception = 红色虚线 */
  kind: EdgeKind
}

export interface FlowDoc {
  schemaVersion: 1
  id: string
  name: string
  /** ISO 时间 */
  updatedAt: string
  grid: GridConfig
  lanes: Lane[]
  stepCount: number
  nodes: FlowNode[]
  edges: FlowEdge[]
}