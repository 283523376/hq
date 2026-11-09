<template>
  <div class="print-preview">
    <!-- 快照回退标注：显著提示数据时间，打印时隐藏 -->
    <a-alert
      v-if="data.snapshotTime"
      class="no-print"
      type="warning"
      show-icon
      :message="`金蝶暂不可达，本次预览为 ${snapshotTimeText} 的数据`"
      :description="data.extras?.warn || ''"
      style="margin-bottom: 12px"
    />
    <div class="print-area">
      <component :is="template" :data="data" />
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

  const props = defineProps<{ data: WmsPrintDataVO }>();

  const TEMPLATES: Record<string, any> = {
    PROFIT: ProfitListPrint,
    LOSS: ProfitListPrint,
    MISC_DELIVERY: MiscDeliveryPrint,
    MISC_INSTOCK: MiscInstockPrint,
    SUB_PICK: SubcontractPickPrint,
    SALES_OUTSTOCK: SalesOutstockPrint,
    SALES_RETURN: SalesReturnPrint,
  };

  const template = computed(() => TEMPLATES[props.data.billType] || ProfitListPrint);

  const snapshotTimeText = computed(() => {
    const t = props.data.snapshotTime;
    if (!t) return '';
    const d = new Date(t);
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  });
</script>
