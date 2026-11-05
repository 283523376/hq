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

/**
 * antd 的 `controlHeight` 种子默认值。
 *
 * 关键：它和 `fontSize` 是**两个独立的种子 token**，不会跟着字号自动变大。
 * 只改字号时，21px 的字行盒约 29px 仍被塞在 32px（小尺寸按钮 24px）的控件里，
 * 就会出现「文字压边/被裁」——所以文字缩放必须同时缩放控件高度。
 */
const CONTROL_BASE_HEIGHT = 32;

/**
 * antd 的 `lineHeight` 默认值（22/14 ≈ 1.571）。
 *
 * 行间距调节以它为 100% 基准：只改 text 缩放会放大字号但行高按倍数同步，
 * 视觉上「字变大了行没变松」；单独给一个维度才能在不改字号的前提下疏密可调。
 */
const BASE_LINE_HEIGHT = 22 / 14;

/** 各维度共用的步进（%） */
export const SCALE_STEP = 5;

/** 各维度合法区间（%） */
export const SCALE_RANGES = {
  text: [80, 150] as const,
  zoom: [50, 200] as const,
  line: [80, 200] as const,
};

const STORAGE_KEY = 'wms_font_scale';
const DEFAULT_SCALE = 100;

/** 响应式状态（模块级单例）：textScaleRef 由 App.vue watch 联动 token.fontSize */
export const textScaleRef = ref(100);
export const zoomScaleRef = ref(100);
/** 行间距比例（100 = antd 默认行高），同样持久化在 localStorage */
export const lineScaleRef = ref(100);

export function getTextScale(): number {
  return textScaleRef.value;
}

export function getZoomScale(): number {
  return zoomScaleRef.value;
}

export function getLineScale(): number {
  return lineScaleRef.value;
}

/** 百分比 → antd token 基准字号（px） */
export function calcTextFontSize(percent: number): number {
  return Math.round((TEXT_BASE_FONT_SIZE * percent) / 100);
}

/** 百分比 → antd 控件高度（px） */
export function calcControlHeight(percent: number): number {
  return Math.round((CONTROL_BASE_HEIGHT * percent) / 100);
}

/**
 * 文字缩放要一并改的 antd token。
 *
 * 派生 token（controlHeightSM/LG、菜单项高度等）都由 controlHeight 推导，改这一个即可。
 */
export function buildFontTokens(percent: number): {
  fontSize: number;
  controlHeight: number;
  lineHeight: number;
} {
  return {
    fontSize: calcTextFontSize(percent),
    controlHeight: calcControlHeight(percent),
    // 行高与字号是两个独立维度：这里直接读行距档位，不跟着 text 比例走
    lineHeight: calcLineHeight(getLineScale()),
  };
}

/** 百分比 → antd token.lineHeight（无单位倍数） */
export function calcLineHeight(percent: number): number {
  const pct = Number.isNaN(Number(percent)) ? 100 : Number(percent);
  // 保留 4 位小数，避免浮点尾差让 antd 算出不同的行盒高度
  return Math.round(((BASE_LINE_HEIGHT * pct) / 100) * 10000) / 10000;
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
 * 表格列宽随文字缩放等比放大（只在放大时放大，缩小字号时保持原列宽）。
 *
 * BasicTable 在 useColumns 里内置了同样的 scaleColumnWidth；裸 `a-table` 不经过那条链路，
 * 列宽是写死的 px，150% 字号下表头折行、日期/数字压列——就是「文字缩放体检」漏掉裸表格页的根因。
 * 页面用 `computed(() => scaleTableColumns(cols))` 接入，与 BasicTable 语义保持一致。
 */
export function scaleTableColumns<T extends { width?: number; children?: unknown[] }>(columns: T[]): T[] {
  const scale = textScaleRef.value / 100;
  if (scale <= 1) {
    return columns;
  }
  const walk = (col: any): any => ({
    ...col,
    width: typeof col.width === 'number' ? Math.round(col.width * scale) : col.width,
    children: Array.isArray(col.children) ? col.children.map(walk) : col.children,
  });
  return columns.map(walk);
}

/** 表格横向滚动宽度按同一比例放大（传未缩放的基准值），与 scaleTableColumns 配套 */
export function scaleScrollX(base: number): number {
  const scale = textScaleRef.value / 100;
  return scale > 1 ? Math.round(base * scale) : base;
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
    // 弹层（下拉/日期/提示）由 antd 用 getBoundingClientRect 定位，而 rect 返回的是
    // **已缩放**坐标；把 top/left 再写回 zoom 上下文会被二次放大，弹层于是飞掉。
    // 这里暴露一个倒数给全局样式，让弹层自己抵消掉这一层缩放。
    document.documentElement.style.setProperty(
      '--wms-zoom-inverse',
      percent === DEFAULT_SCALE ? '' : String(DEFAULT_SCALE / percent),
    );
  }
}

/** 读回持久化的缩放设置；无存储、损坏或越界一律回到安全值 */
export function readStoredScale(): { text: number; zoom: number; line: number } {
  const fallback = { text: DEFAULT_SCALE, zoom: DEFAULT_SCALE, line: DEFAULT_SCALE };
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
      line: clampScale(Number(parsed?.line), SCALE_RANGES.line[0], SCALE_RANGES.line[1]),
    };
  } catch (e) {
    return fallback;
  }
}

/** 更新响应式状态并持久化（是否落到 DOM 由调用方决定） */
export function setScale(type: 'text' | 'zoom' | 'line', percent: number): void {
  const value = clampScale(
    Number(percent),
    SCALE_RANGES[type][0],
    SCALE_RANGES[type][1],
  );
  if (type === 'text') {
    textScaleRef.value = value;
  } else if (type === 'line') {
    lineScaleRef.value = value;
  } else {
    zoomScaleRef.value = value;
  }
  persist();
}

/** 恢复默认（100%） */
export function resetScale(type: 'text' | 'zoom' | 'line', el?: HTMLElement): void {
  setScale(type, DEFAULT_SCALE);
  if (type === 'zoom') {
    applyZoomScale(DEFAULT_SCALE, el);
  }
}

function persist(): void {
  try {
    window.localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ text: textScaleRef.value, zoom: zoomScaleRef.value, line: lineScaleRef.value }),
    );
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
  lineScaleRef.value = stored.line;
  applyZoomScale(stored.zoom, el);
}
