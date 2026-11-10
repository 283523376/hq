import { describe, expect, it } from 'vitest'

import { harvestInboundDoc } from './seed/harvestInbound'
import {
  clearDoc,
  loadDoc,
  parseDoc,
  saveDoc,
  serializeDoc,
  STORAGE_KEY,
  type StorageLike,
} from './storage'
import type { FlowDoc } from './types'

/** 内存版 storage：storage.ts 把 Storage 当参数传入，正是为了这里能替身 */
function memoryStorage(): StorageLike & { map: Map<string, string> } {
  const map = new Map<string, string>()
  return {
    map,
    getItem: (key) => map.get(key) ?? null,
    setItem: (key, value) => {
      map.set(key, value)
    },
    removeItem: (key) => {
      map.delete(key)
    },
  }
}

describe('serializeDoc / parseDoc', () => {
  it('序列化再解析回来与原文档等价', () => {
    const parsed = parseDoc(serializeDoc(harvestInboundDoc))
    expect(parsed.ok).toBe(true)
    if (parsed.ok) expect(parsed.doc).toEqual(harvestInboundDoc)
  })

  it('解析坏 JSON 返回可读错误而不抛异常', () => {
    const parsed = parseDoc('{ 这不是 json')
    expect(parsed.ok).toBe(false)
    if (!parsed.ok) expect(parsed.errors.join()).toMatch(/JSON/)
  })

  it('解析结构不合法的 JSON 返回校验错误', () => {
    const parsed = parseDoc(JSON.stringify({ schemaVersion: 2 }))
    expect(parsed.ok).toBe(false)
  })

  it('null 返回错误而不是崩溃', () => {
    expect(parseDoc(null).ok).toBe(false)
  })
})

describe('loadDoc / saveDoc / clearDoc', () => {
  it('空存储时回落到示例图', () => {
    expect(loadDoc(memoryStorage(), harvestInboundDoc).id).toBe(harvestInboundDoc.id)
  })

  it('存了再读拿到存进去的那份', () => {
    const storage = memoryStorage()
    const edited: FlowDoc = { ...harvestInboundDoc, name: '改过的' }
    saveDoc(edited, storage)
    expect(loadDoc(storage, harvestInboundDoc).name).toBe('改过的')
  })

  it('存储里是坏数据时回落到示例图（不白屏、不抛异常）', () => {
    const storage = memoryStorage()
    storage.setItem(STORAGE_KEY, '{ 坏的')
    expect(loadDoc(storage, harvestInboundDoc).id).toBe(harvestInboundDoc.id)
  })

  it('clearDoc 之后读到的是回落值', () => {
    const storage = memoryStorage()
    saveDoc({ ...harvestInboundDoc, name: '改过的' }, storage)
    clearDoc(storage)
    expect(loadDoc(storage, harvestInboundDoc).name).toBe(harvestInboundDoc.name)
  })
})