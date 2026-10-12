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