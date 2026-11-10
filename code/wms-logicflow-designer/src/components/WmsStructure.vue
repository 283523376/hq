<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

import LogicFlow, { PolylineEdge } from '@logicflow/core'
// Deep import on purpose: @logicflow/extension declares no `sideEffects`, so
// importing from its barrel keeps every extension (bpmn, menus, minimap, …) in
// the bundle and roughly doubles it.
import Snapshot from '@logicflow/extension/es/tools/snapshot'
import '@logicflow/core/lib/style/index.css'

import { MindMapEdgeModel } from '../flow/MindMapEdge'
import { buildMindMap } from '../layout/mindmap'
import { wmsData } from '../data/wms'

/** The hierarchy is static, so build the graph once at setup time. */
const graph = buildMindMap(wmsData)

const EXPORT_FILE_NAME = '永鑫WMS系统功能结构'

interface SnapshotOptions {
  fileType?: string
  backgroundColor?: string
  padding?: number
  /** false exports every element, true only what is inside the current viewport. */
  partial?: boolean
}

/**
 * `getSnapshot` is attached to the LogicFlow instance at runtime by the Snapshot
 * plugin, so it is not part of the core `LogicFlow` type.
 */
type ExportableLogicFlow = LogicFlow & {
  getSnapshot(fileName?: string, options?: SnapshotOptions): Promise<void>
}

const canvas = ref<HTMLDivElement>()
const exporting = ref(false)

let lf: ExportableLogicFlow | undefined

/**
 * The map is a tall poster (roughly 1:2). Fitting both axes would shrink the
 * labels to nothing, so fit the whole thing only when it stays legible, and
 * otherwise fit the width and let the user scroll.
 */
function frameGraph() {
  if (!lf || !canvas.value) return
  const { clientWidth, clientHeight } = canvas.value
  const fitAll = Math.min(clientWidth / (graph.width + 48), clientHeight / (graph.height + 48))
  const fitWidth = Math.min(clientWidth / (graph.width + 48), 1.4)
  lf.zoom(fitAll >= 0.72 ? fitAll : fitWidth)
  lf.translateCenter()
}

/** Keep LogicFlow's internal canvas size in step with the element; do not re-frame,
 *  that would throw away the user's own zoom and pan. */
function handleResize() {
  lf?.resize()
}

/**
 * Rasterises the whole map to a PNG and downloads it.
 *
 * `partial: false` matters here: the map is taller than the window, and the
 * default (false) exports every element rather than only the visible ones. The
 * Snapshot plugin also copies the page's stylesheets into the cloned SVG, which
 * is what keeps the CJK font stack and the per-node text sizes intact.
 */
async function exportImage() {
  if (!lf || exporting.value) return
  exporting.value = true
  try {
    await lf.getSnapshot(EXPORT_FILE_NAME, {
      fileType: 'png',
      // Default is transparent, which reads as an empty file in dark-mode viewers.
      backgroundColor: '#ffffff',
      padding: 40,
      partial: false,
    })
  } finally {
    exporting.value = false
  }
}

onMounted(() => {
  lf = new LogicFlow({
    container: canvas.value!,
    // No grid: the map is a printed-chart style drawing, not a freeform canvas.
    grid: false,
    isSilentMode: false,
    // Node labels are read-only structure; edge points would fight the bracket routing.
    textEdit: false,
    adjustEdge: false,
    plugins: [Snapshot],
  }) as ExportableLogicFlow

  lf.register({
    type: 'mindmap-edge',
    view: PolylineEdge,
    model: MindMapEdgeModel,
  })

  // Fallbacks only — every node carries its own style from the layout.
  lf.setTheme({
    rect: { radius: 6, stroke: '#d4d4d4', strokeWidth: 1, fill: '#ffffff' },
    nodeText: { fontSize: 13, color: '#444444' },
    polyline: { stroke: '#c9c9c9', strokeWidth: 1 },
  })

  lf.render(graph)
  frameGraph()

  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  lf?.destroy()
})
</script>

<template>
  <div ref="canvas" class="mindmap-canvas"></div>

  <button type="button" class="export-btn" :disabled="exporting" @click="exportImage">
    {{ exporting ? '导出中…' : '导出图片' }}
  </button>
</template>

<style scoped>
.mindmap-canvas {
  width: 100%;
  height: 100%;
  background: #ffffff;
}

.export-btn {
  position: fixed;
  top: 20px;
  right: 20px;
  z-index: 999;
  padding: 8px 15px;
  background: #1677ff;
  color: #ffffff;
  border: none;
  border-radius: 5px;
  cursor: pointer;
  font-size: 14px;
}

.export-btn:disabled {
  background: #8ab8ff;
  cursor: default;
}
</style>