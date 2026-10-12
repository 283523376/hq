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