<template>
  <a-card :bordered="false" size="small" :title="panelTitle">
    <template #extra>
      <a-button @click="handleReset">恢复默认</a-button>
    </template>

    <a-alert type="info" show-icon :message="panelDesc" class="font-scale-tip" />

    <!-- 滑块单独占一行：刻度文字（如 150%）在字号放大后会溢出到右侧，
         与数字输入框并排必然重叠，改为上下排列彻底避开 -->
    <div class="font-scale-row">
      <a-slider
        v-model:value="percent"
        :min="range[0]"
        :max="range[1]"
        :step="SCALE_STEP"
        :marks="marks"
        style="flex: 1"
        @change="handleApply"
      />
    </div>
    <div class="font-scale-current">
      <span class="font-scale-presets-label">当前档位：</span>
      <a-input-number
        v-model:value="percent"
        :min="range[0]"
        :max="range[1]"
        :step="SCALE_STEP"
        :parser="parser"
        :formatter="formatter"
        style="width: 100px"
        @change="handleApply"
      />
    </div>

    <div class="font-scale-presets">
      <span class="font-scale-presets-label">快捷档位：</span>
      <a-button
        v-for="p in presets"
        :key="p"
        size="small"
        :type="percent === p ? 'primary' : 'default'"
        @click="handlePreset(p)"
      >
        {{ p }}%
      </a-button>
    </div>
  </a-card>
</template>

<script lang="ts" name="wms-font-scale-panel" setup>
  import { computed, ref } from 'vue';
  import {
    SCALE_RANGES,
    SCALE_STEP,
    applyZoomScale,
    clampScale,
    getTextScale,
    getZoomScale,
    setScale,
  } from '/@/hooks/web/useFontScale';

  /**
   * 字体缩放设置面板。
   *
   * type=text 联动 antd token 基准字号；type=zoom 对整页写 CSS zoom。
   * 拖动/输入/档位都会立即生效并自动保存（localStorage）。
   */
  const props = defineProps<{ type: 'text' | 'zoom' }>();

  const range = computed(() => (props.type === 'text' ? SCALE_RANGES.text : SCALE_RANGES.zoom));
  const panelTitle = computed(() => (props.type === 'text' ? '文字缩放' : '界面缩放'));
  const panelDesc = computed(() =>
    props.type === 'text'
      ? '调整系统内组件文字的基准字号（默认 14px × 比例），按钮、输入框等控件高度与表格、表单文字一并放大；布局结构不变，个别页面写死的字号不受影响。'
      : '对整个页面（含弹窗、消息提示）整体等比缩放，效果类似浏览器的 Ctrl + 滚轮。'
  );

  const percent = ref<number>(props.type === 'text' ? getTextScale() : getZoomScale());

  const marks = computed(() => {
    const [min, max] = range.value;
    const m: Record<number, string> = { [min]: `${min}%`, [max]: `${max}%` };
    if (100 > min && 100 < max) {
      m[100] = '100%';
    }
    return m;
  });

  const presets = computed(() =>
    props.type === 'text' ? [80, 100, 120, 150] : [50, 100, 125, 150, 200]
  );

  function formatter(v?: number) {
    return v === null || v === undefined ? '' : `${v}`;
  }

  function parser(v?: string) {
    return v === null || v === undefined ? (undefined as any) : Number(String(v).replace(/[^\d]/g, ''));
  }

  /** 统一入口：收敛取值 → 更新状态并持久化 → zoom 维度同步落 DOM */
  function handleApply() {
    const pct = clampScale(Number(percent.value) || 100, range.value[0], range.value[1]);
    percent.value = pct;
    setScale(props.type, pct);
    if (props.type === 'zoom') {
      applyZoomScale(pct);
    }
  }

  function handlePreset(p: number) {
    percent.value = p;
    handleApply();
  }

  function handleReset() {
    percent.value = 100;
    handleApply();
  }
</script>

<style lang="less" scoped>
  .font-scale-tip {
    margin-bottom: 16px;
  }

  .font-scale-row {
    display: flex;
    align-items: center;
    max-width: 560px;
    // 最右端刻度文字以刻度点为中心展开，需要给它留出半个字宽的余量
    padding-right: 24px;
  }

  .font-scale-current {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 8px;
  }

  .font-scale-presets {
    margin-top: 24px;
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .font-scale-presets-label {
    color: rgba(0, 0, 0, 0.45);
    font-size: 12px;
  }
</style>
