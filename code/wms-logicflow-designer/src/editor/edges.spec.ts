import { describe, expect, it } from 'vitest'

import { routeBetween } from './edges'

describe('routeBetween', () => {
  it('同一行上只留两端，不产生退化折线', () => {
    expect(routeBetween({ x: 0, y: 50 }, { x: 100, y: 50 })).toEqual([
      { x: 0, y: 50 },
      { x: 100, y: 50 },
    ])
  })

  it('不同行时走肘形：先水平、再垂直、再水平', () => {
    expect(routeBetween({ x: 0, y: 0 }, { x: 100, y: 80 })).toEqual([
      { x: 0, y: 0 },
      { x: 50, y: 0 },
      { x: 50, y: 80 },
      { x: 100, y: 80 },
    ])
  })

  it('两端同一点时不产出重复点', () => {
    expect(routeBetween({ x: 5, y: 5 }, { x: 5, y: 5 })).toEqual([{ x: 5, y: 5 }])
  })

  it('同一列上下相邻时只留两端（垂直共线）', () => {
    expect(routeBetween({ x: 0, y: 0 }, { x: 0, y: 100 })).toEqual([
      { x: 0, y: 0 },
      { x: 0, y: 100 },
    ])
  })
})