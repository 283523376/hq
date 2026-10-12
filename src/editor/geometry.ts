/**
 * 网格几何：把文档里的「格子」推导成像素。
 *
 * 参考图之所以整齐，全靠三条规则：
 *   1. 节点宽 = 泳道宽 − 两侧内边距，横向居中于泳道 —— 同一列节点宽度天然相等；
 *   2. 节点纵向中心 = 行带中线 —— 同一行节点对齐；
 *   3. 节点高度 = 文本按上面那个宽度折行后的高度 —— 行数多的节点自然更高。
 * 位置不落盘，这三条算出来的就是唯一答案，增删泳道/步骤因此自动重排。
 *
 * 坐标约定：Bounds 用左上角，NodeGeom 用中心点（直接喂给 LogicFlow 节点）。
 */
import { wrappedTextHeight } from './text'
import type { FlowDoc, FlowNode, Lane, LaneColor } from './types'

/** 节点正文字号 */
export const FONT_SIZE = 12
/** 节点正文行高 */
export const LINE_HEIGHT = 18
/** 正文相对节点左右的内边距 */
export const TEXT_PAD_X = 10
/** 正文相对节点上下的内边距 */
export const TEXT_PAD_Y = 10

/** 左上角约定的矩形，用于背景（泳道/行带） */
export interface Bounds {
  left: number
  top: number
  width: number
  height: number
}

/** 中心点约定的节点几何，直接对应 LogicFlow 节点的 x/y/width/height */
export interface NodeGeom {
  x: number
  y: number
  width: number
  height: number
}

function findLane(doc: FlowDoc, laneId: string): Lane {
  const lane = doc.lanes.find((item) => item.id === laneId)
  if (!lane) throw new Error(`未知泳道 id: ${laneId}`)
  return lane
}

/** 该泳道左边缘的 x（第一条紧贴步骤号列右侧） */
export function laneLeft(doc: FlowDoc, laneId: string): number {
  let left = doc.grid.stepColumnWidth
  for (const lane of doc.lanes) {
    if (lane.id === laneId) return left
    left += lane.width
  }
  throw new Error(`未知泳道 id: ${laneId}`)
}

/** 泳道「体」的范围（不含表头） */
export function laneBounds(doc: FlowDoc, laneId: string): Bounds {
  const lane = findLane(doc, laneId)
  return {
    left: laneLeft(doc, laneId),
    top: doc.grid.headerHeight,
    width: lane.width,
    height: doc.stepCount * doc.grid.rowHeight,
  }
}

/** 泳道表头的范围（页面最顶一条） */
export function laneHeaderBounds(doc: FlowDoc, laneId: string): Bounds {
  const lane = findLane(doc, laneId)
  return {
    left: laneLeft(doc, laneId),
    top: 0,
    width: lane.width,
    height: doc.grid.headerHeight,
  }
}

/** 某一「步骤」行带的范围，横跨整张图（含步骤号列） */
export function stepBandBounds(doc: FlowDoc, step: number): Bounds {
  const { width } = docSize(doc)
  return {
    left: 0,
    top: doc.grid.headerHeight + (step - 1) * doc.grid.rowHeight,
    width,
    height: doc.grid.rowHeight,
  }
}

/** 节点的像素几何。宽度由列宽（或节点自报的 width）决定，高度由文字折行决定 */
export function nodeGeom(doc: FlowDoc, node: FlowNode): NodeGeom {
  const lane = findLane(doc, node.laneId)
  const width = node.width ?? lane.width - doc.grid.lanePadX * 2
  const height =
    wrappedTextHeight(node.text, width - TEXT_PAD_X * 2, FONT_SIZE, LINE_HEIGHT) + TEXT_PAD_Y * 2
  return {
    x: laneLeft(doc, lane.id) + lane.width / 2 + (node.offsetX ?? 0),
    y: doc.grid.headerHeight + (node.step - 1) * doc.grid.rowHeight + doc.grid.rowHeight / 2,
    width,
    height,
  }
}

/** 节点配色取自所属泳道——参考图 21 个节点无一例外，所以这是规则而非巧合 */
export function laneColorOf(doc: FlowDoc, laneId: string): LaneColor {
  return findLane(doc, laneId).color
}

/** 整张图的包围尺寸，用于取景（frameGraph） */
export function docSize(doc: FlowDoc): { width: number; height: number } {
  const lanesWidth = doc.lanes.reduce((sum, lane) => sum + lane.width, 0)
  return {
    width: doc.grid.stepColumnWidth + lanesWidth,
    height: doc.grid.headerHeight + doc.stepCount * doc.grid.rowHeight,
  }
}