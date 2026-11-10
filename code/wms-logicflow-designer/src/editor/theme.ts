/**
 * 颜色是唯一需要「看着调」的东西，集中在这里，改配色只动这一个文件。
 * 色值参考原图：表头是较饱和的浅色块，节点是更淡的底 + 同色系描边与文字。
 */
import type { EdgeKind, LaneColor } from './types'

export interface LaneTheme {
  headerFill: string
  headerText: string
  nodeFill: string
  nodeStroke: string
  nodeText: string
}

export const LANE_THEME: Record<LaneColor, LaneTheme> = {
  blue: {
    headerFill: '#cfe2fb',
    headerText: '#1d4ed8',
    nodeFill: '#f2f7ff',
    nodeStroke: '#5b9bd5',
    nodeText: '#1f3864',
  },
  orange: {
    headerFill: '#fbe0b8',
    headerText: '#b45309',
    nodeFill: '#fff8ec',
    nodeStroke: '#e0a951',
    nodeText: '#7a4a06',
  },
  green: {
    headerFill: '#cdeccf',
    headerText: '#15803d',
    nodeFill: '#f3fbf4',
    nodeStroke: '#6bbf7a',
    nodeText: '#14532d',
  },
  purple: {
    headerFill: '#ded4f5',
    headerText: '#6d28d9',
    nodeFill: '#f8f5ff',
    nodeStroke: '#9a8ad6',
    nodeText: '#4c1d95',
  },
  red: {
    headerFill: '#f8d2d2',
    headerText: '#b91c1c',
    nodeFill: '#fdf1f1',
    nodeStroke: '#d98a8a',
    nodeText: '#7f1d1d',
  },
}

export interface EdgeTheme {
  stroke: string
  strokeWidth: number
  /** 有值即为虚线 */
  dashArray?: string
}

export const EDGE_THEME: Record<EdgeKind, EdgeTheme> = {
  main: { stroke: '#8a6a3a', strokeWidth: 2 },
  exception: { stroke: '#e05252', strokeWidth: 2, dashArray: '6 4' },
}