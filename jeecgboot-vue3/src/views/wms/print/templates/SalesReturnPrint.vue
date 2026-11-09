<template>
  <!-- 销售退货单：样单 XSTHD10126110003 版式（购货单位/日期/编号 + 6 列 + 保管员/送提货人/发货签名 + 公司信息块） -->
  <div class="print-sheet">
    <div v-for="(page, pi) in data.pages" :key="pi" class="print-page">
      <div class="sheet-company">{{ company }}</div>
      <div class="sheet-title">{{ data.title }}</div>
      <div class="sheet-meta">
        <div class="meta-block">
          <span class="meta-item">购货单位：{{ data.headers['购货单位'] }}</span>
        </div>
        <div class="meta-block">
          <span class="meta-item">日期：{{ data.headers['日期'] }}</span>
          <span class="meta-item">编 号：{{ data.erpBillNo }}</span>
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
            <td v-for="(cell, ci) in row" :key="ci" :class="{ center: ci === 3 || ci === 4 }">{{ cell }}</td>
          </tr>
          <tr v-if="pi === data.totalPages - 1" class="total-row">
            <td colspan="4" class="center">合计</td>
            <td class="num">{{ data.totalsOnLastPage['数量'] }}</td>
            <td></td>
          </tr>
        </tbody>
      </table>
      <div class="sheet-footers">
        <span v-for="(footer, fi) in data.footers" :key="fi">{{ footer }}</span>
      </div>
      <div class="sheet-company-info">
        <div v-for="(line, li) in companyInfo" :key="li">{{ line }}</div>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
  import { computed } from 'vue';
  import type { WmsPrintDataVO } from '../print.data';

  const props = defineProps<{ data: WmsPrintDataVO }>();
  const company = computed(() => props.data.extras?.['公司名'] || '宜昌永鑫精工科技股份有限公司');
  const companyInfo = computed<string[]>(() => props.data.extras?.['公司信息块'] || []);

  function colWidth(index: number) {
    const widths = ['30%', '26%', '18%', '8%', '10%', '8%'];
    return { width: widths[index] || 'auto' };
  }
</script>
