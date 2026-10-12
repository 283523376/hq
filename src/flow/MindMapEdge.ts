/**
 * Bracket-style connectors for the WMS mind map.
 *
 * Each parent→child link leaves the parent's right edge, runs out to a share of
 * the trunk line, then runs vertically to the child's row before entering the
 * child's left edge. Because the trunk x is measured back from the *child*
 * column, every sibling edge draws its vertical segment at the same x, and the
 * group reads as one bracket — the look of the printed org chart.
 *
 * The corner rounding itself is not drawn here: LogicFlow's PolylineEdge view
 * rounds polyline corners from `properties.radius`, which the layout sets.
 */
import { PolylineEdgeModel } from '@logicflow/core'

/**
 * Horizontal distance from a child's left edge back to the trunk line. Must stay
 * smaller than COLUMN_GAP in ../layout/mindmap.ts, otherwise the trunk would be
 * drawn on top of the parent box.
 */
const TRUNK_GAP = 22

interface Point {
  x: number
  y: number
}

/**
 * Drops duplicate and collinear points. A single-child parent sits on the exact
 * row of its child, and a degenerate 4-point path like that would hand the
 * rounded-corner renderer a zero-length segment.
 */
function simplify(points: Point[]): Point[] {
  const result: Point[] = []
  for (const point of points) {
    const last = result[result.length - 1]
    if (last && last.x === point.x && last.y === point.y) continue
    result.push(point)
  }
  for (let i = 1; i < result.length - 1; i += 1) {
    const prev = result[i - 1]
    const curr = result[i]
    const next = result[i + 1]
    if ((prev.x === curr.x && curr.x === next.x) || (prev.y === curr.y && curr.y === next.y)) {
      result.splice(i, 1)
      i -= 1
    }
  }
  return result
}

export class MindMapEdgeModel extends PolylineEdgeModel {
  /**
   * Mind maps have no arrowheads. `getArrowPath` renders an empty path for any
   * type other than solid/hollow/diamond/circle, so 'none' is a true no-op.
   */
  getArrowStyle() {
    return {
      ...super.getArrowStyle(),
      startArrowType: 'none' as const,
      endArrowType: 'none' as const,
    }
  }

  /**
   * Overrides the orthogonal routing so the path is the bracket, not a
   * shortest-path detour. Re-runs whenever either endpoint node moves, which is
   * why dragging a node keeps its connectors attached.
   */
  updatePoints() {
    const start = this.startPoint
    const end = this.endPoint
    const trunkX = end.x - TRUNK_GAP

    this.pointsList = simplify([
      { x: start.x, y: start.y },
      { x: trunkX, y: start.y },
      { x: trunkX, y: end.y },
      { x: end.x, y: end.y },
    ])
    this.points = this.getPath(this.pointsList)
  }
}