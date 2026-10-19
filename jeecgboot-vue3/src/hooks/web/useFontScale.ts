/**
 * 全局字体缩放核心逻辑（纯前端）。
 *
 * text（文字缩放）：联动 antd 主题 token 基准字号，App.vue 消费 textScaleRef。
 * zoom（界面缩放）：对目标元素写 CSS zoom，整页等比放大（含弹窗）。
 *
 * 取值按 5% 步进取整并收敛到合法区间，持久化在 localStorage，仅对本浏览器生效。
 */
import { ref } from 'vue';

/** antd token 的基准字号（对应 App.vue 里写死的 14） */
const TEXT_BASE_FONT_SIZE = 14;

/** 两个维度共用的步进（%） */
export const SCALE_STEP = 5;

/** 各维度合法区间（%） */
export const SCALE_RANGES = {
  text: [80, 150] as const,
  zoom: [50, 200] as const,
};

const STORAGE_KEY = 'wms_font_scale';
const DEFAULT_SCALE = 100;

/** 响应式状态（模块级单例）：textScaleRef 由 App.vue watch 联动 token.fontSize */
export const textScaleRef = ref(100);
export const zoomScaleRef = ref(100);

export function getTextScale(): number {
  return textScaleRef.value;
}

export function getZoomScale(): number {
  return zoomScaleRef.value;
}

/** 百分比 → antd token 基准字号（px） */
export function calcTextFontSize(percent: number): number {
  return Math.round((TEXT_BASE_FONT_SIZE * percent) / 100);
}

/** 按 5% 步进取整后收敛到 [min, max] */
export function clampScale(value: number, min: number, max: number): number {
  if (Number.isNaN(value)) {
    return min;
  }
  const stepped = Math.round(value / SCALE_STEP) * SCALE_STEP;
  return Math.min(max, Math.max(min, stepped));
}

/**
 * 把百分比写入目标元素 style.zoom。
 *
 * 100% 时清掉属性而不是写 1，保证未缩放时 DOM 与默认状态完全一致。
 */
export function applyZoomScale(
  percent: number,
  el: HTMLElement | { style: Record<string, string> } = document.body,
): void {
  if (el) {
    el.style.zoom = percent === DEFAULT_SCALE ? '' : String(percent / 100);
  }
}

/** 读回持久化的缩放设置；无存储、损坏或越界一律回到安全值 */
export function readStoredScale(): { text: number; zoom: number } {
  const fallback = { text: DEFAULT_SCALE, zoom: DEFAULT_SCALE };
  let raw: string | null = null;
  try {
    raw = window.localStorage.getItem(STORAGE_KEY);
  } catch (e) {
    return fallback;
  }
  if (!raw) {
    return fallback;
  }
  try {
    const parsed = JSON.parse(raw);
    return {
      text: clampScale(Number(parsed?.text), SCALE_RANGES.text[0], SCALE_RANGES.text[1]),
      zoom: clampScale(Number(parsed?.zoom), SCALE_RANGES.zoom[0], SCALE_RANGES.zoom[1]),
    };
  } catch (e) {
    return fallback;
  }
}

/** 更新响应式状态并持久化（是否落到 DOM 由调用方决定） */
export function setScale(type: 'text' | 'zoom', percent: number): void {
  const value = clampScale(
    Number(percent),
    SCALE_RANGES[type][0],
    SCALE_RANGES[type][1],
  );
  if (type === 'text') {
    textScaleRef.value = value;
  } else {
    zoomScaleRef.value = value;
  }
  persist();
}

/** 恢复默认（100%） */
export function resetScale(type: 'text' | 'zoom', el?: HTMLElement): void {
  setScale(type, DEFAULT_SCALE);
  if (type === 'zoom') {
    applyZoomScale(DEFAULT_SCALE, el);
  }
}

function persist(): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ text: textScaleRef.value, zoom: zoomScaleRef.value }));
  } catch (e) {
    // 隐私模式等场景写不进 localStorage：缩放仍然生效，只是刷新后不保留
  }
}

/**
 * 应用启动时恢复上次的缩放设置。
 *
 * zoom 在这里直接落到目标元素（默认 body）；text 只同步 ref，字号由 App.vue 的 token watch 消费。
 */
export function initFontScale(el: HTMLElement | { style: Record<string, string> } = document.body): void {
  const stored = readStoredScale();
  textScaleRef.value = stored.text;
  zoomScaleRef.value = stored.zoom;
  applyZoomScale(stored.zoom, el);
}
