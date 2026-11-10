<template>
  <!-- 盘盈单 / 盘亏单：按客户 xlsx 表头 12 列清单（数量列标题随类型切换） -->
  <div class="print-sheet">
    <div v-for="(page, pi) in data.pages" :key="pi" class="print-page">
      <div class="sheet-title">{{ data.title }}</div>
      <!-- 表体已有「单据编号」列，信息行只放页码，避免重复打印 -->
      <div class="sheet-meta">
        <div></div>
        <div></div>
        <div class="meta-block meta-right">
          <span class="meta-item">第{{ pi + 1 }}页，共{{ data.totalPages }}页</span>
        </div>
      </div>
      <table class="sheet-table">
        <thead>
          <tr>
            <th v-for="(col, ci) in data.columns" :key="ci">{{ col }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, ri) in page" :key="ri">
            <td v-for="(cell, ci) in row" :key="ci" :class="{ center: ci !== 4 && ci !== 5 && ci !== 6 }">{{ cell }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script lang="ts" setup>
  import type { WmsPrintDataVO } from '../print.data';

  defineProps<{ data: WmsPrintDataVO }>();
</script>
