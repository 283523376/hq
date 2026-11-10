/**
 * 文本估宽与折行。
 *
 * 估宽规则沿用 layout/mindmap.ts 里 measureText 的思路：CJK 与全角标点约 1em、
 * Latin 约 0.55em（用 codePoint <= 0xff 判别）。**故意不抽成共享模块**：导图版
 * 只做单行估宽、编辑器版要折行，职责不同，且导图部分要求一行不动。
 *
 * 本模块是折行的单一事实源：节点高度由它算、文字也由它分行来画，
 * 所以不会出现「量高用一套、渲染用另一套」的错位。
 */

const LATIN_RATIO = 0.55

/** 估算 text 在 fontSize 下的推进宽度 */
export function measureText(text: string, fontSize: number): number {
  let width = 0
  for (const char of text) {
    // Latin-1 及以下按 0.55em，其余（CJK / 全角标点）按 1em
    width += (char.codePointAt(0) ?? 0) <= 0xff ? fontSize * LATIN_RATIO : fontSize
  }
  return width
}

/**
 * 把 text 按 maxWidth 折行，含换行符时硬换行优先。
 * 空文本返回 ['']（占一行），单个超宽字独占一行而非被丢弃。
 */
export function wrapText(text: string, maxWidth: number, fontSize: number): string[] {
  const lines: string[] = []
  for (const hardLine of text.split('\n')) {
    let current = ''
    for (const char of hardLine) {
      const next = current + char
      // current 为空时不换行，保证单个超宽字也能落到行上
      if (current !== '' && measureText(next, fontSize) > maxWidth) {
        lines.push(current)
        current = char
      } else {
        current = next
      }
    }
    lines.push(current)
  }
  return lines
}

/** 折行后的总高度 */
export function wrappedTextHeight(
  text: string,
  maxWidth: number,
  fontSize: number,
  lineHeight: number,
): number {
  return wrapText(text, maxWidth, fontSize).length * lineHeight
}