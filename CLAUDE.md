# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

A single-page Vue 3 app that renders the 永鑫 WMS (warehouse management system) functional breakdown as a layered, left-to-right mind map. The graph is not authored by hand in LogicFlow terms — the hierarchy in `src/data/wms.ts` is auto-laid-out into a printable org-chart style drawing, and LogicFlow acts as the renderer plus pan/zoom/drag surface.

## Commands

Package manager is **pnpm** (`pnpm-lock.yaml`, `pnpm-workspace.yaml`).

```bash
pnpm install
pnpm dev       # Vite dev server with HMR
pnpm build     # vue-tsc -b && vite build  (type-check runs first, and gates the build)
pnpm preview   # serve the production build
```

There is no test runner, linter, or formatter configured. `pnpm build` is the only verification step available — it type-checks via `vue-tsc -b` before bundling. It cannot see anything visual: layout, connector, and export changes are only observable by running `pnpm dev` and looking at the canvas (or exporting a PNG), so a green build is not evidence the drawing is correct.

This directory is not a git repository — there is no `.git`, no history, and no diff safety net for edits.

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