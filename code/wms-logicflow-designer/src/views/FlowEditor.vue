<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

import LogicFlow from '@logicflow/core'
import '@logicflow/core/lib/style/index.css'
// 深层导入：@logicflow/extension 没声明 sideEffects，走包桶会把全部扩展打进包里
import Snapshot from '@logicflow/extension/es/tools/snapshot'

import { docSize } from '../editor/geometry'
import { NODE_TYPE, buildRenderData, registerEditorNodes } from '../editor/render'
import {
  addEdge,
  addLane,
  addNode,
  moveNode,
  pointToCell,
  removeLane,
  removeNodes,
  setEdgeLabel,
  setLaneName,
  setNodeText,
  setStepCount,
} from '../editor/ops'
import { harvestInboundDoc } from '../editor/seed/harvestInbound'
import { clearDoc, loadDoc, parseDoc, saveDoc, serializeDoc } from '../editor/storage'
import { captureViewport, restoreViewport } from '../editor/viewport'
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
  const snapshot = captureViewport(lf.graphModel.transformModel)
  lf.render(buildRenderData(doc.value))
  // lf.render 本身不重置变换，但这里仍然显式还原：代价只有两次赋值，
  // 换来的是「视口与编辑无关」这件事不依赖第三方对 render 的实现细节。
  restoreViewport(lf, snapshot)
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
  // 正在就地编辑文字时，键盘属于那个输入框：Ctrl+Z 应该撤文字，不该撤整张图
  const target = event.target as HTMLElement | null
  if (target && (target.tagName === 'TEXTAREA' || target.tagName === 'INPUT')) return

  const withModifier = event.ctrlKey || event.metaKey
  if (withModifier && event.key.toLowerCase() === 'z') {
    event.preventDefault()
    if (event.shiftKey) redo()
    else undo()
    return
  }

  // LogicFlow 内置快捷键只绑了 Backspace，没绑 Delete；这里补上。
  // 直接算新文档再 commit，而不是去操作画布模型——文档是唯一事实源。
  if (event.key !== 'Delete' || !lf) return
  const selected = lf.getSelectElements()
  const nodeIds = selected.nodes.map((node) => node.id)
  const edgeIds = new Set(selected.edges.map((edge) => edge.id))
  if (nodeIds.length === 0 && edgeIds.size === 0) return
  event.preventDefault()
  const afterNodes = removeNodes(doc.value, nodeIds)
  commit({ ...afterNodes, edges: afterNodes.edges.filter((edge) => !edgeIds.has(edge.id)) })
}

function handleResize() {
  lf?.resize()
}

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
  if (!window.confirm('重置为示例会丢掉当前未导出的改动，确定吗？')) return
  clearDoc()
  past.value = []
  future.value = []
  doc.value = harvestInboundDoc
  rerender()
}

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
    // 必须显式关掉：默认是开启的，而它的撤销作用于**画布模型**，
    // 会与文档级撤销打架（内置 ctrl+z / ctrl+y 会改画布而不改文档，
    // 造成画布与文档不一致）。关掉后那些内置快捷键因历史栈为空而无害。
    history: false,
    plugins: [Snapshot],
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
    if (doc.value.nodes.some((node) => node.id === args.data.id)) {
      commit(removeNodes(doc.value, [args.data.id]))
      return
    }
    // 删的不是文档里的节点（泳道表头这类背景节点）：LogicFlow 已经把它从画布上摘掉了，
    // 必须立刻重渲染把它放回来，否则表头会一直缺到下一次编辑为止。
    rerender()
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