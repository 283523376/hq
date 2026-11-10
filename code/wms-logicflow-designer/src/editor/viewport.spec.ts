import { describe, expect, it } from 'vitest'

import { captureViewport, restoreViewport, type ViewportSnapshot } from './viewport'

/**
 * 假的「LogicFlow 变换」，刻意照搬它的真实语义（已核对 @logicflow/core 2.2.5 源码）：
 *  - `translate(x, y)` 是**相对**的：内部是 `TRANSLATE_X += x`
 *  - `resetTranslate()` 把偏移归零
 * 把真实语义写进替身，是为了让「把绝对偏移当增量再传一次」这个 bug 能被抓住——
 * 那正是真实发生过的视口漂移。
 */
function fakeTransform(initial: { x: number; y: number; scale: number }) {
  const state = { ...initial }
  return {
    state,
    get SCALE_X() {
      return state.scale
    },
    get TRANSLATE_X() {
      return state.x
    },
    get TRANSLATE_Y() {
      return state.y
    },
    resetTranslate() {
      state.x = 0
      state.y = 0
    },
    translate(x: number, y: number) {
      state.x += x
      state.y += y
    },
    zoom(scale: number) {
      state.scale = scale
    },
  }
}

describe('captureViewport', () => {
  it('捕获当前的缩放与平移', () => {
    const transform = fakeTransform({ x: 340, y: -180, scale: 0.72 })
    expect(captureViewport(transform)).toEqual({ scale: 0.72, translateX: 340, translateY: -180 })
  })
})

describe('restoreViewport', () => {
  it('把视口还原到捕获值，即便当前已有偏移', () => {
    const transform = fakeTransform({ x: 340, y: -180, scale: 0.72 })
    const snapshot: ViewportSnapshot = captureViewport(transform)
    restoreViewport(transform, snapshot)
    expect(transform.state).toEqual({ x: 340, y: -180, scale: 0.72 })
  })

  it('连续还原多次也不漂移（这个 bug 真实发生过）', () => {
    const transform = fakeTransform({ x: 340, y: -180, scale: 0.72 })
    const snapshot = captureViewport(transform)
    restoreViewport(transform, snapshot)
    restoreViewport(transform, snapshot)
    restoreViewport(transform, snapshot)
    expect(transform.state).toEqual({ x: 340, y: -180, scale: 0.72 })
  })

  it('还原到原点也是精确归零', () => {
    const transform = fakeTransform({ x: 340, y: -180, scale: 0.72 })
    restoreViewport(transform, { scale: 1, translateX: 0, translateY: 0 })
    expect(transform.state).toEqual({ x: 0, y: 0, scale: 1 })
  })

  it('从零偏移出发也正确（渲染前没有动过视口的情形）', () => {
    const transform = fakeTransform({ x: 0, y: 0, scale: 1 })
    restoreViewport(transform, { scale: 0.5, translateX: 12, translateY: -8 })
    expect(transform.state).toEqual({ x: 12, y: -8, scale: 0.5 })
  })
})