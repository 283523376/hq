/**
 * Turns the WMS hierarchy into a layered, left-to-right mind map.
 *
 * Layout rules (these are what make the output read like a printed org chart
 * rather than LogicFlow's default graph):
 *   - every node of the same depth shares one column, sized so the widest node
 *     of a depth sets where the next column starts;
 *   - leaves stack top-to-bottom on a fixed row pitch, and a parent sits centred
 *     on its own children;
 *   - nothing is centred *over* its children on the x axis, so columns stay
 *     straight all the way down.
 *
 * Node ids are derived from the hierarchy path rather than authored by hand,
 * because labels repeat (「采购入库」 appears under both 入库管理 and 成品入库).
 */
import type { WmsNode } from '../data/wms'

export interface MindMapNodeConfig {
  id: string
  type: string
  x: number
  y: number
  text: string
  properties: {
    width: number
    height: number
    radius: number
    style: Record<string, string | number>
    textStyle: Record<string, string | number>
  }
}

export interface MindMapEdgeConfig {
  id: string
  type: string
  sourceNodeId: string
  sourceAnchorId: string
  targetNodeId: string
  targetAnchorId: string
  properties: { radius: number }
}

export interface MindMapGraph {
  nodes: MindMapNodeConfig[]
  edges: MindMapEdgeConfig[]
  /** Bounding size of the whole map, used to frame the initial view. */
  width: number
  height: number
}

const NODE_TYPE = 'rect'
const EDGE_TYPE = 'mindmap-edge'

/** Rect anchors come from RectNodeModel: _0 top, _1 right, _2 bottom, _3 left. */
const ANCHOR_RIGHT = '_1'
const ANCHOR_LEFT = '_3'

const MARGIN_X = 48
const MARGIN_Y = 48
/** Vertical pitch per leaf row. Taller than the tallest node so rows never touch. */
const ROW_HEIGHT = 38
/** Gap between a column's right edge and the next column's left edge. Must stay
 *  larger than TRUNK_GAP in ../flow/MindMapEdge.ts, or the bracket trunk would
 *  be drawn inside the parent box. */
const COLUMN_GAP = 56
const NODE_RADIUS = 6
const EDGE_RADIUS = 6

interface DepthStyle {
  fontSize: number
  padX: number
  padY: number
  fill: string
  stroke: string
  color: string
  fontWeight?: number
}

const ROOT_STYLE: DepthStyle = {
  fontSize: 20,
  padX: 16,
  padY: 12,
  fill: '#000000',
  stroke: 'none',
  color: '#ffffff',
  fontWeight: 700,
}
const BRANCH_STYLE: DepthStyle = {
  fontSize: 15,
  padX: 12,
  padY: 8,
  fill: '#ffffff',
  stroke: '#d4d4d4',
  color: '#333333',
  fontWeight: 500,
}
const MID_STYLE: DepthStyle = {
  fontSize: 13,
  padX: 10,
  padY: 7,
  fill: '#ffffff',
  stroke: '#d4d4d4',
  color: '#444444',
}
const LEAF_STYLE: DepthStyle = {
  fontSize: 11,
  padX: 8,
  padY: 6,
  fill: '#ffffff',
  stroke: '#d4d4d4',
  color: '#555555',
}

function styleAt(depth: number): DepthStyle {
  if (depth <= 0) return ROOT_STYLE
  if (depth === 1) return BRANCH_STYLE
  if (depth === 2) return MID_STYLE
  return LEAF_STYLE
}

interface Item {
  id: string
  text: string
  depth: number
  width: number
  height: number
  x: number
  y: number
  children: Item[]
}

/**
 * Estimated advance width of `text` at `fontSize`. Full-width glyphs (CJK and
 * 全角标点) take about one em, Latin about 0.55em. Measuring the DOM instead
 * would be exact but would force a layout pass before every render.
 */
function measureText(text: string, fontSize: number): number {
  let width = 0
  for (const char of text) {
    // Latin-1 glyphs advance ~0.55em; CJK and full-width punctuation take a full em.
    width += char.codePointAt(0)! <= 0xff ? fontSize * 0.55 : fontSize
  }
  return width
}

function buildItem(node: WmsNode, depth: number, path: string): Item {
  const style = styleAt(depth)
  const item: Item = {
    id: `wms-${path}`,
    text: node.text,
    depth,
    width: Math.round(measureText(node.text, style.fontSize)) + style.padX * 2,
    height: style.fontSize + style.padY * 2,
    x: 0,
    y: 0,
    children: [],
  }
  const children = node.children ?? []
  children.forEach((child, index) => {
    item.children.push(buildItem(child, depth + 1, `${path}-${index}`))
  })
  return item
}

function walk(item: Item, visit: (item: Item) => void): void {
  visit(item)
  for (const child of item.children) walk(child, visit)
}

export function buildMindMap(data: WmsNode): MindMapGraph {
  const root = buildItem(data, 0, '0')

  const items: Item[] = []
  walk(root, (item) => items.push(item))

  // Column left edges: each depth clears the widest node of the previous depth.
  const maxWidth: number[] = []
  for (const item of items) {
    maxWidth[item.depth] = Math.max(maxWidth[item.depth] ?? 0, item.width)
  }
  const colX: number[] = [MARGIN_X]
  for (let depth = 0; depth < maxWidth.length - 1; depth += 1) {
    colX.push(colX[depth] + maxWidth[depth] + COLUMN_GAP)
  }

  // Rows: leaves take successive slots, parents centre on their first and last child.
  // Every child must be walked (not just the first and last) or the subtrees in
  // between never get a row.
  let row = 0
  const assignY = (item: Item): number => {
    if (item.children.length === 0) {
      item.y = MARGIN_Y + row * ROW_HEIGHT + ROW_HEIGHT / 2
      row += 1
    } else {
      const childYs = item.children.map(assignY)
      item.y = (childYs[0] + childYs[childYs.length - 1]) / 2
    }
    return item.y
  }
  assignY(root)

  const nodes: MindMapNodeConfig[] = items.map((item) => {
    const style = styleAt(item.depth)
    return {
      id: item.id,
      type: NODE_TYPE,
      x: colX[item.depth] + item.width / 2,
      y: item.y,
      text: item.text,
      properties: {
        width: item.width,
        height: item.height,
        radius: NODE_RADIUS,
        style: {
          fill: style.fill,
          stroke: style.stroke,
          strokeWidth: 1,
        },
        textStyle: {
          fontSize: style.fontSize,
          color: style.color,
          fontWeight: style.fontWeight ?? 400,
        },
      },
    }
  })

  const edges: MindMapEdgeConfig[] = []
  walk(root, (item) => {
    for (const child of item.children) {
      edges.push({
        id: `wms-edge-${child.id}`,
        type: EDGE_TYPE,
        sourceNodeId: item.id,
        sourceAnchorId: `${item.id}${ANCHOR_RIGHT}`,
        targetNodeId: child.id,
        targetAnchorId: `${child.id}${ANCHOR_LEFT}`,
        properties: { radius: EDGE_RADIUS },
      })
    }
  })

  const lastDepth = maxWidth.length - 1
  return {
    nodes,
    edges,
    width: colX[lastDepth] + maxWidth[lastDepth] + MARGIN_X,
    height: MARGIN_Y * 2 + row * ROW_HEIGHT,
  }
}