<template>
  <!-- 销售出库单：样单 XSCK1012619868 版式（logo + 订单类型/客户订单号/编号 + 6 列 + 五项签名 + 联次 + 公司信息块） -->
  <div class="print-sheet">
    <div v-for="(page, pi) in data.pages" :key="pi" class="print-page">
      <div class="sheet-top">
        <div class="logo-wrap">
          <img class="sheet-logo" :src="logoUrl" alt="YOUTH 佑勝" />
        </div>
        <div class="top-center">
          <div class="sheet-company">{{ company }}</div>
          <div class="sheet-title">{{ data.title }}</div>
        </div>
        <div class="top-right">
          <div class="meta-item">订单类型：{{ data.headers['订单类型'] }}</div>
          <div class="meta-item">客户订单号：{{ data.headers['客户订单号'] }}</div>
          <div class="meta-item">编 号：{{ data.erpBillNo }}</div>
        </div>
      </div>
      <div class="sheet-meta">
        <div class="meta-block">
          <span class="meta-item">购货单位：{{ data.headers['购货单位'] }}</span>
        </div>
        <div class="meta-block">
          <span class="meta-item">日期：{{ data.headers['日期'] }}</span>
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
            <td v-for="(cell, ci) in row" :key="ci" :class="{ center: ci === 1 || ci === 2 || ci === 3 || ci === 4 }">{{ cell }}</td>
          </tr>
          <tr v-if="pi === data.totalPages - 1" class="total-row">
            <td class="center">合计</td>
            <td></td>
            <td class="num">{{ data.totalsOnLastPage['数量'] }}</td>
            <td></td>
            <td class="num">{{ data.totalsOnLastPage['货款'] || '' }}</td>
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
      <div class="sheet-copies">
        <span v-for="(copy, ci) in copies" :key="ci">{{ copy }}</span>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
  import { computed } from 'vue';
  import type { WmsPrintDataVO } from '../print.data';
  // YOUTH 佑勝 logo（客户 2026-11-09 提供）
  import logoUrl from '../assets/youth-logo.png';

  const props = defineProps<{ data: WmsPrintDataVO }>();
  const company = computed(() => props.data.extras?.['公司名'] || '宜昌永鑫精工科技股份有限公司');
  const companyInfo = computed<string[]>(() => props.data.extras?.['公司信息块'] || []);
  const copies = computed<string[]>(() => props.data.extras?.['联次行'] || []);

  function colWidth(index: number) {
    const widths = ['44%', '8%', '12%', '10%', '12%', '14%'];
    return { width: widths[index] || 'auto' };
  }
</script>

<style scoped>
  .sheet-top {
    display: flex;
    justify-content: space-between;
    align-items: flex-end;
    gap: 12px;
  }

  .logo-wrap {
    width: 160px;
  }

  .top-center {
    flex: 1;
    text-align: center;
  }

  .top-right {
    width: 200px;
    font-size: 12px;
    text-align: left;
  }
</style>
