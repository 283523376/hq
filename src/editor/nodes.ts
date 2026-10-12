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
 * 交互分工：
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
    // 不可拖，但保留命中（双击表头改泳道名）
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