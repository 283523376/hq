# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

A single-page Vue 3 app that renders the 永鑫 WMS (warehouse management system) functional breakdown as a layered, left-to-right mind map. The graph is not authored by hand in LogicFlow terms — the hierarchy in `src/data/wms.ts` is auto-laid-out into a printable org-chart style drawing, and LogicFlow acts as the renderer plus pan/zoom/drag surface.

It has **two modes**, switched from the buttons at the top of the page: 思维导图 (the read-only mind map described here) and 流程图 (a swimlane flowchart editor). The two share no code — the editor has its own document model and lives in `src/editor/`, described in its own section below.

## Commands

Package manager is **pnpm** (`pnpm-lock.yaml`, `pnpm-workspace.yaml`).

```bash
pnpm install
pnpm dev       # Vite dev server with HMR
pnpm build     # vue-tsc -b && vite build  (type-check runs first, and gates the build)
pnpm preview   # serve the production build
pnpm test      # vitest run — unit tests for the flowchart editor's pure logic
pnpm test:watch
```

**Verification is two commands, and they catch different things.** `pnpm test` covers the flowchart editor's pure logic (text wrapping, grid geometry, document validation, cell snapping and the conservation rules, seed integrity, render-data shape). `pnpm build` is the type gate: `vue-tsc -b` type-checks `src/**` including the `*.spec.ts` files. Vitest strips types with esbuild without checking them, so a green `pnpm test` says nothing about types — run both.

Neither can see anything visual. Layout, connector, and export changes are only observable by running `pnpm dev` and looking at the canvas (or exporting a PNG). Some of what used to need eyeballing is now machine-checked instead — see the layout-sanity tests in `src/editor/render.spec.ts` (no two node rects intersect, every node stays inside its lane, no node is taller than its row band).

There is still no linter or formatter configured.

This directory *is* now a git repository (it was not until the flowchart editor work began): history starts at the commit `chore: 记录流程图编辑器开发前的基线`.

## Architecture

Three layers, each with one job:

1. **`src/data/wms.ts` — content only.** `WmsNode = { text, children? }`. Node ids are deliberately *not* authored here: labels repeat in the real hierarchy (「采购入库」 sits under both 入库管理 and 成品入库), so hand-written ids collide. The layout derives ids from the hierarchy path (`wms-0-2-1`) instead. To change the diagram, edit this file only.

2. **`src/layout/mindmap.ts` — geometry.** `buildMindMap()` walks the tree three times and returns plain LogicFlow node/edge configs plus the drawing's bounding size. The rules that produce the org-chart look, all tunable at the top of the file:
   - Every node at the same depth shares one column. `colX[depth+1] = colX[depth] + widestNodeAt(depth) + COLUMN_GAP`, so a column starts past the widest box of the previous one.
   - Leaves take successive rows on a fixed `ROW_HEIGHT` pitch; a parent's `y` is the midpoint of its first and last child. Nothing is centred *over* its children on x, which is what keeps the columns straight.
   - Node boxes are sized from an estimated text width (`measureText`, ~1em per CJK glyph, ~0.55em per Latin), so each box hugs its label. Sizing is per depth: root is a black box with white bold text, deeper levels get progressively smaller fonts. Fonts and padding come from `styleAt(depth)` — four tiers, where everything at depth ≥ 3 shares `LEAF_STYLE`. A new visual depth means adding a tier there, not just writing deeper nodes in `wms.ts`.
   - Anchors are addressed explicitly (`<nodeId>_1` = right, `<nodeId>_3` = left) so links always leave the parent's right edge into the child's left edge.

3. **`src/components/WmsStructure.vue` — wiring.** Builds the graph once at setup time, registers the custom edge type and the `Snapshot` plugin, sets fallback theme, renders, frames the initial view, and owns the 导出图片 button. The graph is rendered exactly once with no reactive re-render path — an edit to `wms.ts` reaches the canvas only because the dev server reloads the module, so nothing here re-lays-out on data change. `'mindmap-edge'` is the coupling string: `lf.register({ type: 'mindmap-edge' })` here must match `EDGE_TYPE` in the layout that emits it.

### Bracket connectors

`src/flow/MindMapEdge.ts` holds `MindMapEdgeModel`, which overrides `updatePoints()` to draw the **bracket connector** instead of LogicFlow's orthogonal detour: parent right edge → out to the trunk line → vertically to the child's row → into the child's left edge. Because the trunk x is measured *back from the child column*, all sibling edges draw their vertical segment at the same x and the group reads as one bracket. Corner rounding is not drawn here — LogicFlow's `PolylineEdge` view rounds polyline corners from `properties.radius`, which the layout sets.

`simplify()` in that file drops duplicate and collinear points before they reach the rounded-corner renderer. That is load-bearing for a single-child parent, which sits on the exact row of its child and would otherwise hand the renderer a zero-length segment.

### Image export

The 导出图片 button calls `lf.getSnapshot(name, options)`. Two things are easy to get wrong:

- `getSnapshot` is attached to the LogicFlow *instance* at runtime by the `Snapshot` plugin, so it is missing from the core `LogicFlow` type. Hence the `ExportableLogicFlow` intersection type in the component.
- `partial: false` is required, not incidental. The default exports every element; `partial: true` would export only what currently sits inside the viewport, which for this taller-than-window map means a cropped picture.

The plugin copies the page's stylesheets into a clone of the canvas SVG before rasterising (`getClassRules`, `useGlobalRules = true`). That is what preserves the CJK `font-family` from `src/style.css` and the per-node font sizes — if the exported text ever comes out in the wrong font, check that this stylesheet is still loaded rather than looking at the SVG.

The plugin's real options type, `ToImageOptions`, is exported from the same deep path (`@logicflow/extension/es/tools/snapshot`) and already covers `fileType` / `backgroundColor` / `padding` / `partial`. The component hand-rolls an equivalent `SnapshotOptions` interface; prefer the exported type when touching this.

## 流程图编辑器（第二个模式）

`src/views/FlowEditor.vue` + `src/editor/`。用户视角的用法在 `docs/流程图编辑器使用说明.md`，设计取舍在 `docs/superpowers/specs/2026-10-12-flow-editor-design.md`；本节写的是**为什么这么组织**。

### 核心机制：网格锚定

`src/editor/types.ts` 的 `FlowDoc` 是唯一事实源，而且**不存坐标**：节点只记 `{laneId, step}`。像素位置全部由 `src/editor/geometry.ts` 的三条规则推导：

- 节点宽 = 泳道宽 − 两侧内边距（或节点自己声明的 `width`），横向居中于泳道 → 同一列节点宽度天然相等；
- 节点纵向中心 = 行带中线 → 同一行节点对齐；
- 节点高度 = 文本按上述宽度折行后的高度 → 行数多的节点自然更高。

`src/editor/text.ts` 是折行的**单一事实源**：算高度与画图共用它，所以不会出现「量高用一套、渲染用另一套」的错位。

于是**增删泳道/步骤会自动重排**，不存在「重排后节点错位」。要改图，改的是文档数据，不是坐标。

**同一格可以放多个节点**（参考图的 A13/A14 是并排的），靠节点可选的 `width` + `offsetX` 表达。`validateDoc` 因此不禁止同格，只禁止**横向重叠**——重叠才是画错。

### 文件职责

- `types.ts` / `theme.ts` —— 文档类型与配色（改配色只动 `theme.ts`）。
- `text.ts` / `geometry.ts` —— 折行与几何推导，纯函数。
- `doc.ts` —— `validateDoc()`，导入与加载的门卫，**一次报出所有错误**（改 JSON 的人希望一次看全）。
- `nodes.ts` / `edges.ts` —— 自定义 LogicFlow 元素；`edges.ts` 自带可单测的肘形路由。
- `render.ts` —— `buildRenderData()`：文档 → 渲染配置。纯函数，是几何/配色/背景压制的测试着力点；`isBackgroundType()` 是背景白名单的依据。
- `ops.ts` —— **所有编辑操作都是「文档进、文档出」的纯函数**，UI 只把事件翻译成这些调用，所以守恒规则可以单测。
- `storage.ts` —— 单文档 localStorage + JSON 序列化/校验。`storage` 作为**参数**传入（默认才是 `window.localStorage`），便于测试替身。
- `seed/harvestInbound.ts` —— 示例图（参考图转录）。节点表来自设计文档 §9；连线表是读图结果，有歧义的在注释里标了 `⚠待核对`。

### 画布与文档的关系（最容易搞错的一点）

**文档是唯一事实源，LogicFlow 的画布模型不是。** 每次编辑都是「算新文档 → 重渲染 → 恢复视口」，而不是逐个去改画布上的模型。一条路径，永远一致，不会出现「画布与文档不一致」的补丁式 bug。

由此推出两条硬约束：

- **不要打开 LogicFlow 的 `history`**。它的撤销操作的是画布模型，启用后会与文档脱节（撤销后画布变了、文档没变）。撤销栈是**文档级**的，写在 `FlowEditor.vue` 里。
- **不要用内置的 `editText`**。自定义节点自己画多行正文（`getText()` 返回 null），与内置 `BaseText` 不能共存。编辑走一个定位在节点上的 HTML `<textarea>` 覆盖层，用 `transformModel.CanvasPointToHtmlPoint` 定位。

### LogicFlow 使用注意（编辑器踩过的坑）

- 事件名是 **`dbclick`**（不是 `dblclick`），拖拽结束是 **`node:drop`**（不是 `node:dragstart` / `dragstop`）。
- 基类把 `BaseModel.type` 声明成字面量 `''`，与 `'lane-header'` 比较会报 TS2367 → 先收宽成 `string` 再比。`BaseEdgeModel` 的 `x` / `y` 是 `unknown` → 用类型正确的 `pointsList` 算中点。
- 背景（泳道表头 / 行带）也是 LogicFlow 节点，靠负 `zIndex` 压底、靠 `draggable` / `isHittable` 关掉交互。表头**保留点击**（双击改名），行带**彻底穿透**。背景不会进文档，因为它是 `buildRenderData()` 渲染时才生成的。
- 新连线要设 `edgeType: 'flow-edge'`，否则会退回内置 `polyline`。
- `editConfig` 里白拿的能力：`snapline`（对齐吸附）、`multipleSelectKey: 'shift'`（多选）。Delete 删除与锚点拖拽建边是默认行为。
- **不要用 `@logicflow/extension` 的 `Pool`**：它是横向 BPMN 池语义（`laneConfig.titleSize: 20` 的窄标题条、`getPoolModel()`），做不出参考图这种纵向角色列 + 彩色表头。
- 接入 Snapshot 插件走深层路径后 bundle 只涨 ~2.6 kB（483.94 → 486.55 kB）；走包桶会把全部扩展打进来（~960 kB）。

## Gotchas

- **Interactivity depends on the overridden edge methods.** `PolylineEdgeModel.moveStartPoint` / `moveEndPoint` call `updatePoints()`, so a dragged node's connectors stay attached. Moving a node through `BaseNodeModel.move()` bypasses this (`GraphModel.moveNode` → `moveEdge` is the path that propagates to edges); don't "fix" a stale edge by editing points directly.
- **A node drag is a transient visual.** `isSilentMode: false` keeps nodes draggable, but a dragged position is never written back into the layout — a re-frame or reload puts the node back. `adjustEdge: false` and `textEdit: false` are deliberate, not oversights: dragging edge anchor points would fight the bracket routing in `updatePoints()`, and the labels are read-only structure.
- **The window `resize` handler calls `lf.resize()` only — do not add a re-frame there.** It intentionally leaves the user's zoom and pan alone; re-running `frameGraph()` on every resize would throw their viewport away.
- `COLUMN_GAP` in `src/layout/mindmap.ts` must stay larger than `TRUNK_GAP` in `src/flow/MindMapEdge.ts`, or the bracket trunk will be drawn on top of the parent box.
- LogicFlow's stylesheet must be imported explicitly where LogicFlow is used: `import "@logicflow/core/lib/style/index.css"` in `WmsStructure.vue`. Without it the canvas renders unstyled.
- The canvas fills the viewport; `frameGraph()` fits the whole map only when it stays legible (zoom ≥ 0.72) and otherwise fits the width, because this map is a tall poster (~1:2) and fitting both axes would shrink labels to nothing. `src/style.css` is a bare reset for that reason — do not reintroduce a `max-width`/`padding` on `#app`, which is what the Vite template originally shipped and what boxes the canvas into a narrow column.
- `tsconfig.app.json` enables `noUnusedLocals`, `noUnusedParameters`, and `erasableSyntaxOnly`. Unused imports/variables fail `pnpm build`, and TS-only runtime syntax (enums, parameter properties) is rejected.
- Per-node styling goes through the node's `properties.style` / `properties.textStyle`, which LogicFlow merges over the theme (`RectNodeModel.getNodeStyle`, `BaseNodeModel.getTextStyle`). `properties.width` / `height` / `radius` are read by `RectNodeModel.setAttributes`.
- `@logicflow/core` has build scripts blocked in `pnpm-workspace.yaml` (`allowBuilds: ... false`); it is consumed as a prebuilt package. `mobx` is only a transitive dependency and is not resolvable from this project — do not import it.
- Import extensions from their own path (`@logicflow/extension/es/tools/snapshot`), not the package barrel. `@logicflow/extension` declares no `sideEffects`, so `import { Snapshot } from '@logicflow/extension'` keeps every extension — bpmn, menus, minimap, … — and takes the bundle from ~460 kB to ~960 kB. `pnpm build` prints the size, so this one is checkable after any import change: ~462 kB (~127 kB gzip) is the healthy baseline, and ~960 kB means something pulled the barrel back in.
- `src/components/HelloWorld.vue` and `src/assets/*` are unused leftovers from the Vite template — `HelloWorld.vue` imports the three assets, but nothing in the entry chain (`main.ts` → `App.vue` → `WmsStructure.vue`) reaches any of them. They are still inside `tsconfig.app.json`'s `include`, so they are type-checked on every build. They have no styles left now that `src/style.css` is a bare reset, so deleting the lot is safe.