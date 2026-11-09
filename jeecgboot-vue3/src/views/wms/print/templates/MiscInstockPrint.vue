<template>
  <!-- 其他入库单：样单 QTRK10126110010 版式（部门/日期 + 7 列含批号/收货仓库 + 审核/验收/保管/制单） -->
  <div class="print-sheet">
    <div v-for="(page, pi) in data.pages" :key="pi" class="print-page">
      <div class="sheet-title">{{ data.title }}</div>
      <div class="sheet-meta">
        <div class="meta-block">
          <span class="meta-item">部门：{{ data.headers['部门'] }}</span>
        </div>
        <div class="meta-block">
          <span class="meta-item">日期：{{ data.headers['日期'] }}</span>
          <span class="meta-item">单据编号：{{ data.erpBillNo }}</span>
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
            <td v-for="(cell, ci) in row" :key="ci" :class="{ center: ci !== 0 && ci !== 1 }">{{ cell }}</td>
          </tr>
          <tr v-if="pi === data.totalPages - 1" class="total-row">
            <td colspan="4" class="center">合计</td>
            <td class="num">{{ data.totalsOnLastPage['数量'] }}</td>
            <td></td>
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
  import type { WmsPrintDataVO } from '../print.data';

  defineProps<{ data: WmsPrintDataVO }>();

  function colWidth(index: number) {
    const widths = ['20%', '20%', '18%', '7%', '9%', '10%', '16%'];
    return { width: widths[index] || 'auto' };
  }
</script>
