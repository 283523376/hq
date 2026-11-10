# 泳道流程图编辑器实施计划（最小集）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让应用能在图形界面里拖拽创建、修改参考图那张「WMS 收货入库流程」泳道图，并能存下来、导出 JSON/PNG。

**Architecture:** 唯一事实源是一份**不存坐标**的 `FlowDoc`（节点只记 `{laneId, step}`）；`text.ts` 按宽度折行决定节点高度，`geometry.ts` 把格子推导成像素，`render.ts`（纯函数）把文档转成 LogicFlow 配置。背景（泳道表头 / 斑马行带 / 步骤号列）也是节点，用 `draggable` / `isHittable` 关掉交互、用负 `zIndex` 压底。编辑操作全部是 `ops.ts` 里的纯函数（文档进、文档出），所以能单测，UI 只负责把事件翻译成这些调用。

**Tech Stack:** Vue 3 + TypeScript + Vite、LogicFlow 2.2.5（`@logicflow/core`）、Vitest 5（新增，仅 devDependency）

**Spec:** `docs/superpowers/specs/2026-10-12-flow-editor-design.md`

## Global Constraints

- 包管理器 **pnpm**。`pnpm build`（`vue-tsc -b && vite build`）必须通过，它是类型门禁。
- `tsconfig.app.json` 开了 `noUnusedLocals` / `noUnusedParameters` / `erasableSyntaxOnly`：**未使用的变量会让构建失败**；**不许用 enum 与构造函数参数属性**（用 `const` 对象 + 联合类型）。
- LogicFlow 视图层是 **preact**：自定义节点/连线视图用 `@logicflow/core` 导出的 `h()` **显式调用**，**不写 JSX**。
- 从 `@logicflow/extension` 取东西一律走**深层路径**（`@logicflow/extension/es/tools/snapshot`），**不要用包桶**——包桶会把 bundle 从 ~462 kB 翻到 ~960 kB。本计划只用 snapshot 一个扩展。
- **以下文件一行都不许改**：`src/layout/mindmap.ts`、`src/flow/MindMapEdge.ts`、`src/data/wms.ts`、`src/components/WmsStructure.vue`。被修改的既有文件只有 `src/App.vue`、`package.json`、`CLAUDE.md`。
- 纯前端，不引入后端/网络依赖。UI 文案用中文。
- LogicFlow 节点 `x`/`y` 是**中心点**；背景矩形的 `left`/`top` 是**左上角**。代码里用不同类型名区分（`NodeGeom` vs `Bounds`），不要混用。
- **已按最小化砍掉，不要顺手加回来**：右栏属性面板、连线手动调走线（`waypoints`）、多文档管理、复制粘贴剪贴板、泳道调宽/排序/配色 UI。详见 spec §1 末。
- **本仓库不是 git 仓库**。Task 1 的 Step 1 会 `git init`（强烈建议：全部改动约 1300 行，没有版本控制就无法 diff、无法回滚）。若你选择不用 git，**跳过所有 Commit 步骤**，其余照常。

## Review Focus

这几类是 spec 隐含、单个任务的测试却照不到、最容易在真机上咬人的情形。每一类都在拥有该代码的任务里放了对应测试：

1. **换行的边界输入**：超宽无空格 Latin 串、单个宽于可用宽度的字、空文本、连续换行符。期望不丢字、高度不塌成 0、坐标不出现 NaN。
2. **`laneId` 指向不存在的泳道**：期望几何函数**显式抛错**，而不是静默把节点放到最右边——静默错位比崩溃更难查。
3. **泳道数量与宽度极端值**：0 条泳道、1 条泳道、宽度小到 `width - 2*lanePadX <= 0`。期望 `validateDoc` 报错、几何不产出负宽。
4. **越界与占用**：`step > stepCount`、两个节点落在同一 `(laneId, step)`、把 `stepCount` 调小到低于某节点所在步。期望校验报错；调小步骤数时越界节点**上移而不是被删除**，格子确实不够时**拒绝该操作**，而不是把节点堆成重复格。
5. **连线两端问题**：连线指向不存在的节点、`source === target` 自环、`kind` 非法。期望 `validateDoc` 报错，而不是渲染时崩溃。

---

## 阶段 A：渲染基线

### Task 1: 版本控制 + Vitest + `types.ts` + `theme.ts`

把脚手架和它服务的第一个真实模块放在一起：交付物是「`pnpm test` 能跑通，且配色表被断言覆盖」。

**Files:**
- Create: `src/editor/types.ts`
- Create: `src/editor/theme.ts`
- Create: `src/editor/theme.spec.ts`
- Modify: `package.json`

**Interfaces:**
- Consumes: 无
- Produces: `LaneColor`、`EdgeKind`、`NodeKind`、`GridConfig`、`Lane`、`FlowNode`、`FlowEdge`、`FlowDoc`（后续所有任务）；`LaneTheme`、`EdgeTheme`、`LANE_THEME`、`EDGE_THEME`

- [ ] **Step 1: 初始化 git（若决定不用 git 则跳过本步与所有 Commit 步）**

```bash
cd /d/yongxin_WMS/wms-logicflow-designer
git init
git add -A && git commit -m "chore: 记录流程图编辑器开发前的基线"
```

Expected: 提交成功，`git log --oneline` 有一条记录。

- [ ] **Step 2: 安装 Vitest**

```bash
pnpm add -D vitest
```

Expected: devDependencies 出现 `vitest`（5.x）。已核实 vitest 5.0.3 的 peer 为 `vite ^6.4.0 || ^7.0.0 || ^8.0.0`，与本仓库 vite 8 兼容；本机 node v24 满足要求。

- [ ] **Step 3: 加 test 脚本**

修改 `package.json` 的 `scripts`（保留原有三条不动）：

```json
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc -b && vite build",
    "preview": "vite preview",
    "test": "vitest run",
    "test:watch": "vitest"
  },
```

- [ ] **Step 4: 写类型文件**

创建 `src/editor/types.ts`：

```ts
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
```

- [ ] **Step 5: 写配色表的失败测试**

创建 `src/editor/theme.spec.ts`：

```ts
import { describe, expect, it } from 'vitest'

import { EDGE_THEME, LANE_THEME } from './theme'

describe('LANE_THEME', () => {
  it('五种泳道色都有完整主题', () => {
    const colors = ['blue', 'orange', 'green', 'purple', 'red'] as const
    for (const color of colors) {
      const theme = LANE_THEME[color]
      expect(theme.headerFill).toMatch(/^#[0-9a-f]{6}$/)
      expect(theme.nodeFill).toMatch(/^#[0-9a-f]{6}$/)
      expect(theme.nodeStroke).toMatch(/^#[0-9a-f]{6}$/)
      expect(theme.nodeText).toMatch(/^#[0-9a-f]{6}$/)
    }
  })
})

describe('EDGE_THEME', () => {
  it('主流程是实线、异常流程是虚线', () => {
    expect(EDGE_THEME.main.dashArray).toBeUndefined()
    expect(EDGE_THEME.exception.dashArray).toBeDefined()
  })

  it('两类线的颜色不同', () => {
    expect(EDGE_THEME.main.stroke).not.toBe(EDGE_THEME.exception.stroke)
  })
})
```

- [ ] **Step 6: 运行测试确认失败**

Run: `pnpm test`
Expected: FAIL —— 找不到模块 `./theme`。

- [ ] **Step 7: 实现配色表**

创建 `src/editor/theme.ts`：

```ts
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
```

- [ ] **Step 8: 运行确认通过**

Run: `pnpm test`
Expected: PASS，3 个用例全绿。

- [ ] **Step 9: 类型门禁**

Run: `pnpm build`
Expected: 通过。`types.ts` 里的类型此刻大多还没被使用——这是允许的，`noUnusedLocals` 管的是变量不是导出类型。

- [ ] **Step 10: Commit**

```bash
git add package.json pnpm-lock.yaml src/editor/types.ts src/editor/theme.ts src/editor/theme.spec.ts
git commit -m "feat(editor): 加 Vitest 与流程图文档模型、配色表"
```

---

### Task 2: `text.ts` —— 文本估宽与按宽度换行

节点高度由文字折行决定，所以这个模块同时决定「渲染几行」和「节点多高」。它是**单一事实源**，几何与渲染共用，因此不会出现「量高度一套、画图另一套」的错位。

**Files:**
- Create: `src/editor/text.ts`
- Create: `src/editor/text.spec.ts`

**Interfaces:**
- Consumes: 无（纯字符串计算）
- Produces: `measureText(text, fontSize): number`、`wrapText(text, maxWidth, fontSize): string[]`、`wrappedTextHeight(text, maxWidth, fontSize, lineHeight): number`

- [ ] **Step 1: 写失败测试**

创建 `src/editor/text.spec.ts`：

```ts
import { describe, expect, it } from 'vitest'

import { measureText, wrapText, wrappedTextHeight } from './text'

describe('measureText', () => {
  it('CJK 按 1em、Latin 按 0.55em 估宽', () => {
    expect(measureText('AB', 10)).toBeCloseTo(11)
    expect(measureText('材料', 10)).toBeCloseTo(20)
    expect(measureText('A材', 10)).toBeCloseTo(15.5)
  })

  it('空文本宽度为 0', () => {
    expect(measureText('', 12)).toBe(0)
  })
})

describe('wrapText', () => {
  it('超出可用宽度时逐字换行', () => {
    expect(wrapText('ABCDEFGH', 22, 10)).toEqual(['ABCD', 'EFGH'])
  })

  it('显式换行符按硬换行优先', () => {
    expect(wrapText('甲\n乙', 100, 10)).toEqual(['甲', '乙'])
  })

  it('空文本仍占一行，不会塌成 0 行', () => {
    expect(wrapText('', 100, 10)).toEqual([''])
  })

  it('单个宽于可用宽度的字独占一行而不被丢弃', () => {
    expect(wrapText('材', 1, 10)).toEqual(['材'])
  })

  it('连续换行符产生空行而不吞掉', () => {
    expect(wrapText('甲\n\n乙', 100, 10)).toEqual(['甲', '', '乙'])
  })
})

describe('wrappedTextHeight', () => {
  it('高度 = 行数 × 行高', () => {
    expect(wrappedTextHeight('甲\n乙', 100, 10, 18)).toBe(36)
  })

  it('空文本高度为一行高（不塌成 0）', () => {
    expect(wrappedTextHeight('', 100, 10, 18)).toBe(18)
  })
})
```

- [ ] **Step 2: 运行确认失败**

Run: `pnpm test src/editor/text.spec.ts`
Expected: FAIL —— 找不到 `./text`。

- [ ] **Step 3: 实现**

创建 `src/editor/text.ts`：

```ts
/**
 * 文本估宽与折行。
 *
 * 估宽规则沿用 layout/mindmap.ts 里 measureText 的思路：CJK 与全角标点约 1em、
 * Latin 约 0.55em（用 codePoint <= 0xff 判别）。**故意不抽成共享模块**：导图版
 * 只做单行估宽、编辑器版要折行，职责不同，且导图部分要求一行不动。
 *
 * 本模块是折行的单一事实源：节点高度由它算、文字也由它分行来画，
 * 所以不会出现「量高用一套、渲染用另一套」的错位。
 */

const LATIN_RATIO = 0.55

/** 估算 text 在 fontSize 下的推进宽度 */
export function measureText(text: string, fontSize: number): number {
  let width = 0
  for (const char of text) {
    // Latin-1 及以下按 0.55em，其余（CJK / 全角标点）按 1em
    width += (char.codePointAt(0) ?? 0) <= 0xff ? fontSize * LATIN_RATIO : fontSize
  }
  return width
}

/**
 * 把 text 按 maxWidth 折行，含换行符时硬换行优先。
 * 空文本返回 ['']（占一行），单个超宽字独占一行而非被丢弃。
 */
export function wrapText(text: string, maxWidth: number, fontSize: number): string[] {
  const lines: string[] = []
  for (const hardLine of text.split('\n')) {
    let current = ''
    for (const char of hardLine) {
      const next = current + char
      // current 为空时不换行，保证单个超宽字也能落到行上
      if (current !== '' && measureText(next, fontSize) > maxWidth) {
        lines.push(current)
        current = char
      } else {
        current = next
      }
    }
    lines.push(current)
  }
  return lines
}

/** 折行后的总高度 */
export function wrappedTextHeight(
  text: string,
  maxWidth: number,
  fontSize: number,
  lineHeight: number,
): number {
  return wrapText(text, maxWidth, fontSize).length * lineHeight
}
```

- [ ] **Step 4: 运行确认通过**

Run: `pnpm test src/editor/text.spec.ts`
Expected: PASS，9 个用例全绿。

- [ ] **Step 5: Commit**

```bash
git add src/editor/text.ts src/editor/text.spec.ts
git commit -m "feat(editor): 加文本估宽与按宽度折行的单一事实源"
```

---

### Task 3: `geometry.ts` —— 网格几何推导

整个编辑器的核心机制：格子 → 像素。三条规则（节点宽 = 列宽、纵向居中于行带、高度随文字）都在这里。

**Files:**
- Create: `src/editor/geometry.ts`
- Create: `src/editor/geometry.spec.ts`

**Interfaces:**
- Consumes: `FlowDoc` / `FlowNode` / `LaneColor`（Task 1）、`wrappedTextHeight`（Task 2）
- Produces:
  - `FONT_SIZE`、`LINE_HEIGHT`、`TEXT_PAD_X`、`TEXT_PAD_Y`
  - `Bounds = { left; top; width; height }`（左上角约定，用于背景矩形）
  - `NodeGeom = { x; y; width; height }`（**中心点**约定，直接对应 LogicFlow 节点）
  - `laneLeft(doc, laneId)`、`laneBounds(doc, laneId)`、`laneHeaderBounds(doc, laneId)`
  - `stepBandBounds(doc, step)`、`nodeGeom(doc, node)`、`laneColorOf(doc, laneId)`、`docSize(doc)`

- [ ] **Step 1: 写失败测试**

创建 `src/editor/geometry.spec.ts`：

```ts
import { describe, expect, it } from 'vitest'

import {
  docSize,
  laneBounds,
  laneColorOf,
  laneHeaderBounds,
  laneLeft,
  nodeGeom,
  stepBandBounds,
} from './geometry'
import type { FlowDoc } from './types'

function makeDoc(nodes: FlowDoc['nodes'] = []): FlowDoc {
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
    nodes,
    edges: [],
  }
}

const oneNode: FlowDoc['nodes'] = [
  { id: 'A01', text: '甲', laneId: 'l1', step: 1, kind: 'process' },
]

describe('laneLeft', () => {
  it('第一条泳道从步骤号列右边开始', () => {
    expect(laneLeft(makeDoc(), 'l1')).toBe(60)
  })

  it('后续泳道按前一条的宽度累进', () => {
    expect(laneLeft(makeDoc(), 'l2')).toBe(260)
  })

  it('未知泳道 id 显式抛错，而不是静默放到最右边', () => {
    expect(() => laneLeft(makeDoc(), 'nope')).toThrow(/未知泳道/)
  })
})

describe('增删泳道后自动重排', () => {
  it('在前一条泳道左边插一条更宽的泳道，会把后面的列整体右移', () => {
    const doc = makeDoc()
    const shifted: FlowDoc = {
      ...doc,
      lanes: [{ id: 'l0', name: '新', color: 'green', width: 300 }, ...doc.lanes],
    }
    expect(laneLeft(shifted, 'l1')).toBe(360)
    expect(laneLeft(shifted, 'l2')).toBe(560)
    // 纯函数不改入参
    expect(laneLeft(doc, 'l1')).toBe(60)
  })
})

describe('laneBounds / laneHeaderBounds', () => {
  it('泳道体在表头之下，高度 = 行数 × 行高', () => {
    expect(laneBounds(makeDoc(), 'l2')).toEqual({
      left: 260,
      top: 40,
      width: 150,
      height: 300,
    })
  })

  it('表头在顶部，高度 = headerHeight', () => {
    expect(laneHeaderBounds(makeDoc(), 'l1')).toEqual({
      left: 60,
      top: 0,
      width: 200,
      height: 40,
    })
  })
})

describe('stepBandBounds', () => {
  it('第 2 行带的 top = 表头高 + 1 个行高', () => {
    expect(stepBandBounds(makeDoc(), 2)).toEqual({
      left: 0,
      top: 140,
      width: 410,
      height: 100,
    })
  })
})

describe('nodeGeom', () => {
  it('宽度 = 列宽 − 两侧内边距，x 落在列中心，y 落在行带中线', () => {
    const geom = nodeGeom(makeDoc(oneNode), oneNode[0])
    expect(geom.width).toBe(180)
    expect(geom.x).toBe(160)
    expect(geom.y).toBe(90)
  })

  it('高度由文字行数决定：单行 = 行高 + 上下内边距', () => {
    const geom = nodeGeom(makeDoc(oneNode), oneNode[0])
    expect(geom.height).toBe(18 + 20)
  })

  it('文字变多则节点变高（不是手调的）', () => {
    const tall: FlowDoc['nodes'] = [
      { id: 'A02', text: '甲\n甲\n甲', laneId: 'l1', step: 2, kind: 'process' },
    ]
    const geom = nodeGeom(makeDoc(tall), tall[0])
    expect(geom.height).toBe(18 * 3 + 20)
  })

  it('节点落在所属泳道中心，换泳道即换 x 与配色', () => {
    const node: FlowDoc['nodes'][number] = {
      id: 'A03',
      text: '甲',
      laneId: 'l2',
      step: 1,
      kind: 'process',
    }
    const geom = nodeGeom(makeDoc([node]), node)
    expect(geom.x).toBe(335)
    expect(laneColorOf(makeDoc([node]), 'l2')).toBe('red')
  })

  it('未知泳道 id 抛错', () => {
    const bad: FlowDoc['nodes'] = [
      { id: 'A04', text: '甲', laneId: 'nope', step: 1, kind: 'process' },
    ]
    expect(() => nodeGeom(makeDoc(bad), bad[0])).toThrow(/未知泳道/)
  })
})

describe('docSize', () => {
  it('宽度 = 步骤号列 + 所有列宽；高度 = 表头 + 行数 × 行高', () => {
    expect(docSize(makeDoc())).toEqual({ width: 410, height: 340 })
  })
})
```

- [ ] **Step 2: 运行确认失败**

Run: `pnpm test src/editor/geometry.spec.ts`
Expected: FAIL —— 找不到 `./geometry`。

- [ ] **Step 3: 实现**

创建 `src/editor/geometry.ts`：

```ts
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
import type { FlowDoc, FlowNode, LaneColor } from './types'

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

function findLane(doc: FlowDoc, laneId: string) {
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

/** 节点的像素几何。宽度由列宽决定，高度由文字折行决定 */
export function nodeGeom(doc: FlowDoc, node: FlowNode): NodeGeom {
  const lane = findLane(doc, node.laneId)
  const width = lane.width - doc.grid.lanePadX * 2
  const height =
    wrappedTextHeight(node.text, width - TEXT_PAD_X * 2, FONT_SIZE, LINE_HEIGHT) + TEXT_PAD_Y * 2
  return {
    x: laneLeft(doc, lane.id) + lane.width / 2,
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
```

- [ ] **Step 4: 运行确认通过**

Run: `pnpm test src/editor/geometry.spec.ts`
Expected: PASS，13 个用例全绿。

- [ ] **Step 5: 全量测试 + 类型门禁**

Run: `pnpm test && pnpm build`
Expected: 全绿 + 通过。

- [ ] **Step 6: Commit**

```bash
git add src/editor/geometry.ts src/editor/geometry.spec.ts
git commit -m "feat(editor): 加网格几何推导与列宽/行带/文字驱动的节点几何"
```

---

### Task 4: `doc.ts` —— 文档校验

`validateDoc` 是导入、本地加载、以及示例图完整性测试的共用门卫。职责是**把坏数据挡在渲染之前并给出可读错误**，而不是让画布白屏。

**Files:**
- Create: `src/editor/doc.ts`
- Create: `src/editor/doc.spec.ts`

**Interfaces:**
- Consumes: `FlowDoc` 等类型（Task 1）
- Produces: `ValidateResult = { ok: true; doc: FlowDoc } | { ok: false; errors: string[] }`、`validateDoc(input: unknown): ValidateResult`

- [ ] **Step 1: 写失败测试**

创建 `src/editor/doc.spec.ts`：

```ts
import { describe, expect, it } from 'vitest'

import { validateDoc } from './doc'
import type { FlowDoc } from './types'

function validDoc(): FlowDoc {
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
      { id: 'A02', text: '二', laneId: 'l2', step: 2, kind: 'process' },
    ],
    edges: [{ id: 'e1', source: 'A01', target: 'A02', kind: 'main', label: '下一' }],
  }
}

function errorsOf(input: unknown): string[] {
  const result = validateDoc(input)
  expect(result.ok).toBe(false)
  return result.ok ? [] : result.errors
}

describe('validateDoc 通过的情形', () => {
  it('合法文档返回 ok 与文档本身', () => {
    const result = validateDoc(validDoc())
    expect(result.ok).toBe(true)
    if (result.ok) expect(result.doc.nodes).toHaveLength(2)
  })

  it('接受纯 JSON 往返后的对象', () => {
    expect(validateDoc(JSON.parse(JSON.stringify(validDoc()))).ok).toBe(true)
  })
})

describe('validateDoc 拒绝的情形', () => {
  it('非对象 / null / undefined', () => {
    expect(errorsOf(null).length).toBeGreaterThan(0)
    expect(errorsOf(undefined).length).toBeGreaterThan(0)
    expect(errorsOf('不是对象').length).toBeGreaterThan(0)
  })

  it('schemaVersion 不是 1', () => {
    expect(errorsOf({ ...validDoc(), schemaVersion: 2 }).join()).toMatch(/schemaVersion/)
  })

  it('泳道为空', () => {
    expect(errorsOf({ ...validDoc(), lanes: [] }).join()).toMatch(/泳道/)
  })

  it('泳道宽度不足以放下节点（扣掉内边距后 <= 0）', () => {
    const doc = validDoc()
    doc.lanes[0].width = 20
    expect(errorsOf(doc).join()).toMatch(/宽度/)
  })

  it('步骤数小于 1', () => {
    expect(errorsOf({ ...validDoc(), stepCount: 0 }).join()).toMatch(/stepCount/)
  })

  it('节点的 laneId 不存在', () => {
    const doc = validDoc()
    doc.nodes[0].laneId = 'nope'
    expect(errorsOf(doc).join()).toMatch(/laneId/)
  })

  it('节点 step 越界（大于 stepCount）', () => {
    const doc = validDoc()
    doc.nodes[0].step = 99
    expect(errorsOf(doc).join()).toMatch(/step/)
  })

  it('两个节点落在同一格', () => {
    const doc = validDoc()
    doc.nodes[1].laneId = 'l1'
    doc.nodes[1].step = 1
    expect(errorsOf(doc).join()).toMatch(/格子/)
  })

  it('节点 id 重复', () => {
    const doc = validDoc()
    doc.nodes[1].id = 'A01'
    expect(errorsOf(doc).join()).toMatch(/id 重复/)
  })

  it('连线指向不存在的节点', () => {
    const doc = validDoc()
    doc.edges[0].target = 'nope'
    expect(errorsOf(doc).join()).toMatch(/target/)
  })

  it('连线自环（两端同一节点）', () => {
    const doc = validDoc()
    doc.edges[0].target = 'A01'
    expect(errorsOf(doc).join()).toMatch(/自环/)
  })

  it('连线 kind 取值非法', () => {
    const doc = validDoc()
    doc.edges[0].kind = 'weird' as FlowDoc['edges'][number]['kind']
    expect(errorsOf(doc).join()).toMatch(/kind/)
  })

  it('一次报出多个错误而不是只报第一个', () => {
    const doc = validDoc()
    doc.schemaVersion = 9 as 1
    doc.stepCount = 0
    expect(errorsOf(doc).length).toBeGreaterThanOrEqual(2)
  })
})
```

- [ ] **Step 2: 运行确认失败**

Run: `pnpm test src/editor/doc.spec.ts`
Expected: FAIL —— 找不到 `./doc`。

- [ ] **Step 3: 实现**

创建 `src/editor/doc.ts`：

```ts
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
```

- [ ] **Step 4: 运行确认通过**

Run: `pnpm test src/editor/doc.spec.ts`
Expected: PASS，15 个用例全绿。

- [ ] **Step 5: 全量测试 + 类型门禁**

Run: `pnpm test && pnpm build`
Expected: 全绿 + 通过。

- [ ] **Step 6: Commit**

```bash
git add src/editor/doc.ts src/editor/doc.spec.ts
git commit -m "feat(editor): 加文档校验，坏数据给出可读错误而非白屏"
```

---

### Task 5: 示例图转录 `seed/harvestInbound.ts`

把参考图变成数据。**这是整件事的验收基线**：转录错了后面全错。两条硬要求——(1) 数据必须过 `validateDoc`；(2) 交付时逐条对图核对（Task 10）。

**Files:**
- Create: `src/editor/seed/harvestInbound.ts`
- Create: `src/editor/seed/harvestInbound.spec.ts`

**Interfaces:**
- Consumes: `FlowDoc`（Task 1）、`validateDoc`（Task 4）
- Produces: `harvestInboundDoc: FlowDoc`

- [ ] **Step 1: 写失败测试**

创建 `src/editor/seed/harvestInbound.spec.ts`：

```ts
import { describe, expect, it } from 'vitest'

import { validateDoc } from '../doc'
import { harvestInboundDoc } from './harvestInbound'

describe('示例图 harvestInboundDoc', () => {
  it('通过文档校验', () => {
    const result = validateDoc(harvestInboundDoc)
    expect(result.ok ? [] : result.errors).toEqual([])
  })

  it('6 条泳道、12 个步骤', () => {
    expect(harvestInboundDoc.lanes).toHaveLength(6)
    expect(harvestInboundDoc.stepCount).toBe(12)
  })

  it('21 个节点，编号从 A01 到 A21 连续', () => {
    const ids = harvestInboundDoc.nodes.map((node) => node.id).sort()
    expect(ids).toHaveLength(21)
    expect(ids[0]).toBe('A01')
    expect(ids[20]).toBe('A21')
  })

  it('连线数量落在 20–26 条（对图读数的合理区间，偏离说明漏读或误读）', () => {
    expect(harvestInboundDoc.edges.length).toBeGreaterThanOrEqual(20)
    expect(harvestInboundDoc.edges.length).toBeLessThanOrEqual(26)
  })

  it('同时存在实线主流程与虚线异常流程', () => {
    const kinds = new Set(harvestInboundDoc.edges.map((edge) => edge.kind))
    expect(kinds).toContain('main')
    expect(kinds).toContain('exception')
  })

  it('异常连线都带标签（原图红虚线全部有文字说明）', () => {
    const exceptions = harvestInboundDoc.edges.filter((edge) => edge.kind === 'exception')
    expect(exceptions.length).toBeGreaterThan(0)
    for (const edge of exceptions) {
      expect(edge.label).toBeTruthy()
    }
  })

  it('没有节点落在重复格（validateDoc 之外再断言一次，便于定位）', () => {
    const cells = harvestInboundDoc.nodes.map((node) => `${node.laneId}#${node.step}`)
    expect(new Set(cells).size).toBe(cells.length)
  })
})
```

- [ ] **Step 2: 运行确认失败**

Run: `pnpm test src/editor/seed/harvestInbound.spec.ts`
Expected: FAIL —— 找不到 `./harvestInbound`。

- [ ] **Step 3: 写数据**

创建 `src/editor/seed/harvestInbound.ts`。节点表来自 spec §9；连线表是逐条读图的结果，标 `⚠待核对` 的几条读数时存在歧义，Task 10 重点核。

```ts
/**
 * 示例图：WMS 收货入库流程（参考图转录）。
 *
 * 这是本项目的验收基线。节点表来自设计文档 §9；连线表是逐条读图的结果，
 * 标注 ⚠待核对 的几条在读数时存在歧义（尤其长距离绕行与回边的起点），
 * 请在阶段 A 验收时对着原图重点核这几条。
 */
import type { FlowDoc } from '../types'

const MAIN = 'main' as const
const EXCEPTION = 'exception' as const

export const harvestInboundDoc: FlowDoc = {
  schemaVersion: 1,
  id: 'seed-harvest-inbound',
  name: 'WMS 收货入库流程',
  updatedAt: '2026-10-12T00:00:00.000Z',
  grid: {
    headerHeight: 44,
    stepColumnWidth: 64,
    rowHeight: 122,
    lanePadX: 14,
    lanePadY: 10,
  },
  stepCount: 12,
  lanes: [
    { id: 'lane-receiver', name: '收货员', color: 'blue', width: 210 },
    { id: 'lane-prep', name: '待切头/柄料', color: 'blue', width: 210 },
    { id: 'lane-inspect', name: '来料检验', color: 'orange', width: 190 },
    { id: 'lane-cabinet', name: '原材料线边柜', color: 'green', width: 230 },
    { id: 'lane-semi', name: '半成品立库', color: 'purple', width: 190 },
    { id: 'lane-exception', name: '异常处理', color: 'red', width: 200 },
  ],
  nodes: [
    { id: 'A01', text: '外购料收货单\n扫码/输入订单号', laneId: 'lane-receiver', step: 1, kind: 'process' },
    { id: 'A02', text: '调出订单信息', laneId: 'lane-cabinet', step: 1, kind: 'process' },
    { id: 'A03', text: '核对订单信息\n物料类型/规格\n重量/数量', laneId: 'lane-receiver', step: 2, kind: 'process' },
    { id: 'A04', text: '选择入库物料\n物料种类=1时\n自动选择', laneId: 'lane-cabinet', step: 2, kind: 'process' },
    { id: 'A05', text: '实物与订单不符\n提示核对', laneId: 'lane-exception', step: 2, kind: 'process' },
    { id: 'A06', text: '来料检验', laneId: 'lane-inspect', step: 3, kind: 'process' },
    { id: 'A07', text: '称重·计数', laneId: 'lane-exception', step: 3, kind: 'process' },
    { id: 'A08', text: '称重·计数', laneId: 'lane-prep', step: 4, kind: 'process' },
    { id: 'A09', text: '外购不合格品\n退回供应商', laneId: 'lane-exception', step: 4, kind: 'process' },
    { id: 'A10', text: '在原材料线边柜\n输入待切头/柄料\n良品/不良品数量', laneId: 'lane-prep', step: 5, kind: 'process' },
    { id: 'A11', text: '检查格子数量', laneId: 'lane-cabinet', step: 5, kind: 'process' },
    { id: 'A12', text: '格子数量不足\n提示是否仅收货', laneId: 'lane-exception', step: 5, kind: 'process' },
    { id: 'A13', text: '改刀料\n称重·计数\n插盘', laneId: 'lane-prep', step: 6, kind: 'process' },
    { id: 'A14', text: '上料并关门', laneId: 'lane-prep', step: 6, kind: 'process' },
    { id: 'A15', text: '自动开启柜门\n等待上料', laneId: 'lane-cabinet', step: 6, kind: 'process' },
    { id: 'A16', text: '生成批次号', laneId: 'lane-cabinet', step: 7, kind: 'process' },
    { id: 'A17', text: '上报良品与不良品\n数量/重量', laneId: 'lane-cabinet', step: 8, kind: 'process' },
    { id: 'A18', text: '更新柜内库存\n收货入库完成', laneId: 'lane-cabinet', step: 9, kind: 'process' },
    { id: 'A19', text: '同步\nERP/MES/WMS', laneId: 'lane-cabinet', step: 10, kind: 'process' },
    { id: 'A20', text: '提示\n是否继续入库', laneId: 'lane-cabinet', step: 11, kind: 'process' },
    { id: 'A21', text: '改刀料入\n半成品立库', laneId: 'lane-semi', step: 12, kind: 'process' },
  ],
  edges: [
    // --- 主流程（棕色实线）---
    { id: 'e-01-02', source: 'A01', target: 'A02', kind: MAIN },
    { id: 'e-01-03', source: 'A01', target: 'A03', kind: MAIN },
    { id: 'e-02-03', source: 'A02', target: 'A03', kind: MAIN },
    { id: 'e-03-04', source: 'A03', target: 'A04', kind: MAIN },
    { id: 'e-04-06', source: 'A04', target: 'A06', kind: MAIN },
    // ⚠待核对：A06 分出「合格待切头/柄料」进 A08、「合格改刀料」进 A13
    { id: 'e-06-08', source: 'A06', target: 'A08', kind: MAIN, label: '合格待切头/柄料' },
    { id: 'e-06-13', source: 'A06', target: 'A13', kind: MAIN, label: '合格改刀料' },
    { id: 'e-08-10', source: 'A08', target: 'A10', kind: MAIN },
    { id: 'e-10-11', source: 'A10', target: 'A11', kind: MAIN },
    { id: 'e-11-15', source: 'A11', target: 'A15', kind: MAIN, label: '数量足够' },
    { id: 'e-15-14', source: 'A15', target: 'A14', kind: MAIN },
    { id: 'e-14-16', source: 'A14', target: 'A16', kind: MAIN },
    { id: 'e-16-17', source: 'A16', target: 'A17', kind: MAIN },
    { id: 'e-17-18', source: 'A17', target: 'A18', kind: MAIN },
    { id: 'e-18-19', source: 'A18', target: 'A19', kind: MAIN },
    { id: 'e-19-20', source: 'A19', target: 'A20', kind: MAIN, label: '仍有待入库物料' },
    { id: 'e-20-19', source: 'A20', target: 'A19', kind: MAIN, label: '继续入库' },
    // ⚠待核对：A13 一路向下接到末步 A21（原图左侧那条长竖线）
    { id: 'e-13-21', source: 'A13', target: 'A21', kind: MAIN },
    // --- 异常流程（红色虚线）---
    // ⚠待核对：这条红虚线的起点我读作 A03（核对订单信息 → 实物与订单不符），也可能是 A04
    { id: 'e-03-05', source: 'A03', target: 'A05', kind: EXCEPTION },
    { id: 'e-06-07', source: 'A06', target: 'A07', kind: EXCEPTION, label: '不合格品' },
    { id: 'e-07-09', source: 'A07', target: 'A09', kind: EXCEPTION },
    { id: 'e-11-12', source: 'A11', target: 'A12', kind: EXCEPTION, label: '数量不足' },
    { id: 'e-12-19', source: 'A12', target: 'A19', kind: EXCEPTION, label: '仅收货，暂不入库' },
  ],
}
```

- [ ] **Step 4: 运行确认通过**

Run: `pnpm test src/editor/seed/harvestInbound.spec.ts`
Expected: PASS，7 个用例全绿。若「连线数量落在 20–26 条」失败，说明转录漏读或多读，回去对图。

- [ ] **Step 5: 全量测试 + 类型门禁**

Run: `pnpm test && pnpm build`
Expected: 全绿 + 通过。

- [ ] **Step 6: Commit**

```bash
git add src/editor/seed/
git commit -m "feat(editor): 转录 WMS 收货入库流程示例图（21 节点 / 23 连线）"
```

---

### Task 6: `nodes.ts` —— 三种自定义节点

三种节点放一个文件：参考图的**流程节点**（圆角矩形 + 编号徽章 + 居中多行正文）、**泳道表头**、**行带**（含左侧步骤号列与斑马纹）。写法照 `@logicflow/extension/es/rect-label-node` 的 `getLabelShape()`。

**Files:**
- Create: `src/editor/nodes.ts`

**Interfaces:**
- Consumes: `FONT_SIZE` / `LINE_HEIGHT`（Task 3）、`wrapText`（Task 2）
- Produces: `FLOW_NODE`、`LANE_HEADER`、`STEP_BAND`（都是 `{ type, view, model }`，直接喂 `lf.register`）

**约定（Task 8 依赖）**：流程节点的 `properties` 需带 `width`、`height`、`radius`、`badge`、`nodeFill`、`nodeStroke`、`nodeText`、`textMaxWidth`；表头需带 `width`、`height`、`headerFill`、`headerText`；行带需带 `width`、`height`、`step`、`zebra`、`stepColumnWidth`、`fontSize`。

**说明**：本任务**没有单测**——LogicFlow 视图是 preact 组件，在 jsdom 里挂载整个画布做断言代价过高且易碎。视图正确性由类型门禁 + Task 9/10 的实机渲染核对覆盖。这是刻意选择，不是遗漏。

- [ ] **Step 1: 实现三种节点**

创建 `src/editor/nodes.ts`：

```ts
/**
 * 自定义节点：流程节点、泳道表头、行带。
 *
 * 为什么不用内置 rect：
 *  - 流程节点要「左上角小号编号徽章 + 居中多行正文」两种样式并存，内置 rect 的
 *    text 是单段统一样式；
 *  - 表头 / 行带必须随画布缩放平移同步，而 LogicFlow 只有等距的 Grid overlay 与
 *    不同步变换的 CSS BackgroundOverlay，都表达不了「列宽不等的泳道」。
 *
 * 两条硬约束：
 *  1. 视图层是 preact，因此用 @logicflow/core 导出的 h() 显式调用，不写 JSX。
 *  2. 正文由**我们自己**按 textMaxWidth 折行后逐行绘制，并让 getText() 返回 null
 *     抑制内置文本 —— 这样「几何算高度」与「渲染画几行」共用 wrapText，
 *     不会出现量高与画图各一套导致的错位。
 *
 * 交互分工（阶段 A 只有第一个生效，阶段 B 依赖第二、三条）：
 *  - 表头：不可拖，但**保留点击**（阶段 B 双击它改泳道名）；
 *  - 行带：不可拖且 isHittable = false，彻底穿透，不抢画布的点击与框选。
 */
import { RectNode, RectNodeModel, h } from '@logicflow/core'

import { FONT_SIZE, LINE_HEIGHT } from './geometry'
import { wrapText } from './text'

// ---------------------------------------------------------------- 流程节点

interface FlowNodeProperties {
  width: number
  height: number
  radius: number
  badge: string
  nodeFill: string
  nodeStroke: string
  nodeText: string
  textMaxWidth: number
}

export class FlowNodeView extends RectNode {
  /** 顶部编号徽章：贴着矩形左上角内缩一点 */
  private getBadgeShape() {
    const { model } = this.props
    const props = model.properties as unknown as FlowNodeProperties
    return h(
      'text',
      {
        key: 'badge',
        x: model.x - model.width / 2 + 8,
        y: model.y - model.height / 2 + 15,
        fontSize: 11,
        fill: props.nodeStroke,
        textAnchor: 'start',
      },
      props.badge,
    )
  }

  /** 居中多行正文 */
  private getBodyShape() {
    const { model } = this.props
    const props = model.properties as unknown as FlowNodeProperties
    const lines = wrapText(model.text?.value ?? '', props.textMaxWidth, FONT_SIZE)
    // 首行基线：整块文字在节点高度内垂直居中
    const firstBaseline = model.y - ((lines.length - 1) * LINE_HEIGHT) / 2 + FONT_SIZE / 3
    return h(
      'g',
      { key: 'body-text' },
      lines.map((line, index) =>
        h(
          'text',
          {
            key: `line-${index}`,
            x: model.x,
            y: firstBaseline + index * LINE_HEIGHT,
            fontSize: FONT_SIZE,
            fill: props.nodeText,
            textAnchor: 'middle',
          },
          line,
        ),
      ),
    )
  }

  getShape() {
    const { model } = this.props
    const props = model.properties as unknown as FlowNodeProperties
    return h('g', { className: 'flow-node' }, [
      h('rect', {
        key: 'rect',
        x: model.x - model.width / 2,
        y: model.y - model.height / 2,
        width: model.width,
        height: model.height,
        rx: props.radius,
        ry: props.radius,
        fill: props.nodeFill,
        stroke: props.nodeStroke,
        strokeWidth: 1,
      }),
      this.getBadgeShape(),
      this.getBodyShape(),
    ])
  }

  /** 抑制内置单段文本：正文由 getBodyShape 自己画 */
  getText() {
    return null
  }
}

export class FlowNodeModel extends RectNodeModel {
  setAttributes() {
    const props = this.properties as unknown as FlowNodeProperties
    if (typeof props.width === 'number') this.width = props.width
    if (typeof props.height === 'number') this.height = props.height
    if (typeof props.radius === 'number') this.radius = props.radius
  }
}

// ---------------------------------------------------------------- 泳道表头

interface LaneHeaderProperties {
  width: number
  height: number
  headerFill: string
  headerText: string
}

export class LaneHeaderView extends RectNode {
  getShape() {
    const { model } = this.props
    const props = model.properties as unknown as LaneHeaderProperties
    return h('g', { className: 'lane-header' }, [
      h('rect', {
        key: 'rect',
        x: model.x - model.width / 2,
        y: model.y - model.height / 2,
        width: model.width,
        height: model.height,
        rx: 4,
        ry: 4,
        fill: props.headerFill,
        stroke: '#ffffff',
        strokeWidth: 1,
      }),
      h(
        'text',
        {
          key: 'label',
          x: model.x,
          y: model.y + 5,
          fontSize: 14,
          fill: props.headerText,
          textAnchor: 'middle',
        },
        model.text?.value ?? '',
      ),
    ])
  }

  getText() {
    return null
  }
}

export class LaneHeaderModel extends RectNodeModel {
  setAttributes() {
    const props = this.properties as unknown as LaneHeaderProperties
    if (typeof props.width === 'number') this.width = props.width
    if (typeof props.height === 'number') this.height = props.height
    // 不可拖，但保留命中（阶段 B 靠点击表头选中泳道）
    this.draggable = false
  }
}

// ---------------------------------------------------------------- 行带

interface StepBandProperties {
  width: number
  height: number
  step: number
  zebra: boolean
  stepColumnWidth: number
  fontSize: number
}

export class StepBandView extends RectNode {
  getShape() {
    const { model } = this.props
    const props = model.properties as unknown as StepBandProperties
    const left = model.x - model.width / 2
    const top = model.y - model.height / 2
    return h('g', { className: 'step-band' }, [
      h('rect', {
        key: 'band',
        x: left,
        y: top,
        width: model.width,
        height: model.height,
        fill: props.zebra ? '#f5f8fc' : '#ffffff',
        stroke: '#e4e9f0',
        strokeWidth: 1,
      }),
      // 左侧「步骤」号列的格子
      h('rect', {
        key: 'step-cell',
        x: left,
        y: top,
        width: props.stepColumnWidth,
        height: model.height,
        fill: '#fafbfd',
        stroke: '#e4e9f0',
        strokeWidth: 1,
      }),
      h(
        'text',
        {
          key: 'step-no',
          x: left + props.stepColumnWidth / 2,
          y: model.y + 5,
          fontSize: props.fontSize,
          fill: '#8a94a6',
          textAnchor: 'middle',
        },
        String(props.step),
      ),
    ])
  }

  getText() {
    return null
  }
}

export class StepBandModel extends RectNodeModel {
  setAttributes() {
    const props = this.properties as unknown as StepBandProperties
    if (typeof props.width === 'number') this.width = props.width
    if (typeof props.height === 'number') this.height = props.height
    this.draggable = false
    // 彻底穿透：行带绝不抢画布的点击与框选
    this.isHittable = false
  }
}

export const FLOW_NODE = { type: 'flow-node', view: FlowNodeView, model: FlowNodeModel }
export const LANE_HEADER = { type: 'lane-header', view: LaneHeaderView, model: LaneHeaderModel }
export const STEP_BAND = { type: 'step-band', view: StepBandView, model: StepBandModel }
```

- [ ] **Step 2: 类型门禁**

Run: `pnpm build`
Expected: 通过。若报 `draggable` / `isHittable` / `h` / `RectNodeModel` 不存在，说明与 2.2.5 实际导出不符——已核实 `@logicflow/core` 的 index 导出 `h`、`RectNode`、`RectNodeModel`，且 `BaseNodeModel` 上有 `draggable: boolean` 与 `isHittable: boolean`，应通过。

- [ ] **Step 3: Commit**

```bash
git add src/editor/nodes.ts
git commit -m "feat(editor): 加流程节点/泳道表头/行带三种自定义节点"
```

---

### Task 7: `edges.ts` —— 自定义连线（两类线型 + 肘形路由）

**Files:**
- Create: `src/editor/edges.ts`
- Create: `src/editor/edges.spec.ts`

**Interfaces:**
- Consumes: `EDGE_THEME` / `EdgeTheme`（Task 1）
- Produces: `routeBetween(start, end): { x, y }[]`、`FlowEdgeModel`、`FLOW_EDGE = { type: 'flow-edge', view, model }`

**约定（Task 8 依赖）**：连线 `properties` 需带 `kind` 与 `theme`（`EdgeTheme`）。

- [ ] **Step 1: 写失败测试**

创建 `src/editor/edges.spec.ts`。注意：已核实 `@logicflow/core` 在纯 node 里可以 import（不需要 DOM），所以本文件可以在顶部 import 里带上 LogicFlow —— 但**只 import 不渲染**。

```ts
import { describe, expect, it } from 'vitest'

import { routeBetween } from './edges'

describe('routeBetween', () => {
  it('同一行上只留两端，不产生退化折线', () => {
    expect(routeBetween({ x: 0, y: 50 }, { x: 100, y: 50 })).toEqual([
      { x: 0, y: 50 },
      { x: 100, y: 50 },
    ])
  })

  it('不同行时走肘形：先水平、再垂直、再水平', () => {
    expect(routeBetween({ x: 0, y: 0 }, { x: 100, y: 80 })).toEqual([
      { x: 0, y: 0 },
      { x: 50, y: 0 },
      { x: 50, y: 80 },
      { x: 100, y: 80 },
    ])
  })

  it('两端同一点时不产出重复点', () => {
    expect(routeBetween({ x: 5, y: 5 }, { x: 5, y: 5 })).toEqual([{ x: 5, y: 5 }])
  })

  it('同一列上下相邻时只留两端（垂直共线）', () => {
    expect(routeBetween({ x: 0, y: 0 }, { x: 0, y: 100 })).toEqual([
      { x: 0, y: 0 },
      { x: 0, y: 100 },
    ])
  })
})
```

- [ ] **Step 2: 运行确认失败**

Run: `pnpm test src/editor/edges.spec.ts`
Expected: FAIL —— 找不到 `./edges`。

- [ ] **Step 3: 实现**

创建 `src/editor/edges.ts`：

```ts
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
```

- [ ] **Step 4: 运行确认通过**

Run: `pnpm test src/editor/edges.spec.ts`
Expected: PASS，4 个用例全绿。

- [ ] **Step 5: 类型门禁**

Run: `pnpm build`
Expected: 通过。

- [ ] **Step 6: Commit**

```bash
git add src/editor/edges.ts src/editor/edges.spec.ts
git commit -m "feat(editor): 加两类线型的自定义连线与可测的肘形路由"
```

---

### Task 8: `render.ts` —— 文档 → 渲染配置（纯函数 + 可测）

「文档」与「画布」之间唯一的转换点。纯函数，所以是本计划测试性价比最高的地方：几何、配色、背景压制、注册全在这里被断言。

**Files:**
- Create: `src/editor/render.ts`
- Create: `src/editor/render.spec.ts`

**Interfaces:**
- Consumes: `FlowDoc`（Task 1）、`LANE_THEME` / `EDGE_THEME`（Task 1）、`geometry.*`（Task 3）、`FLOW_NODE` / `LANE_HEADER` / `STEP_BAND`（Task 6）、`FLOW_EDGE`（Task 7）
- Produces:
  - `NODE_TYPE = 'flow-node'`、`EDGE_TYPE = 'flow-edge'`、`LANE_HEADER_TYPE = 'lane-header'`、`STEP_BAND_TYPE = 'step-band'`
  - `RenderNodeConfig`、`RenderEdgeConfig`、`RenderData`
  - `buildRenderData(doc): RenderData`、`isBackgroundType(type): boolean`、`registerEditorNodes(lf): void`

- [ ] **Step 1: 写失败测试**

创建 `src/editor/render.spec.ts`：

```ts
import { describe, expect, it } from 'vitest'

import { buildRenderData, isBackgroundType, LANE_HEADER_TYPE, NODE_TYPE, STEP_BAND_TYPE } from './render'
import { harvestInboundDoc } from './seed/harvestInbound'
import type { FlowDoc } from './types'

function smallDoc(): FlowDoc {
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
    stepCount: 2,
    nodes: [{ id: 'A01', text: '一', laneId: 'l1', step: 1, kind: 'process' }],
    edges: [],
  }
}

describe('buildRenderData', () => {
  it('为每条泳道生成一个表头节点', () => {
    const { nodes } = buildRenderData(smallDoc())
    const headers = nodes.filter((node) => node.type === LANE_HEADER_TYPE)
    expect(headers.map((node) => node.id)).toEqual(['bg-header-l1', 'bg-header-l2'])
  })

  it('为每个步骤生成一个行带节点，行带横跨整图', () => {
    const { nodes } = buildRenderData(smallDoc())
    const bands = nodes.filter((node) => node.type === STEP_BAND_TYPE)
    expect(bands).toHaveLength(2)
    expect(bands[0].properties.width).toBe(410)
  })

  it('用户节点坐标来自几何推导，不是文档里存的', () => {
    const { nodes } = buildRenderData(smallDoc())
    const node = nodes.find((item) => item.id === 'A01')
    expect(node).toBeDefined()
    expect(node!.type).toBe(NODE_TYPE)
    expect(node!.x).toBe(160)
    expect(node!.y).toBe(90)
    expect(node!.properties.width).toBe(180)
    expect(node!.properties.badge).toBe('A01')
  })

  it('正文可用宽度与几何算高度时用的是同一个值', () => {
    const { nodes } = buildRenderData(smallDoc())
    const node = nodes.find((item) => item.id === 'A01')!
    // 节点宽 180 − 两侧正文内边距 10*2 = 160
    expect(node.properties.textMaxWidth).toBe(160)
  })

  it('背景节点的 zIndex 低于用户节点', () => {
    const { nodes } = buildRenderData(smallDoc())
    const band = nodes.find((item) => item.type === STEP_BAND_TYPE)!
    const node = nodes.find((item) => item.id === 'A01')!
    expect(band.zIndex!).toBeLessThan(node.zIndex!)
  })

  it('背景节点都标记了 isBackground', () => {
    const { nodes } = buildRenderData(smallDoc())
    const backgrounds = nodes.filter((node) => node.type !== NODE_TYPE)
    expect(backgrounds.length).toBeGreaterThan(0)
    for (const node of backgrounds) {
      expect(node.properties.isBackground).toBe(true)
    }
  })

  it('连线带上 kind 与主题，异常线是虚线', () => {
    const doc = smallDoc()
    doc.nodes.push({ id: 'A02', text: '二', laneId: 'l2', step: 2, kind: 'process' })
    doc.edges.push({ id: 'e1', source: 'A01', target: 'A02', kind: 'exception', label: '不合格品' })
    const { edges } = buildRenderData(doc)
    expect(edges).toHaveLength(1)
    expect(edges[0].text).toBe('不合格品')
    expect(edges[0].properties.kind).toBe('exception')
    expect(edges[0].properties.theme.dashArray).toBeDefined()
  })
})

describe('isBackgroundType', () => {
  it('区分背景与用户元素', () => {
    expect(isBackgroundType('lane-header')).toBe(true)
    expect(isBackgroundType('step-band')).toBe(true)
    expect(isBackgroundType('flow-node')).toBe(false)
    expect(isBackgroundType('flow-edge')).toBe(false)
  })
})

describe('示例图能转成渲染数据', () => {
  it('21 个用户节点 + 6 表头 + 12 行带', () => {
    const { nodes, edges } = buildRenderData(harvestInboundDoc)
    expect(nodes.filter((node) => node.type === NODE_TYPE)).toHaveLength(21)
    expect(nodes.filter((node) => node.type === LANE_HEADER_TYPE)).toHaveLength(6)
    expect(nodes.filter((node) => node.type === STEP_BAND_TYPE)).toHaveLength(12)
    expect(edges).toHaveLength(harvestInboundDoc.edges.length)
  })

  it('所有坐标都是有限数字（不出现 NaN）', () => {
    const { nodes } = buildRenderData(harvestInboundDoc)
    for (const node of nodes) {
      expect(Number.isFinite(node.x)).toBe(true)
      expect(Number.isFinite(node.y)).toBe(true)
      expect(node.properties.width as number).toBeGreaterThan(0)
      expect(node.properties.height as number).toBeGreaterThan(0)
    }
  })
})
```

- [ ] **Step 2: 运行确认失败**

Run: `pnpm test src/editor/render.spec.ts`
Expected: FAIL —— 找不到 `./render`。

- [ ] **Step 3: 实现**

创建 `src/editor/render.ts`：

```ts
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
```

- [ ] **Step 4: 运行确认通过**

Run: `pnpm test src/editor/render.spec.ts`
Expected: PASS，10 个用例全绿。

- [ ] **Step 5: 全量测试 + 类型门禁**

Run: `pnpm test && pnpm build`
Expected: 全绿 + 通过。

- [ ] **Step 6: Commit**

```bash
git add src/editor/render.ts src/editor/render.spec.ts
git commit -m "feat(editor): 加文档到渲染配置的纯函数转换与背景白名单判定"
```

---

### Task 9: `FlowEditor.vue` 只读渲染 + `App.vue` 模式切换

**Files:**
- Create: `src/views/FlowEditor.vue`
- Modify: `src/App.vue`（整体替换为外壳）

**Interfaces:**
- Consumes: `registerEditorNodes` / `buildRenderData`（Task 8）、`harvestInboundDoc`（Task 5）、`docSize`（Task 3）
- Produces: 默认导出组件 `FlowEditor`（无 props；阶段 B 会加编辑）

- [ ] **Step 1: 实现编辑视图（阶段 A：只读）**

创建 `src/views/FlowEditor.vue`：

```vue
<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

import LogicFlow from '@logicflow/core'
import '@logicflow/core/lib/style/index.css'

import { docSize } from '../editor/geometry'
import { buildRenderData, registerEditorNodes } from '../editor/render'
import { harvestInboundDoc } from '../editor/seed/harvestInbound'

// 阶段 A 先直接渲染示例图；阶段 B 改成从 storage 载入，此处会接编辑
const doc = harvestInboundDoc

const canvas = ref<HTMLDivElement>()
let lf: LogicFlow | undefined

/**
 * 与导图视图同样的取景策略：先尝试整图放下，太小则退到「装下宽度」。
 * 这张图纵向比横向长得多，两轴都塞进视口会把字缩到看不见。
 */
function frameGraph() {
  if (!lf || !canvas.value) return
  const { clientWidth, clientHeight } = canvas.value
  const { width, height } = docSize(doc)
  const fitAll = Math.min(clientWidth / (width + 48), clientHeight / (height + 48))
  const fitWidth = Math.min(clientWidth / (width + 48), 1.4)
  lf.zoom(fitAll >= 0.5 ? fitAll : fitWidth)
  lf.translateCenter()
}

function handleResize() {
  lf?.resize()
}

onMounted(() => {
  lf = new LogicFlow({
    container: canvas.value!,
    grid: false,
    isSilentMode: false,
    textEdit: false,
    adjustEdge: false,
  })

  registerEditorNodes(lf)
  lf.render(buildRenderData(doc))
  frameGraph()

  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  lf?.destroy()
})
</script>

<template>
  <div class="flow-root">
    <div ref="canvas" class="flow-canvas"></div>
  </div>
</template>

<style scoped>
.flow-root {
  width: 100%;
  height: 100%;
}

.flow-canvas {
  width: 100%;
  height: 100%;
  background: #ffffff;
}
</style>
```

- [ ] **Step 2: 把 `App.vue` 换成外壳**

`src/App.vue` 整体替换为：

```vue
<script setup lang="ts">
import { ref } from 'vue'

import WmsStructure from './components/WmsStructure.vue'
import FlowEditor from './views/FlowEditor.vue'

type Mode = 'mindmap' | 'flow'

const mode = ref<Mode>('flow')
</script>

<template>
  <div class="app">
    <div class="mode-switch">
      <button type="button" :class="{ active: mode === 'mindmap' }" @click="mode = 'mindmap'">
        思维导图
      </button>
      <button type="button" :class="{ active: mode === 'flow' }" @click="mode = 'flow'">
        流程图
      </button>
    </div>

    <!-- v-if 而非 v-show：LogicFlow 取景依赖容器的可见尺寸，
         隐藏状态下容器为 0 会把图缩到看不见 -->
    <WmsStructure v-if="mode === 'mindmap'" />
    <FlowEditor v-else />
  </div>
</template>

<style scoped>
.app {
  width: 100%;
  height: 100%;
}

.mode-switch {
  position: fixed;
  top: 20px;
  left: 20px;
  z-index: 999;
  display: flex;
  gap: 6px;
  background: #ffffff;
  padding: 4px;
  border: 1px solid #e4e9f0;
  border-radius: 6px;
}

.mode-switch button {
  padding: 6px 14px;
  font-size: 13px;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: #4b5563;
  cursor: pointer;
}

.mode-switch button.active {
  background: #1677ff;
  color: #ffffff;
}
</style>
```

- [ ] **Step 3: 类型门禁**

Run: `pnpm build`
Expected: 通过（`vue-tsc -b` 会检查 `.vue` 的模板与 script）。

- [ ] **Step 4: 实机确认**

Run: `pnpm dev`
Expected（人工核对，这是阶段 A 的核心验收）：
1. 默认进入「流程图」模式，看到 6 条泳道表头（两条蓝、一条橙、一条绿、一条紫、一条红）、左侧 1–12 步骤号列、斑马行带。
2. 21 个节点按 A01–A21 编号就位，所在列与行号**对上 spec §9 的表**。
3. 节点配色跟随泳道；连线上有「合格改刀料」「不合格品」「数量不足」等标签；异常连线是红虚线。
4. 切到「思维导图」再切回来，两边都正常（导图仍能正确取景）。
5. **背景不被误操作**：拖表头无反应；点行带空白处不会选中任何东西。

有偏差先怀疑三处：`harvestInbound` 的 `laneId`/`step`、`geometry.ts` 的 `nodeGeom`、`theme.ts` 的色值。几何类问题用 `pnpm test src/editor/geometry.spec.ts` 缩小范围。

- [ ] **Step 5: Commit**

```bash
git add src/App.vue src/views/FlowEditor.vue
git commit -m "feat(editor): 加流程图只读渲染视图与双模式外壳"
```

---

### Task 10: 阶段 A 验收（交用户核对转录）

**这不是编码任务，是阶段 A 的门。** spec 规定阶段 A 单独验收：转录或几何一旦有错，越晚发现返工面越大。

**Files:** 无（只读核对）

- [ ] **Step 1: 跑全部自动化验证**

```bash
pnpm test && pnpm build
```
Expected: 全绿 + 构建通过。

- [ ] **Step 2: 出图交核对**

Run: `pnpm dev`，浏览器截图（或临时把导图模式的 Snapshot 逻辑接到流程视图导出 PNG）。
Expected: 得到一张能与参考图并排对照的整图。

- [ ] **Step 3: 逐条核对清单（交用户）**

1. **泳道**：6 条的名称、顺序、配色是否与原图一致（注意「待切头/柄料」在原图里与「收货员」同为**蓝色**）。
2. **节点所在格**：21 个节点的列与步骤是否与原图一致（spec §9 的表）。
3. **节点文字**：是否与原图逐字一致（含「·」「/」等符号与换行位置）。
4. **连线**：23 条是否与原图一致，特别是 4 处标了 `⚠待核对` 的：
   - A06 → A08 与 A06 → A13 的标签归属（「合格待切头/柄料」/「合格改刀料」哪条是哪条）；
   - A13 → A21 那条长竖线的**起点**（我读作 A13，也可能是从 A06 一路下来的主干）；
   - A03 → A05 这条红虚线的**起点**（我读作 A03，也可能是 A04）；
   - A12 → A19 的终点与「仅收货，暂不入库」标签位置。
5. **几何观感**：行高/列宽比例、节点是否在格内垂直居中、有没有节点超出所在行带、左侧步骤号列宽度是否合适。

- [ ] **Step 4: 记录核对结果**

把修正逐条改回 `src/editor/seed/harvestInbound.ts`（唯一需要改的文件），重跑 `pnpm test`（示例图断言会自动复查），然后：

```bash
git add src/editor/seed/harvestInbound.ts
git commit -m "fix(editor): 按原图核对结果修正示例图转录"
```

**阶段 A 到此结束。** 阶段 B 从这里开始。

---

## 阶段 B：编辑能力

### Task 11: `ops.ts` —— 编辑操作与格子吸附（纯函数 + 可测）

阶段 B 的核心。**所有编辑都是「文档进、文档出」的纯函数**，UI 只负责把事件翻译成这些调用。这样「增删泳道自动重排」「步骤数调小不丢节点」这些正确性都能单测，而不是靠手点。

**Files:**
- Create: `src/editor/ops.ts`
- Create: `src/editor/ops.spec.ts`

**Interfaces:**
- Consumes: `FlowDoc` / `FlowNode` / `Lane`（Task 1）、`laneLeft`（Task 3）
- Produces:
  - `Cell = { laneId: string; step: number }`
  - `pointToCell(doc, point): Cell | null`
  - `isCellOccupied(doc, cell): boolean`、`findFreeCell(doc, cell): Cell | null`
  - `nextNodeId(doc): string`
  - `addNode(doc, cell, text): FlowDoc`、`moveNode(doc, nodeId, cell): FlowDoc`
  - `setNodeText(doc, nodeId, text): FlowDoc`、`setEdgeLabel(doc, edgeId, label): FlowDoc`
  - `removeNodes(doc, nodeIds): FlowDoc`
  - `setLaneName(doc, laneId, name): FlowDoc`、`addLane(doc): FlowDoc`
  - `removeLane(doc, laneId): { ok: true; doc: FlowDoc } | { ok: false; error: string }`
  - `setStepCount(doc, stepCount): FlowDoc`

- [ ] **Step 1: 写失败测试**

创建 `src/editor/ops.spec.ts`：

```ts
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
    // x=100 落在 l1（60..260），y=140 落在第 2 行带（40..140 是第 1 带，140..240 是第 2 带）
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

  it('目标格被别的节点占着就顺延', () => {
    const doc = moveNode(makeDoc(), 'A02', { laneId: 'l1', step: 1 })
    expect(doc.nodes.find((node) => node.id === 'A02')!.step).toBe(3)
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
```

- [ ] **Step 2: 运行确认失败**

Run: `pnpm test src/editor/ops.spec.ts`
Expected: FAIL —— 找不到 `./ops`。

- [ ] **Step 3: 实现**

创建 `src/editor/ops.ts`：

```ts
/**
 * 编辑操作。**全部是纯函数：文档进、文档出**，不改入参、不碰 DOM、不碰 LogicFlow。
 *
 * 这样安排的理由：「增删泳道自动重排」「步骤数调小不丢节点」这些正确性，
 * 用手点很难覆盖全，而纯函数可以直接单测。UI 只负责把事件翻译成这些调用。
 *
 * 一条贯穿全部操作的原则：**绝不静默删除用户的节点**。放不下就抛错、越界就上移、
 * 泳道里还有节点就拒绝删除，让用户自己决定。
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
 * 格子确实不够时（泳道数 × 新步骤数 < 节点总数）**拒绝这次操作并抛错**，
 * 而不是把节点堆成重复格留下一个坏状态：拒绝是可恢复的，坏数据不是。
 */
export function setStepCount(doc: FlowDoc, stepCount: number): FlowDoc {
  const next = Math.max(1, Math.floor(stepCount))
  if (next >= doc.stepCount) return { ...doc, stepCount: next }

  const capacity = doc.lanes.length * next
  if (doc.nodes.length > capacity) {
    throw new Error(
      `放不下：${doc.lanes.length} 条泳道 × ${next} 步 = ${capacity} 格，却有 ${doc.nodes.length} 个节点`,
    )
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
```

- [ ] **Step 4: 运行确认通过**

Run: `pnpm test src/editor/ops.spec.ts`
Expected: PASS，全部用例通过（11 组 describe，约 38 个用例）。

- [ ] **Step 5: 全量测试 + 类型门禁**

Run: `pnpm test && pnpm build`
Expected: 全绿 + 通过。

- [ ] **Step 6: Commit**

```bash
git add src/editor/ops.ts src/editor/ops.spec.ts
git commit -m "feat(editor): 加编辑操作纯函数与格子吸附，含守恒规则"
```

---

### Task 12: `storage.ts` —— 单文档存取与 JSON 导入导出

**Files:**
- Create: `src/editor/storage.ts`
- Create: `src/editor/storage.spec.ts`

**Interfaces:**
- Consumes: `FlowDoc`（Task 1）、`validateDoc` / `ValidateResult`（Task 4）、`harvestInboundDoc`（Task 5）
- Produces: `STORAGE_KEY`、`StorageLike`、`serializeDoc(doc): string`、`parseDoc(raw): ValidateResult`、`loadDoc(storage?, fallback?): FlowDoc`、`saveDoc(doc, storage?)`、`clearDoc(storage?)`

- [ ] **Step 1: 写失败测试**

创建 `src/editor/storage.spec.ts`：

```ts
import { describe, expect, it } from 'vitest'

import { harvestInboundDoc } from './seed/harvestInbound'
import { clearDoc, loadDoc, parseDoc, saveDoc, serializeDoc, STORAGE_KEY, type StorageLike } from './storage'
import type { FlowDoc } from './types'

/** 内存版 storage：storage.ts 把 Storage 当参数传入，正是为了这里能替身 */
function memoryStorage(): StorageLike & { map: Map<string, string> } {
  const map = new Map<string, string>()
  return {
    map,
    getItem: (key) => map.get(key) ?? null,
    setItem: (key, value) => {
      map.set(key, value)
    },
    removeItem: (key) => {
      map.delete(key)
    },
  }
}

describe('serializeDoc / parseDoc', () => {
  it('序列化再解析回来与原文档等价', () => {
    const parsed = parseDoc(serializeDoc(harvestInboundDoc))
    expect(parsed.ok).toBe(true)
    if (parsed.ok) expect(parsed.doc).toEqual(harvestInboundDoc)
  })

  it('解析坏 JSON 返回可读错误而不抛异常', () => {
    const parsed = parseDoc('{ 这不是 json')
    expect(parsed.ok).toBe(false)
    if (!parsed.ok) expect(parsed.errors.join()).toMatch(/JSON/)
  })

  it('解析结构不合法的 JSON 返回校验错误', () => {
    const parsed = parseDoc(JSON.stringify({ schemaVersion: 2 }))
    expect(parsed.ok).toBe(false)
  })

  it('null 返回错误而不是崩溃', () => {
    expect(parseDoc(null).ok).toBe(false)
  })
})

describe('loadDoc / saveDoc / clearDoc', () => {
  it('空存储时回落到示例图', () => {
    expect(loadDoc(memoryStorage(), harvestInboundDoc).id).toBe(harvestInboundDoc.id)
  })

  it('存了再读拿到存进去的那份', () => {
    const storage = memoryStorage()
    const edited: FlowDoc = { ...harvestInboundDoc, name: '改过的' }
    saveDoc(edited, storage)
    expect(loadDoc(storage, harvestInboundDoc).name).toBe('改过的')
  })

  it('存储里是坏数据时回落到示例图（不白屏、不抛异常）', () => {
    const storage = memoryStorage()
    storage.setItem(STORAGE_KEY, '{ 坏的')
    expect(loadDoc(storage, harvestInboundDoc).id).toBe(harvestInboundDoc.id)
  })

  it('clearDoc 之后读到的是回落值', () => {
    const storage = memoryStorage()
    saveDoc({ ...harvestInboundDoc, name: '改过的' }, storage)
    clearDoc(storage)
    expect(loadDoc(storage, harvestInboundDoc).name).toBe(harvestInboundDoc.name)
  })
})
```

- [ ] **Step 2: 运行确认失败**

Run: `pnpm test src/editor/storage.spec.ts`
Expected: FAIL —— 找不到 `./storage`。

- [ ] **Step 3: 实现**

创建 `src/editor/storage.ts`：

```ts
/**
 * 单文档存取。
 *
 * storage 作为**参数**传入（默认才是 window.localStorage），这样单测能塞内存替身，
 * 不必依赖 jsdom。任何一步坏掉都回落到示例图（fallback），
 * 因为「打开应用看到一张图」远好过「打开应用看到白屏」。
 */
import { validateDoc } from './doc'
import { harvestInboundDoc } from './seed/harvestInbound'
import type { FlowDoc } from './types'
import type { ValidateResult } from './doc'

export const STORAGE_KEY = 'wms-flow:doc'

export interface StorageLike {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
}

export function serializeDoc(doc: FlowDoc): string {
  return JSON.stringify(doc, null, 2)
}

/** 解析 + 校验。坏 JSON 转成可读错误返回，不往上抛 */
export function parseDoc(raw: string | null): ValidateResult {
  if (raw === null) return { ok: false, errors: ['没有可解析的内容'] }
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    return { ok: false, errors: [`JSON 解析失败：${message}`] }
  }
  return validateDoc(parsed)
}

function defaultStorage(): StorageLike {
  return window.localStorage
}

export function loadDoc(storage?: StorageLike, fallback: FlowDoc = harvestInboundDoc): FlowDoc {
  const target = storage ?? defaultStorage()
  const result = parseDoc(target.getItem(STORAGE_KEY))
  return result.ok ? result.doc : fallback
}

export function saveDoc(doc: FlowDoc, storage?: StorageLike): void {
  const target = storage ?? defaultStorage()
  target.setItem(STORAGE_KEY, serializeDoc(doc))
}

export function clearDoc(storage?: StorageLike): void {
  const target = storage ?? defaultStorage()
  target.removeItem(STORAGE_KEY)
}
```

- [ ] **Step 4: 运行确认通过**

Run: `pnpm test src/editor/storage.spec.ts`
Expected: PASS，8 个用例全绿。

- [ ] **Step 5: 全量测试 + 类型门禁**

Run: `pnpm test && pnpm build`
Expected: 全绿 + 通过。

- [ ] **Step 6: Commit**

```bash
git add src/editor/storage.ts src/editor/storage.spec.ts
git commit -m "feat(editor): 加单文档存取与 JSON 导入导出，坏数据回落示例图"
```

---

### Task 13: `FlowEditor.vue` 接上编辑能力

**关键设计决定：文档是唯一事实源，LogicFlow 的画布模型不是。** 因此每次编辑都是「算新文档 → 用新文档重渲染 → 恢复视口」，而不是去逐个改画布上的模型。一条路径，永远一致，不会出现「画布与文档不一致」的补丁式 bug。代价是每次编辑整图重渲染（40 个节点量级无感）和选中状态会丢（可接受）。

**事件名有个坑**：LogicFlow 2.2.5 里双击事件叫 **`dbclick`**（不是 `dblclick`），拖拽结束叫 **`node:drop`**（不是 `node:dragstop`）。已核实的事件名：`node:dbclick`、`edge:dbclick`、`node:click`、`node:drop`、`node:add`、`node:delete`、`edge:add`、`edge:delete`、`graph:rendered`。

**Files:**
- Modify: `src/editor/ops.ts`（补 `addEdge`）
- Modify: `src/editor/ops.spec.ts`（补 `addEdge` 测试）
- Modify: `src/views/FlowEditor.vue`（整体替换为编辑版）

**Interfaces:**
- Consumes: 阶段 A 全部 + `ops.*`（Task 11）+ `storage.*`（Task 12）
- Produces: `addEdge(doc, source, target): FlowDoc`（ops.ts）

- [ ] **Step 1: 给 `ops.ts` 补 `addEdge`（先写测试）**

在 `src/editor/ops.spec.ts` 末尾追加：

```ts
describe('addEdge', () => {
  it('在两个节点之间加一条主流程连线', () => {
    const doc = addEdge(makeDoc(), 'A02', 'A01')
    expect(doc.edges).toHaveLength(2)
    expect(doc.edges[1]).toMatchObject({ source: 'A02', target: 'A01', kind: 'main' })
  })

  it('连线 id 不重复', () => {
    const once = addEdge(makeDoc(), 'A02', 'A01')
    const twice = addEdge(once, 'A01', 'A02')
    expect(new Set(twice.edges.map((edge) => edge.id)).size).toBe(3)
  })

  it('自环抛错', () => {
    expect(() => addEdge(makeDoc(), 'A01', 'A01')).toThrow(/自环/)
  })

  it('端点不存在时抛错', () => {
    expect(() => addEdge(makeDoc(), 'A01', 'nope')).toThrow(/未知节点/)
  })
})
```

同时把 `addEdge` 加进该文件顶部的 import 列表。

Run: `pnpm test src/editor/ops.spec.ts`
Expected: FAIL —— `addEdge` 未导出。

- [ ] **Step 2: 实现 `addEdge`**

在 `src/editor/ops.ts` 末尾追加：

```ts
/** 新建连线。id 用两端节点串联，天然不重复；自环与悬空端点直接抛错 */
export function addEdge(doc: FlowDoc, source: string, target: string): FlowDoc {
  findNode(doc, source)
  findNode(doc, target)
  if (source === target) throw new Error('不支持自环连线（起点与终点是同一个节点）')
  return {
    ...doc,
    edges: [...doc.edges, { id: `e-${source}-${target}`, source, target, kind: 'main' }],
  }
}
```

Run: `pnpm test src/editor/ops.spec.ts`
Expected: PASS，含新增的 4 个 `addEdge` 用例全部通过。

- [ ] **Step 3: 把 `FlowEditor.vue` 整体替换为编辑版**

```vue
<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

import LogicFlow from '@logicflow/core'
import '@logicflow/core/lib/style/index.css'

import { docSize } from '../editor/geometry'
import { NODE_TYPE, buildRenderData, registerEditorNodes } from '../editor/render'
import {
  addEdge,
  addNode,
  moveNode,
  pointToCell,
  removeNodes,
  setEdgeLabel,
  setLaneName,
  setNodeText,
} from '../editor/ops'
import { loadDoc, saveDoc } from '../editor/storage'
import type { FlowDoc } from '../editor/types'

const doc = ref<FlowDoc>(loadDoc())
const canvas = ref<HTMLDivElement>()
const message = ref('')

/** 就地编辑覆盖层的状态。三种目标共用一套：节点文字 / 连线标签 / 泳道名 */
interface EditorState {
  kind: 'node' | 'edge' | 'lane'
  id: string
  left: number
  top: number
  width: number
  height: number
  value: string
}
const editor = ref<EditorState | null>(null)

/** 自建撤销栈：LogicFlow 内置的 history 操作的是画布模型而非文档，用了就会与文档脱节 */
const past = ref<FlowDoc[]>([])
const future = ref<FlowDoc[]>([])

let lf: LogicFlow | undefined
let saveTimer: number | undefined

function frameGraph() {
  if (!lf || !canvas.value) return
  const { clientWidth, clientHeight } = canvas.value
  const { width, height } = docSize(doc.value)
  const fitAll = Math.min(clientWidth / (width + 48), clientHeight / (height + 48))
  const fitWidth = Math.min(clientWidth / (width + 48), 1.4)
  lf.zoom(fitAll >= 0.5 ? fitAll : fitWidth)
  lf.translateCenter()
}

/** 重渲染并恢复视口——每次编辑都走这一条路径，保证画布与文档一致 */
function rerender() {
  if (!lf) return
  const transform = lf.graphModel.transformModel
  const view = { scale: transform.SCALE_X, x: transform.TRANSLATE_X, y: transform.TRANSLATE_Y }
  lf.render(buildRenderData(doc.value))
  lf.zoom(view.scale)
  lf.translate(view.x, view.y)
}

function persist() {
  window.clearTimeout(saveTimer)
  saveTimer = window.setTimeout(() => saveDoc(doc.value), 300)
}

/** 所有编辑的收口：压撤销栈 → 重渲染 → 落盘 */
function commit(next: FlowDoc) {
  past.value = [...past.value, doc.value]
  future.value = []
  doc.value = next
  rerender()
  persist()
}

function undo() {
  const previous = past.value[past.value.length - 1]
  if (!previous) return
  future.value = [doc.value, ...future.value]
  past.value = past.value.slice(0, -1)
  doc.value = previous
  rerender()
  persist()
}

function redo() {
  const next = future.value[0]
  if (!next) return
  past.value = [...past.value, doc.value]
  future.value = future.value.slice(1)
  doc.value = next
  rerender()
  persist()
}

function report(error: unknown) {
  message.value = error instanceof Error ? error.message : String(error)
}

// ---------------------------------------------------------- 从左栏拖拽创建

function onPaletteDragStart(event: DragEvent) {
  event.dataTransfer?.setData('text/plain', NODE_TYPE)
}

function onCanvasDrop(event: DragEvent) {
  event.preventDefault()
  if (!lf || !canvas.value) return
  const rect = canvas.value.getBoundingClientRect()
  const point = lf.graphModel.transformModel.HtmlPointToCanvasPoint([
    event.clientX - rect.left,
    event.clientY - rect.top,
  ])
  const cell = pointToCell(doc.value, { x: point[0], y: point[1] })
  if (!cell) return
  try {
    commit(addNode(doc.value, cell, '新节点'))
  } catch (error) {
    report(error)
  }
}

// ---------------------------------------------------------- 拖拽移动节点

function onNodeDrop({ data }: { data: { id: string } }) {
  if (!lf) return
  const model = lf.getNodeModelById(data.id)
  if (!model) return
  const cell = pointToCell(doc.value, { x: model.x, y: model.y })
  if (!cell) {
    rerender() // 拖到格子外：拉回原处
    return
  }
  try {
    commit(moveNode(doc.value, data.id, cell))
  } catch (error) {
    report(error)
    rerender()
  }
}

// ---------------------------------------------------------- 就地编辑覆盖层

function openEditorFor(kind: EditorState['kind'], id: string) {
  if (!lf) return
  const transform = lf.graphModel.transformModel
  const model =
    kind === 'edge' ? lf.getEdgeModelById(id) : lf.getNodeModelById(id)
  if (!model) return
  // 连线没有宽高，给一个够用的框；节点与表头按自身尺寸定位
  const width = 'width' in model ? (model.width as number) : 180
  const height = 'height' in model ? (model.height as number) : 32
  const [left, top] = transform.CanvasPointToHtmlPoint([
    model.x - width / 2,
    model.y - height / 2,
  ])
  const [right, bottom] = transform.CanvasPointToHtmlPoint([
    model.x + width / 2,
    model.y + height / 2,
  ])
  const value =
    kind === 'node'
      ? (doc.value.nodes.find((node) => node.id === id)?.text ?? '')
      : kind === 'edge'
        ? (doc.value.edges.find((edge) => edge.id === id)?.label ?? '')
        : (doc.value.lanes.find((lane) => lane.id === id)?.name ?? '')
  editor.value = { kind, id, left, top, width: right - left, height: bottom - top, value }
}

function commitEditor() {
  const current = editor.value
  if (!current) return
  editor.value = null
  try {
    if (current.kind === 'node') commit(setNodeText(doc.value, current.id, current.value))
    else if (current.kind === 'edge') commit(setEdgeLabel(doc.value, current.id, current.value))
    else commit(setLaneName(doc.value, current.id, current.value))
  } catch (error) {
    report(error)
  }
}

// ---------------------------------------------------------- LogicFlow 事件

function onKeydown(event: KeyboardEvent) {
  const isUndo = (event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'z' && !event.shiftKey
  const isRedo = (event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'z' && event.shiftKey
  if (isUndo) {
    event.preventDefault()
    undo()
  } else if (isRedo) {
    event.preventDefault()
    redo()
  }
}

function handleResize() {
  lf?.resize()
}

onMounted(() => {
  lf = new LogicFlow({
    container: canvas.value!,
    grid: false,
    isSilentMode: false,
    // 默认新连线就用我们注册的类型，否则会退回内置 polyline
    edgeType: 'flow-edge',
    // 文字编辑走我们自己的覆盖层（自定义节点自己画多行正文，与内置 BaseText 不能共存）
    textEdit: false,
    adjustEdge: false,
    // 这两个是纯配置就能白拿的能力
    snapline: true,
    multipleSelectKey: 'shift',
  })

  registerEditorNodes(lf)
  lf.render(buildRenderData(doc.value))
  frameGraph()

  lf.on('node:drop', onNodeDrop)
  // 注意事件名是 dbclick，不是 dblclick
  lf.on('node:dbclick', ({ data }) => {
    const model = lf?.getNodeModelById(data.id)
    if (!model) return
    // 表头是背景节点，双击它改的是泳道名
    if (model.type === 'lane-header') openEditorFor('lane', model.properties.laneId as string)
    else if (model.type === NODE_TYPE) openEditorFor('node', data.id)
  })
  lf.on('edge:dbclick', ({ data }) => openEditorFor('edge', data.id))
  lf.on('node:delete', ({ data }) => {
    if (doc.value.nodes.some((node) => node.id === data.id)) commit(removeNodes(doc.value, [data.id]))
  })
  lf.on('edge:delete', ({ data }) => {
    if (!doc.value.edges.some((edge) => edge.id === data.id)) return
    commit({ ...doc.value, edges: doc.value.edges.filter((edge) => edge.id !== data.id) })
  })
  lf.on('edge:add', ({ data }) => {
    // 从锚点拖出的新连线：把它记进文档（重复建同一条连线时 addEdge 的 id 会撞，后端过滤掉）
    if (doc.value.edges.some((edge) => edge.id === data.id)) return
    try {
      commit(addEdge(doc.value, data.sourceNodeId, data.targetNodeId))
    } catch (error) {
      report(error)
      rerender()
    }
  })

  window.addEventListener('resize', handleResize)
  window.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  window.removeEventListener('keydown', onKeydown)
  lf?.destroy()
})
</script>

<template>
  <div class="flow-root">
    <aside
      class="palette"
      draggable="true"
      @dragstart="onPaletteDragStart"
      title="拖到画布上创建节点"
    >
      流程节点
    </aside>

    <div ref="canvas" class="flow-canvas" @dragover.prevent @drop="onCanvasDrop"></div>

    <p v-if="message" class="message" @click="message = ''">{{ message }}（点击关闭）</p>

    <textarea
      v-if="editor"
      class="inline-editor"
      :style="{
        left: `${editor.left}px`,
        top: `${editor.top}px`,
        width: `${editor.width}px`,
        height: `${editor.height}px`,
      }"
      :value="editor.value"
      @input="editor.value = ($event.target as HTMLTextAreaElement).value"
      @blur="commitEditor"
      @keydown.enter.exact.prevent="commitEditor"
      @keydown.esc="editor = null"
      autofocus
    ></textarea>
  </div>
</template>

<style scoped>
.flow-root {
  position: relative;
  width: 100%;
  height: 100%;
}

.flow-canvas {
  width: 100%;
  height: 100%;
  background: #ffffff;
}

.palette {
  position: fixed;
  top: 20px;
  left: 200px;
  z-index: 999;
  padding: 8px 16px;
  font-size: 13px;
  color: #1f3864;
  background: #f2f7ff;
  border: 1px solid #5b9bd5;
  border-radius: 6px;
  cursor: grab;
  user-select: none;
}

.message {
  position: fixed;
  bottom: 20px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 1000;
  margin: 0;
  padding: 8px 16px;
  font-size: 13px;
  color: #b91c1c;
  background: #fdf1f1;
  border: 1px solid #d98a8a;
  border-radius: 6px;
  cursor: pointer;
}

.inline-editor {
  position: absolute;
  z-index: 1000;
  padding: 4px 6px;
  font-family: inherit;
  font-size: 12px;
  line-height: 18px;
  text-align: center;
  color: #1f3864;
  background: #ffffff;
  border: 2px solid #1677ff;
  border-radius: 4px;
  resize: none;
  outline: none;
}
</style>
```

`nodeGeom` 在本文件里没有用到（每次编辑都是整图重渲染，不需要手动算单个节点的几何）——**从 import 里去掉**，只保留 `docSize`（`noUnusedLocals` 会让构建失败）。

- [ ] **Step 4: 类型门禁 + 实机确认**

Run: `pnpm build && pnpm dev`
Expected（人工核对）：
1. 从左栏把「流程节点」拖到某个空格子 → 出现新节点，编号接着最大号（A22）。
2. 拖动节点到别的格子 → 停手后**吸附到格子**；跨泳道拖 → 位置与配色都按新泳道变。
3. 节点拖到画布外（步骤号列或表头）→ 弹回原处，不变形。
4. 双击节点改文字 → 覆盖层出现并定位在节点上；回车或失焦提交后高度按新行数变化。
5. 双击连线改标签；双击泳道表头改泳道名。
6. 选中节点按 Delete 删除；挂在它上面的连线一起消失。
7. 从节点锚点拖到另一个节点 → 生成新连线。
8. Ctrl+Z 撤销 / Ctrl+Shift+Z 重做。
9. 刷新浏览器，改动还在。
10. 编辑时视口（缩放与平移）**不跳**。

- [ ] **Step 5: Commit**

```bash
git add src/editor/ops.ts src/editor/ops.spec.ts src/views/FlowEditor.vue
git commit -m "feat(editor): 接上拖拽建/移节点、就地编辑、连线与删除，含文档级撤销"
```

---

### Task 14: 工具栏（泳道增删 · 步骤 ± · JSON 存取 · PNG 导出）

**Files:**
- Modify: `src/views/FlowEditor.vue`

**Interfaces:**
- Consumes: `ops.addLane` / `ops.removeLane` / `ops.setStepCount`（Task 11）、`storage.serializeDoc` / `parseDoc` / `clearDoc`（Task 12）
- Produces: 无新接口（只接线）

- [ ] **Step 1: 在 `<script setup>` 末尾追加工具栏逻辑**

在 `FlowEditor.vue` 的 `onMounted` 之前追加：

```ts
// ---------------------------------------------------------- 工具栏

function onAddLane() {
  commit(addLane(doc.value))
}

function onRemoveLastLane() {
  const last = doc.value.lanes[doc.value.lanes.length - 1]
  if (!last) return
  const result = removeLane(doc.value, last.id)
  if (!result.ok) {
    report(new Error(result.error))
    return
  }
  commit(result.doc)
}

function onStepDelta(delta: number) {
  try {
    commit(setStepCount(doc.value, doc.value.stepCount + delta))
  } catch (error) {
    // 格子不够时 setStepCount 会拒绝，这里是它唯一的出口
    report(error)
  }
}

function onExportJson() {
  const blob = new Blob([serializeDoc(doc.value)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `${doc.value.name || '流程图'}.json`
  anchor.click()
  URL.revokeObjectURL(url)
}

async function onImportJson(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = '' // 允许重复导入同一个文件
  if (!file) return
  const result = parseDoc(await file.text())
  if (!result.ok) {
    report(new Error(result.errors.join('；')))
    return
  }
  commit(result.doc)
}

function onReset() {
  clearDoc()
  past.value = []
  future.value = []
  doc.value = harvestInboundDoc
  rerender()
}

// ---------------------------------------------------------- PNG 导出

/**
 * getSnapshot 由 Snapshot 插件在运行时挂到实例上，不在核心的 LogicFlow 类型里，
 * 所以这里要交出一个交叉类型 —— 与导图视图里同样的处理。
 */
interface SnapshotOptions {
  fileType?: string
  backgroundColor?: string
  padding?: number
  /** false 导出全部元素；true 只导出视口内的（这张图比窗口高，必须 false） */
  partial?: boolean
}

type ExportableLogicFlow = LogicFlow & {
  getSnapshot(fileName?: string, options?: SnapshotOptions): Promise<void>
}

async function onExportPng() {
  if (!lf) return
  await (lf as ExportableLogicFlow).getSnapshot(doc.value.name || '流程图', {
    fileType: 'png',
    backgroundColor: '#ffffff',
    padding: 40,
    partial: false,
  })
}
```

同时把这些加进顶部的 import：

```ts
import { addLane, removeLane, setStepCount } from '../editor/ops'
import { clearDoc, parseDoc, serializeDoc } from '../editor/storage'
import { harvestInboundDoc } from '../editor/seed/harvestInbound'
import Snapshot from '@logicflow/extension/es/tools/snapshot'
```

并把 `plugins: [Snapshot]` 加进 `new LogicFlow({...})` 的配置里。

- [ ] **Step 2: 把 `<template>` 换成带工具栏的版本**

```vue
<template>
  <div class="flow-root">
    <div class="toolbar">
      <button type="button" @click="onAddLane">添加泳道</button>
      <button type="button" @click="onRemoveLastLane">删除末条泳道</button>
      <button type="button" @click="onStepDelta(1)">步骤 +</button>
      <button type="button" @click="onStepDelta(-1)">步骤 −</button>
      <span class="divider"></span>
      <button type="button" @click="onExportJson">导出 JSON</button>
      <label class="file-button">
        导入 JSON
        <input type="file" accept="application/json" @change="onImportJson" />
      </label>
      <button type="button" @click="onExportPng">导出 PNG</button>
      <span class="divider"></span>
      <button type="button" @click="onReset">重置为示例</button>
    </div>

    <aside
      class="palette"
      draggable="true"
      @dragstart="onPaletteDragStart"
      title="拖到画布上创建节点"
    >
      流程节点
    </aside>

    <div ref="canvas" class="flow-canvas" @dragover.prevent @drop="onCanvasDrop"></div>

    <p v-if="message" class="message" @click="message = ''">{{ message }}（点击关闭）</p>

    <textarea
      v-if="editor"
      class="inline-editor"
      :style="{
        left: `${editor.left}px`,
        top: `${editor.top}px`,
        width: `${editor.width}px`,
        height: `${editor.height}px`,
      }"
      :value="editor.value"
      @input="editor.value = ($event.target as HTMLTextAreaElement).value"
      @blur="commitEditor"
      @keydown.enter.exact.prevent="commitEditor"
      @keydown.esc="editor = null"
      autofocus
    ></textarea>
  </div>
</template>
```

- [ ] **Step 3: 追加工具栏样式**

在 `<style scoped>` 里追加（`palette` 的 `left` 改成 `200px` 之外的值以免与模式切换按钮重叠，见下）：

```css
.toolbar {
  position: fixed;
  top: 20px;
  right: 20px;
  z-index: 999;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px;
  background: #ffffff;
  border: 1px solid #e4e9f0;
  border-radius: 6px;
}

.toolbar button,
.file-button {
  padding: 6px 12px;
  font-size: 13px;
  color: #4b5563;
  background: transparent;
  border: none;
  border-radius: 4px;
  cursor: pointer;
}

.toolbar button:hover,
.file-button:hover {
  background: #f2f7ff;
}

.file-button input {
  display: none;
}

.divider {
  width: 1px;
  height: 20px;
  background: #e4e9f0;
}

.palette {
  position: fixed;
  top: 68px;
  left: 20px;
  z-index: 999;
  padding: 8px 16px;
  font-size: 13px;
  color: #1f3864;
  background: #f2f7ff;
  border: 1px solid #5b9bd5;
  border-radius: 6px;
  cursor: grab;
  user-select: none;
}
```

（`App.vue` 的模式切换按钮在 `top: 20px; left: 20px`，所以素材条目挪到 `top: 68px` 避让。）

- [ ] **Step 4: 类型门禁 + 实机确认**

Run: `pnpm build && pnpm dev`
Expected：
1. 「添加泳道」→ 右侧多一条列，**已有节点整体不动**（列变宽了，但各自仍在自己泳道里）。
2. 「删除末条泳道」→ 空泳道被删；往有节点的泳道删时**弹出可读提示**且图不变。
3. 「步骤 −」→ 末行节点上移，**节点总数不变**。
4. 「导出 JSON」→ 下载一个 `.json`；「导入 JSON」→ 选文件后图变成文件里的样子。
5. 「导出 PNG」→ 下载一张完整图（**不是只截视口**）。
6. 「重置为示例」→ 回到参考图，刷新后仍是示例。
7. 泳道/步骤操作后视口不跳。

- [ ] **Step 5: Commit**

```bash
git add src/views/FlowEditor.vue
git commit -m "feat(editor): 加泳道增删、步骤增减、JSON 存取与 PNG 导出工具栏"
```

---

### Task 15: 使用说明 + 更新 CLAUDE.md

**Files:**
- Create: `docs/流程图编辑器使用说明.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: 写使用说明**

创建 `docs/流程图编辑器使用说明.md`，内容需覆盖（每条都对应 Task 13/14 里已实现的真实行为，不要写没实现的功能）：

1. **打开**：`pnpm install && pnpm dev`，左上角在「思维导图」与「流程图」之间切换；流程图模式默认载入示例图。
2. **建节点**：把左下角「流程节点」拖到某个格子上放手。落在空格子就放那儿；该格已有人就自动往下找最近的空格子（往下到底再往上找）。
3. **移动 / 换泳道**：直接拖节点。松手后自动吸附到格子；**横向拖过泳道线就改了它的归属泳道**，颜色跟着变。
4. **改文字**：双击节点，弹出的输入框里改，回车或点别处提交；Esc 取消。换行会让节点自动变高。
5. **连线**：把鼠标移到节点上，从它的锚点拖到目标节点即可建线（默认棕色实线）。
6. **改连线标签**：双击连线。
7. **改泳道名**：双击泳道表头。
8. **删除**：选中后按 Delete。删节点会连带删掉挂在它上面的连线。
9. **撤销 / 重做**：Ctrl+Z / Ctrl+Shift+Z。
10. **多选**：按住 Shift 点选。
11. **泳道与步骤**：右上角工具栏「添加泳道 / 删除末条泳道 / 步骤 + / 步骤 −」。**泳道里还有节点时不许删**（会提示先把节点移走）；**减少步骤不会删掉节点**，超出范围的节点会自动上移。
12. **保存**：编辑自动存到浏览器本地，刷新不丢。「导出 JSON」存成文件，「导入 JSON」从文件恢复，「重置为示例」回到初始图。
13. **导出图片**：右上角「导出 PNG」，导出的是**整张图**（不是当前视口）。
14. **说明边界**：数据只存在这台浏览器的 localStorage 里，清浏览器数据会丢；换机器请用 JSON 导出/导入。连线走线是自动的，不能手动拖拐点。泳道宽度与配色当前改不了（要改得直接编辑导出的 JSON 再导入）。

- [ ] **Step 2: 更新 `CLAUDE.md`**

在 `CLAUDE.md` 里补一节，并把「验证方式」那句改掉。需要写进去的内容：

```markdown
## 流程图编辑器（第二个模式）

应用有两个模式，顶部切换：**思维导图**（原有，只读渲染 `src/data/wms.ts`）与**流程图编辑器**（新增，`src/views/FlowEditor.vue`）。两者互不依赖。

### 核心机制：网格锚定

`src/editor/` 下是一份**不存坐标**的文档模型（`FlowDoc`，见 `types.ts`）：节点只记 `{laneId, step}`。像素位置全部由三条规则推导（`geometry.ts`）：

- 节点宽 = 泳道宽 − 两侧内边距，横向居中于泳道 → 同一列节点宽度天然相等；
- 节点纵向中心 = 行带中线 → 同一行节点对齐；
- 节点高度 = 文本按上述宽度折行后的高度 → 行数多的节点自然更高（`text.ts` 是折行的**单一事实源**，算高度与画图共用它）。

因此**增删泳道/步骤会自动重排**，不存在「重排后节点错位」。要改动图，改的是文档数据，不是坐标。

### 文件职责

- `types.ts` / `theme.ts` —— 文档类型与配色（配色只在这里改）。
- `text.ts` / `geometry.ts` —— 折行与几何推导，纯函数。
- `doc.ts` —— `validateDoc()`，导入与加载的门卫，一次报出所有错误。
- `nodes.ts` / `edges.ts` —— 自定义 LogicFlow 元素（**三 node 一文件、edge 自带可测的肘形路由**）。
- `render.ts` —— `buildRenderData()`：文档 → 渲染配置。**纯函数**，是几何/配色/背景压制的测试着力点；`isBackgroundType()` 是序列化白名单的依据。
- `ops.ts` —— **所有编辑操作都是「文档进、文档出」的纯函数**，UI 只把事件翻译成这些调用。这样守恒规则可以单测。
- `storage.ts` —— 单文档 localStorage + JSON 序列化/校验。storage 作为参数传入，便于测试替身。
- `views/FlowEditor.vue` —— 画布 + 素材栏 + 工具栏 + 就地编辑覆盖层。**文档是唯一事实源，不是 LogicFlow 的画布模型**：每次编辑都是「算新文档 → 重渲染 → 恢复视口」，所以不会出现画布与文档不一致。

### LogicFlow 使用注意（编辑器相关）

- 事件名是 **`dbclick`**（不是 `dblclick`），拖拽结束是 **`node:drop`**（不是 `node:dragstop`）。
- 背景（泳道表头 / 行带）也是 LogicFlow 节点，靠负 `zIndex` 压底、靠 `draggable: false` / `isHittable: false` 关掉交互。表头**保留点击**（双击改名），行带**彻底穿透**。背景不会进文档，因为它是 `buildRenderData()` 渲染时才生成的。
- 双击编辑**不用** LogicFlow 内置 `editText`：自定义节点自己画多行正文（`getText()` 返回 null），与内置 `BaseText` 不能共存。改用 HTML `<textarea>` 覆盖层，`transformModel.CanvasPointToHtmlPoint` 负责定位。
- **不要打开 LogicFlow 的 `history`**：它的撤销操作的是画布模型，会与文档脱节。撤销栈是文档级的，写在 `FlowEditor.vue` 里。
- 新连线要设 `edgeType: 'flow-edge'`，否则会退回内置 `polyline`。

### 验证方式（更新）

`pnpm build`（`vue-tsc -b` 类型门禁）与 **`pnpm test`（Vitest，单元测试）**都要通过。测试覆盖纯逻辑：折行边界、几何推导与自动重排、文档校验、格子吸附与守恒规则（越界上移不丢节点、占用顺移）、示例图完整性。视图层（preact 渲染）没有单测，靠类型门禁与实机核对——这是刻意选择，不是遗漏。
```

同时把原 CLAUDE.md 里那句「There is no test runner, linter, or formatter configured. `pnpm build` is the only verification step available」改成上面「验证方式（更新）」的内容。

- [ ] **Step 3: Commit**

```bash
git add docs/流程图编辑器使用说明.md CLAUDE.md
git commit -m "docs: 加流程图编辑器使用说明并更新 CLAUDE.md"
```

---

### Task 16: 阶段 B 验收

**Files:** 无

- [ ] **Step 1: 自动化验证**

```bash
pnpm test && pnpm build
```
Expected: 全绿 + 构建通过。

- [ ] **Step 2: 走一遍使用说明**

照 `docs/流程图编辑器使用说明.md` 从第 2 条到第 13 条逐条做一遍，尤其确认这几条容易坏的：

- 拖到已占用的格子 → 顺延而不是覆盖或消失；
- 跨泳道拖 → 配色跟着变；
- 「步骤 −」到很小 → **节点一个都没少**；
- 往有节点的泳道删 → 拒绝并提示；
- 导出 PNG → 是整张图；
- 刷新 → 改动还在；
- Ctrl+Z → 回到上一步且视口不跳。

- [ ] **Step 3: 交用户验收**

把上面的结果报给用户。任何一条不通过都先修再谈完成。

---

## 计划自审记录

- **Spec 覆盖**：§1 验收标准 1–5 → Task 1–14 全覆盖，第 5 条由每个任务的测试 + Task 16 覆盖；§4 数据模型 → Task 1（`waypoints` 已按最小化移除）；§5 几何 → Task 2/3；§5.1 守恒规则 → Task 11（`setStepCount` 上移、`removeLane` 拒绝）+ 测试；§6 渲染层 → Task 6/7/8，背景定案规则 → Task 13 Step 4 第 10 条；§7 UI 与交互 → Task 13/14（交互清单逐条对应到实现与实机核对项）；§8 存储 → Task 12；§9 示例图 → Task 5；§10 验证 → Task 1 起各任务测试 + Task 16；§11 两阶段 → 阶段 A = Task 1–10、阶段 B = Task 11–16；§12 风险 → Task 10 Step 3 核对清单；§13 文件清单 → 逐文件对应。**无遗漏。**
- **占位符**：无 TBD/TODO；每个代码步骤都给了可写入的完整代码；没有「与 Task N 类似」。
- **类型一致性**：`NodeGeom`（中心点）与 `Bounds`（左上角）分开命名；`textMaxWidth` 与 `nodeGeom` 内部统一用 `TEXT_PAD_X * 2`；`EdgeTheme` 只从 `theme.ts` 导入以免循环引用；`routeBetween` 只接两点（最小化去掉了 `waypoints`）；`Cell` 类型在 ops 与 render 之间一致。
- **Review Focus 落点**：① 折行边界 → Task 2；② 未知 `laneId` 抛错 → Task 3；③ 极端泳道宽度 → Task 4；④ 越界与占用 → Task 4 + Task 11；⑤ 连线两端问题 → Task 4 + Task 13 的 `addEdge` 测试。
- **本计划对 spec 的两处修正**（都是想清楚之后发现的，不是随手改的）：
  1. **撤销/重做不是「免费配置项」**。spec §7 原写「由 `history` 配置项提供」，但因为文档是唯一事实源，LogicFlow 内置撤销操作的是画布模型，一旦启用就会与文档脱节（撤销后画布变了、文档没变）。故改为**文档级撤销栈**（约 25 行，写在 `FlowEditor.vue`），并明确**不打开** `history`。
  2. **泳道改名改由双击表头触发**，比 spec §7 里「顶栏按钮 + 属性面板」更省（属性面板已砍）。
- **刻意不写测试的地方**：Task 6/9/13/14 的 preact 视图与 Vue 组件无单测，由类型门禁 + 实机核对清单覆盖；已在任务里写明是选择而非遗漏。