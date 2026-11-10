/**
 * 自定义连线：按 kind 出实线（主流程）或虚线（异常流程），不画箭头。
 *
 * 路由抽成纯函数 routeBetween 便于单测：它不碰 LogicFlow，只做坐标运算。
 *
 * 覆写 updatePoints() 是**交互性的基础**，与 src/flow/MindMapEdge.ts 的经验一致：
 * PolylineEdgeModel.moveStartPoint / moveEndPoint 会调用 updatePoints()，
 * 所以拖动节点时连线端点会跟着走。
 */
import { PolylineEdge, PolylineEdgeModel } from '@logicflow/core'

import type { EdgeTheme } from './theme'

export interface RoutePoint {
  x: number
  y: number
}

/** 去掉重复点与共线中间点，避免把退化折线交给圆角渲染 */
function simplify(points: RoutePoint[]): RoutePoint[] {
  const dedup: RoutePoint[] = []
  for (const point of points) {
    const last = dedup[dedup.length - 1]
    if (last && last.x === point.x && last.y === point.y) continue
    dedup.push(point)
  }
  const result = dedup.slice()
  for (let i = result.length - 2; i >= 1; i -= 1) {
    const prev = result[i - 1]
    const curr = result[i]
    const next = result[i + 1]
    const sameX = prev.x === curr.x && curr.x === next.x
    const sameY = prev.y === curr.y && curr.y === next.y
    if (sameX || sameY) result.splice(i, 1)
  }
  return result
}

/**
 * 起点 → 终点走「水平 → 垂直 → 水平」的肘形，拐点取两端 x 的中点，
 * 这样同一列的连线拐弯位置一致，整组读起来是齐的。
 */
export function routeBetween(start: RoutePoint, end: RoutePoint): RoutePoint[] {
  const middleX = start.x + (end.x - start.x) / 2
  return simplify([start, { x: middleX, y: start.y }, { x: middleX, y: end.y }, end])
}

interface FlowEdgeProperties {
  kind: 'main' | 'exception'
  theme: EdgeTheme
}

export class FlowEdgeModel extends PolylineEdgeModel {
  /** 连线不画箭头：getArrowPath 对非 solid/hollow/diamond/circle 渲染空路径 */
  getArrowStyle() {
    return {
      ...super.getArrowStyle(),
      startArrowType: 'none' as const,
      endArrowType: 'none' as const,
    }
  }

  /** 线型（实/虚）与颜色都取自 properties.theme */
  getEdgeStyle() {
    const props = this.properties as unknown as FlowEdgeProperties
    return {
      ...super.getEdgeStyle(),
      stroke: props.theme.stroke,
      strokeWidth: props.theme.strokeWidth,
      strokeDasharray: props.theme.dashArray ?? 'none',
    }
  }

  updatePoints() {
    this.pointsList = routeBetween(this.startPoint, this.endPoint)
    this.points = this.getPath(this.pointsList)
  }
}

export const FLOW_EDGE = {
  type: 'flow-edge',
  view: PolylineEdge,
  model: FlowEdgeModel,
}