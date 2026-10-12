/**
 * 单文档存取。
 *
 * storage 作为**参数**传入（默认才是 window.localStorage），这样单测能塞内存替身，
 * 不必依赖 jsdom。任何一步坏掉都回落到示例图（fallback），
 * 因为「打开应用看到一张图」远好过「打开应用看到白屏」。
 */
import { validateDoc, type ValidateResult } from './doc'
import { harvestInboundDoc } from './seed/harvestInbound'
import type { FlowDoc } from './types'

export const STORAGE_KEY = 'wms-flow:doc'

export interface StorageLike {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
}

export function serializeDoc(doc: FlowDoc): string {
  return JSON.stringify(doc, null, 2)
}

/** 解析 + 校验。坏 JSON 转成可读错误返回，不往上抛 */
export function parseDoc(raw: string | null): ValidateResult {
  if (raw === null) return { ok: false, errors: ['没有可解析的内容'] }
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    return { ok: false, errors: [`JSON 解析失败：${message}`] }
  }
  return validateDoc(parsed)
}

function defaultStorage(): StorageLike {
  return window.localStorage
}

export function loadDoc(storage?: StorageLike, fallback: FlowDoc = harvestInboundDoc): FlowDoc {
  const target = storage ?? defaultStorage()
  const result = parseDoc(target.getItem(STORAGE_KEY))
  return result.ok ? result.doc : fallback
}

export function saveDoc(doc: FlowDoc, storage?: StorageLike): void {
  const target = storage ?? defaultStorage()
  target.setItem(STORAGE_KEY, serializeDoc(doc))
}

export function clearDoc(storage?: StorageLike): void {
  const target = storage ?? defaultStorage()
  target.removeItem(STORAGE_KEY)
}