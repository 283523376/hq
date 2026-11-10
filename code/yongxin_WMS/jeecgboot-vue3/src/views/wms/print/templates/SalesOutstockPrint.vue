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
          <div class="sheet-title-rule"></div>
        </div>
        <div class="top-right">
          <div class="meta-item">订单类型：{{ data.headers['订单类型'] }}</div>
          <div class="meta-item">客户订单号：{{ data.headers['客户订单号'] }}</div>
        </div>
      </div>
      <div class="sheet-meta">
        <div class="meta-block meta-left">
          <span class="meta-item">购货单位：{{ data.headers['购货单位'] }}</span>
        </div>
        <div class="meta-block meta-center">
          <span class="meta-item">日期：{{ data.headers['日期'] }}</span>
        </div>
        <div class="meta-block meta-right">
          <span class="meta-item">编　　号：{{ data.erpBillNo }}</span>
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
          <!-- 纸样：合计窄格 + 单位/数量合并且数字右贴格端 + 单价/货款/备注合并 -->
          <tr v-if="pi === data.totalPages - 1" class="total-row">
            <td>合计</td>
            <td colspan="2" class="total-number">{{ data.totalsOnLastPage['数量'] }}</td>
            <td colspan="3">{{ data.totalsOnLastPage['货款'] || '' }}</td>
          </tr>
        </tbody>
      </table>
      <div class="sheet-footers">
        <span v-for="(footer, fi) in data.footers" :key="fi">{{ footer }}</span>
      </div>
      <!-- 公司信息块：左栏（提示/工厂地址/办事处地址）+ 右栏（工厂电话/传真），与样单同高 -->
      <div class="sheet-info-block">
        <div class="info-left">
          <div v-for="(line, li) in infoLeft" :key="li">{{ line }}</div>
        </div>
        <div class="info-right">
          <div v-for="(line, li) in infoRight" :key="li">{{ line }}</div>
        </div>
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
  // 左栏 3 行（提示/工厂地址/办事处地址），右栏 2 行（电话/传真）——与样单布局一致
  const leftCount = computed(() => Number(props.data.extras?.['公司信息块左行数'] ?? 3));
  const infoLeft = computed(() => companyInfo.value.slice(0, leftCount.value));
  const infoRight = computed(() => companyInfo.value.slice(leftCount.value));

  function colWidth(index: number) {
    const widths = ['47%', '6%', '10%', '6%', '8%', '23%'];
    return { width: widths[index] || 'auto' };
  }
</script>

<style scoped>
  .sheet-top {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    gap: 12px;
  }

  .logo-wrap {
    width: 210px;
  }

  /* logo 已裁掉下方公司名，按容器宽自适应（比例约 4.15:1） */
  .logo-wrap .sheet-logo {
    width: 100%;
    height: auto;
    display: block;
  }

  .top-center {
    flex: 1;
    text-align: center;
  }

  .top-center :deep(.sheet-title-rule) {
    width: 52%;
    margin: 0 auto;
  }

  .top-right {
    width: 200px;
    font-size: 12px;
    text-align: left;
  }

  .sheet-info-block {
    display: flex;
    justify-content: space-between;
    margin-top: 10px;
    font-size: 11px;
    line-height: 1.5;
  }

  /* 样单：电话起于页宽 53%、传真起于 81%（两行错位，不是都贴右） */
  .info-left {
    flex: 0 0 auto;
  }

  .info-right {
    flex: 0 0 47%;
    text-align: left;
    white-space: nowrap;
    padding-top: 1.5em;
  }

  .info-right > div:last-child {
    padding-left: 60%;
  }
  /* 销售出库单五栏落款：纸样 4% / 38% / 54% / 72% / 95% */
  :deep(.sheet-footers) {
    padding: 0 5% 0 4%;
  }
  .total-number {
    text-align: right;
    padding-right: 10px;
  }
</style>