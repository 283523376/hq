<template>
  <!-- 委外领料单：样单 WMLL00010491 版式（加工单位/业务类型/日期 + 9 列 + 审核人/发料人/制单人） -->
  <div class="print-sheet">
    <div v-for="(page, pi) in data.pages" :key="pi" class="print-page">
      <div class="sheet-company">{{ company }}</div>
      <div class="sheet-title">{{ data.title }}</div>
      <div class="sheet-meta">
        <div class="meta-block">
          <span class="meta-item">加工单位：{{ data.headers['加工单位'] }}</span>
          <span class="meta-item">业务类型：{{ data.headers['业务类型'] }}</span>
          <span class="meta-item">日期：{{ data.headers['日期'] }}</span>
        </div>
        <div class="meta-block">
          <span class="meta-item">编号：{{ data.erpBillNo }}</span>
          <span class="meta-item">第{{ pi + 1 }}页，共{{ data.totalPages }}页</span>
        </div>
      </div>
      <table class="sheet-table">
        <thead>
          <tr>
            <th v-for="(col, ci) in data.columns" :key="ci" :style="colWidth(ci)">{{ col }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, ri) in page" :key="ri">
            <td v-for="(cell, ci) in row" :key="ci" :class="{ center: ci === 4 || ci === 7 }">{{ cell }}</td>
          </tr>
          <tr v-if="pi === data.totalPages - 1" class="total-row">
            <td colspan="4" class="center">合计</td>
            <td class="num">{{ data.totalsOnLastPage['实发数量'] }}</td>
            <td colspan="4"></td>
          </tr>
        </tbody>
      </table>
      <div class="sheet-footers">
        <span v-for="(footer, fi) in data.footers" :key="fi">{{ footer }}</span>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
  import { computed } from 'vue';
  import type { WmsPrintDataVO } from '../print.data';

  const props = defineProps<{ data: WmsPrintDataVO }>();
  const company = computed(() => props.data.extras?.['公司名'] || '宜昌永鑫精工科技股份有限公司');

  function colWidth(index: number) {
    const widths = ['12%', '11%', '14%', '14%', '8%', '14%', '14%', '6%', '7%'];
    return { width: widths[index] || 'auto' };
  }
</script>
