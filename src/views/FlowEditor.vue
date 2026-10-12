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

/** 就地编辑覆盖层。三种目标共用一套：节点文字 / 连线标签 / 泳道名 */
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

/** 重渲染并恢复视口 —— 每次编辑都走这一条路径，保证画布与文档一致 */
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

interface EventArgs {
  data: { id: string; sourceNodeId?: string; targetNodeId?: string }
}

function onNodeDrop(args: EventArgs) {
  if (!lf) return
  const model = lf.getNodeModelById(args.data.id)
  if (!model) return
  const cell = pointToCell(doc.value, { x: model.x, y: model.y })
  if (!cell) {
    rerender() // 拖到格子外：拉回原处
    return
  }
  try {
    commit(moveNode(doc.value, args.data.id, cell))
  } catch (error) {
    report(error)
    rerender()
  }
}

// ---------------------------------------------------------- 就地编辑覆盖层

function openEditorFor(kind: EditorState['kind'], id: string) {
  if (!lf) return
  const transform = lf.graphModel.transformModel
  let centerX: number
  let centerY: number
  let width: number
  let height: number

  if (kind === 'edge') {
    const edge = lf.getEdgeModelById(id)
    if (!edge) return
    // 连线的 x/y 在基类里没有强类型，但 pointsList 是 Point[]，用它算中点更稳
    const points = edge.pointsList
    if (points.length === 0) return
    const first = points[0]
    const last = points[points.length - 1]
    centerX = (first.x + last.x) / 2
    centerY = (first.y + last.y) / 2
    width = 180
    height = 32
  } else {
    const node = lf.getNodeModelById(id)
    if (!node) return
    centerX = node.x
    centerY = node.y
    width = node.width
    height = node.height
  }

  const [left, top] = transform.CanvasPointToHtmlPoint([
    centerX - width / 2,
    centerY - height / 2,
  ])
  const [right, bottom] = transform.CanvasPointToHtmlPoint([
    centerX + width / 2,
    centerY + height / 2,
  ])

  let value = ''
  if (kind === 'node') value = doc.value.nodes.find((node) => node.id === id)?.text ?? ''
  else if (kind === 'edge') value = doc.value.edges.find((edge) => edge.id === id)?.label ?? ''
  else value = doc.value.lanes.find((lane) => lane.id === id)?.name ?? ''

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

// ---------------------------------------------------------- 键盘与事件

function onKeydown(event: KeyboardEvent) {
  const withModifier = event.ctrlKey || event.metaKey
  if (!withModifier || event.key.toLowerCase() !== 'z') return
  event.preventDefault()
  if (event.shiftKey) redo()
  else undo()
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
  lf.on('node:dbclick', (args: EventArgs) => {
    const node = lf?.getNodeModelById(args.data.id)
    if (!node) return
    // 基类的 type 被声明成字面量 ''，先收宽成 string 再比较
    const nodeType: string = node.type
    // 表头是背景节点，双击它改的是泳道名
    if (nodeType === 'lane-header') openEditorFor('lane', node.properties.laneId as string)
    else if (nodeType === NODE_TYPE) openEditorFor('node', args.data.id)
  })
  lf.on('edge:dbclick', (args: EventArgs) => openEditorFor('edge', args.data.id))
  lf.on('node:delete', (args: EventArgs) => {
    if (!doc.value.nodes.some((node) => node.id === args.data.id)) return
    commit(removeNodes(doc.value, [args.data.id]))
  })
  lf.on('edge:delete', (args: EventArgs) => {
    if (!doc.value.edges.some((edge) => edge.id === args.data.id)) return
    commit({ ...doc.value, edges: doc.value.edges.filter((edge) => edge.id !== args.data.id) })
  })
  lf.on('edge:add', (args: EventArgs) => {
    const source = args.data.sourceNodeId
    const target = args.data.targetNodeId
    if (!source || !target) return
    try {
      // addEdge 对同一对节点是幂等的，重复拖出的线不会变成坏数据
      commit(addEdge(doc.value, source, target))
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
      title="拖到画布上创建节点"
      @dragstart="onPaletteDragStart"
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
      autofocus
      @input="editor.value = ($event.target as HTMLTextAreaElement).value"
      @blur="commitEditor"
      @keydown.enter.exact.prevent="commitEditor"
      @keydown.esc="editor = null"
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