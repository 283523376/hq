import { describe, expect, it } from 'vitest'

import { measureText, wrapText, wrappedTextHeight } from './text'

describe('measureText', () => {
  it('CJK 按 1em、Latin 按 0.55em 估宽', () => {
    expect(measureText('AB', 10)).toBeCloseTo(11)
    expect(measureText('材料', 10)).toBeCloseTo(20)
    expect(measureText('A材', 10)).toBeCloseTo(15.5)
  })

  it('空文本宽度为 0', () => {
    expect(measureText('', 12)).toBe(0)
  })
})

describe('wrapText', () => {
  it('超出可用宽度时逐字换行', () => {
    expect(wrapText('ABCDEFGH', 22, 10)).toEqual(['ABCD', 'EFGH'])
  })

  it('显式换行符按硬换行优先', () => {
    expect(wrapText('甲\n乙', 100, 10)).toEqual(['甲', '乙'])
  })

  it('空文本仍占一行，不会塌成 0 行', () => {
    expect(wrapText('', 100, 10)).toEqual([''])
  })

  it('单个宽于可用宽度的字独占一行而不被丢弃', () => {
    expect(wrapText('材', 1, 10)).toEqual(['材'])
  })

  it('连续换行符产生空行而不吞掉', () => {
    expect(wrapText('甲\n\n乙', 100, 10)).toEqual(['甲', '', '乙'])
  })
})

describe('wrappedTextHeight', () => {
  it('高度 = 行数 × 行高', () => {
    expect(wrappedTextHeight('甲\n乙', 100, 10, 18)).toBe(36)
  })

  it('空文本高度为一行高（不塌成 0）', () => {
    expect(wrappedTextHeight('', 100, 10, 18)).toBe(18)
  })
})