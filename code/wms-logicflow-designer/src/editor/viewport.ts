/**
 * 视口的捕获与还原。抽成独立模块是为了能单测——这里是**真实发生过 bug 的地方**。
 *
 * 关键事实（已核对 @logicflow/core 2.2.5 源码）：
 *  - `translate(x, y)` 是**相对**量，内部执行 `TRANSLATE_X += x`，不是设定绝对值；
 *  - `resetTranslate()` 把偏移归零；
 *  - `lf.render()` **不会**重置变换（它只做 graphDataToModel + preact 渲染），
 *    所以重渲染之后偏移还在，不需要也不需要「再应用一次」。
 *
 * 因此还原必须**先归零、再设绝对值**。曾经漏掉归零这一步，直接把捕获到的绝对偏移
 * 当成增量再传一次，结果是每编辑一次视口就漂移一倍（340 → 680 → 1360…）。
 */

export interface ViewportSnapshot {
  scale: number
  translateX: number
  translateY: number
}

/** 只读侧：LogicFlow 的 transformModel 直接满足 */
export interface ViewportReader {
  SCALE_X: number
  TRANSLATE_X: number
  TRANSLATE_Y: number
}

/** 写入侧：LogicFlow 实例直接满足 */
export interface ViewportWriter {
  resetTranslate(): void
  translate(x: number, y: number): void
  zoom(scale: number): void
}

export function captureViewport(reader: ViewportReader): ViewportSnapshot {
  return {
    scale: reader.SCALE_X,
    translateX: reader.TRANSLATE_X,
    translateY: reader.TRANSLATE_Y,
  }
}

export function restoreViewport(writer: ViewportWriter, snapshot: ViewportSnapshot): void {
  // translate 是相对量：不先归零就会把偏移翻倍
  writer.resetTranslate()
  writer.translate(snapshot.translateX, snapshot.translateY)
  writer.zoom(snapshot.scale)
}