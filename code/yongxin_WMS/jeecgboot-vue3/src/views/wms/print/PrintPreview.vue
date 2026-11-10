<template>
  <div class="print-preview">
    <!-- 批量打印时每张单据之前标注序号与单号，便于分拣（打印时隐藏） -->
    <div v-if="items.length > 1" class="batch-tip no-print">
      共 {{ items.length }} 张单据，将一次打印；
      {{ pageMode === 'per-bill' ? '每张单据自成一页' : '连续排版，页满自动换页' }}
    </div>
    <!-- 打印区：所有选中单据同处一个 .print-area，多页由各模板的 .print-page 分页 -->
    <div
      class="print-area"
      :class="[{ 'print-area-editable': editable }, items.length > 1 ? `mode-${pageMode}` : '']"
      :contenteditable="editable ? 'true' : 'false'"
    >
      <!-- multi-page：本身超过一页的单据（行数多），其分页由组装器决定，不参与「整张不拆」约束 -->
      <div v-for="(item, idx) in items" :key="idx" class="print-item" :class="{ 'multi-page': pageCount(item) > 1 }">
        <!-- 数据提示：快照回退标注数据时间；仅 warn（如本地单金蝶价税取不到）也要显示；打印时隐藏 -->
        <a-alert
          v-if="item.snapshotTime || item.extras?.warn"
          class="no-print"
          type="warning"
          show-icon
          :message="alertMessage(item)"
          :description="item.extras?.warn || ''"
          style="margin-bottom: 12px"
        />
        <component :is="templateOf(item)" :data="item" />
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
  import { computed } from 'vue';
  import type { WmsPrintDataVO } from './print.data';
  import ProfitListPrint from './templates/ProfitListPrint.vue';
  import MiscDeliveryPrint from './templates/MiscDeliveryPrint.vue';
  import MiscInstockPrint from './templates/MiscInstockPrint.vue';
  import SubcontractPickPrint from './templates/SubcontractPickPrint.vue';
  import SalesOutstockPrint from './templates/SalesOutstockPrint.vue';
  import SalesReturnPrint from './templates/SalesReturnPrint.vue';

  /**
   * @param data    单张单据（兼容旧用法）
   * @param items   批量单据；传入后优先于 data
   * @param editable 编辑模式（仅单张时生效，批量打印不开放编辑以免串改）
   * @param pageMode 批量排版：per-bill 每张自成一页（默认）/ auto 自动排版连续排布
   */
  const props = withDefaults(
    defineProps<{
      data?: WmsPrintDataVO;
      items?: WmsPrintDataVO[];
      editable?: boolean;
      pageMode?: 'per-bill' | 'auto';
    }>(),
    { editable: false, pageMode: 'per-bill' },
  );

  const TEMPLATES: Record<string, any> = {
    PROFIT: ProfitListPrint,
    LOSS: ProfitListPrint,
    MISC_DELIVERY: MiscDeliveryPrint,
    MISC_INSTOCK: MiscInstockPrint,
    SUB_PICK: SubcontractPickPrint,
    SALES_OUTSTOCK: SalesOutstockPrint,
    SALES_RETURN: SalesReturnPrint,
  };

  const items = computed<WmsPrintDataVO[]>(() => {
    if (props.items && props.items.length) return props.items;
    return props.data ? [props.data] : [];
  });

  const editable = computed(() => props.editable && items.value.length === 1);

  function templateOf(item: WmsPrintDataVO) {
    return TEMPLATES[item.billType] || ProfitListPrint;
  }

  /** 单据自身页数：>1 表示行数已超出单页容量，分页由组装器切分，不能被「整张不拆」挡住 */
  function pageCount(item: WmsPrintDataVO) {
    return item.totalPages || item.pages?.length || 1;
  }

  function snapshotTimeText(t?: string | null) {
    if (!t) return '';
    const d = new Date(t);
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }

  /** 有快照：提示数据时间；无快照仅有 warn（如本地单金蝶价税取不到）：直接展示原因 */
  function alertMessage(item: WmsPrintDataVO) {
    return item.snapshotTime
      ? `金蝶暂不可达，本次预览为 ${snapshotTimeText(item.snapshotTime)} 的数据`
      : '打印数据提示';
  }
</script>

<style scoped>
  .batch-tip {
    margin-bottom: 8px;
    font-size: 12px;
    color: #666;
  }

  .print-area-editable {
    outline: 2px dashed #faad14;
    outline-offset: 4px;
    cursor: text;
  }

  .print-area-editable :deep(td:hover),
  .print-area-editable :deep(th:hover) {
    background: #fffbe6;
  }

  /* 批量时每张单据之间留间距，打印时由各模板 .print-page 的 page-break-after 分页 */
  .print-item + .print-item {
    margin-top: 16px;
  }

  @media print {
    .print-area-editable {
      outline: none;
    }

    .print-item + .print-item {
      margin-top: 0;
    }
  }
</style>
