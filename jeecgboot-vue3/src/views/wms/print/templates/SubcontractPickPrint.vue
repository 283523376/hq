<template>
  <!-- 委外领料单：样单 WMLL00010491 版式（加工单位/业务类型/日期 + 9 列 + 审核人/发料人/制单人） -->
  <div class="print-sheet">
    <div v-for="(page, pi) in data.pages" :key="pi" class="print-page">
      <div class="sheet-company">{{ company }}</div>
      <!-- 样单：标题比公司名小一号且不加粗 -->
      <div class="sheet-title small">{{ data.title }}</div>
      <div class="sheet-meta">
        <div class="meta-block meta-left">
          <span class="meta-item">加工单位：{{ data.headers['加工单位'] }}</span>
        </div>
        <div class="meta-block meta-center">
          <span class="meta-item">日　期：{{ data.headers['日期'] }}</span>
        </div>
        <div class="meta-block meta-right">
          <span class="meta-item">编　号：{{ data.erpBillNo }}</span>
        </div>
      </div>
      <!-- 样单信息行第二行单独放业务类型 -->
      <div class="sheet-meta sub-type-row">
        <div class="meta-block meta-left">
          <span class="meta-item">业务类型：{{ data.headers['业务类型'] }}</span>
        </div>
        <div></div>
        <div></div>
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
            <td colspan="4">合计</td>
            <td>{{ data.totalsOnLastPage['实发数量'] }}</td>
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
    const widths = ['18%', '13%', '15%', '14%', '9%', '10.5%', '9%', '4%', '7.5%'];
    return { width: widths[index] || 'auto' };
  }
</script>

<style scoped>
  .sub-type-row {
    margin-top: -4px;
  }
</style>