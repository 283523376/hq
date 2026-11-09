<template>
  <!-- 其他出库单：样单 QTCK10126110327 版式（领用类型/部门/日期 + 7 列 + 审核/仓管员/领料） -->
  <div class="print-sheet">
    <div v-for="(page, pi) in data.pages" :key="pi" class="print-page">
      <div class="sheet-company">{{ company }}</div>
      <!-- 纸样：领用类型（左）+ 标题（中，带双下划线）+ 页码（右）同一行 -->
      <div class="title-row">
        <span class="meta-item">领用类型　{{ data.headers['领用类型'] }}</span>
        <span class="title-center">
          <span class="sheet-title">{{ data.title }}</span>
          <span class="sheet-title-rule"></span>
        </span>
        <span class="meta-item">第{{ pi + 1 }}页，共{{ data.totalPages }}页</span>
      </div>
      <!-- 纸样第二行：部门（左） 日期（中） 单据编号（右） -->
      <div class="sheet-meta">
        <div class="meta-block meta-left">
          <span class="meta-item">部门：{{ data.headers['部门'] }}</span>
        </div>
        <div class="meta-block meta-center">
          <span class="meta-item">日期：{{ data.headers['日期'] }}</span>
        </div>
        <div class="meta-block meta-right">
          <span class="meta-item">单据编号：{{ data.erpBillNo }}</span>
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
            <td v-for="(cell, ci) in row" :key="ci" :class="{ center: ci !== 1 && ci !== 2 }">{{ cell }}</td>
          </tr>
          <tr v-if="pi === data.totalPages - 1" class="total-row">
            <td colspan="4" class="center">合计</td>
            <td class="center"></td>
            <td class="num">{{ data.totalsOnLastPage['数量'] }}</td>
            <td></td>
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

  /** 列宽 %（样单观感：序号窄、名称/规格宽） */
  function colWidth(index: number) {
    const widths = ['6%', '20%', '28%', '20%', '8%', '8%', '10%'];
    return { width: widths[index] || 'auto' };
  }
</script>

<style scoped>
  .title-row {
    display: grid;
    grid-template-columns: 1fr auto 1fr;
    align-items: center;
  }

  .title-center {
    display: flex;
    flex-direction: column;
    align-items: center;
  }

  .title-center .sheet-title {
    margin: 4px 0 2px;
  }

  .title-center .sheet-title-rule {
    display: block;
    width: 100%;
    margin: 0;
  }

  .title-row > .meta-item:last-child {
    justify-self: end;
  }
</style>