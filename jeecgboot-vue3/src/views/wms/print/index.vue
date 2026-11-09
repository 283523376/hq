<template>
  <div class="p-4 print-workbench">
    <a-card :bordered="false" class="no-print">
      <a-tabs v-model:activeKey="activeType" @change="onTabChange">
        <a-tab-pane v-for="tab in PRINT_TABS" :key="tab.type" :disabled="!tab.enabled">
          <template #tab>
            <a-tooltip v-if="!tab.enabled" title="打印样式待定，暂不能打印">
              <span>{{ tab.label }}（待定）</span>
            </a-tooltip>
            <span v-else>{{ tab.label }}</span>
          </template>
        </a-tab-pane>
      </a-tabs>

      <div class="toolbar">
        <a-auto-complete
          v-model:value="billNo"
          :options="localOptions"
          :placeholder="placeholder"
          allow-clear
          style="width: 360px"
          @search="onSearch"
          @select="onSelect"
        />
        <a-button type="primary" :loading="loading" @click="handleQuery">查询预览</a-button>
        <a-button :disabled="!data" @click="handlePrint">打印</a-button>
      </div>
    </a-card>

    <a-spin :spinning="loading">
      <div v-if="data" class="preview-wrap">
        <PrintPreview :data="data" />
      </div>
      <a-empty v-else description="选择单据类型与单号后查询预览" />
    </a-spin>
  </div>
</template>

<script lang="ts" setup>
  import { computed, onMounted, ref } from 'vue';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { PRINT_TABS, BILL_NO_HINTS, type WmsPrintDataVO } from './print.data';
  import { getPrintData, getLocalBills } from './print.api';
  import PrintPreview from './PrintPreview.vue';
  import './print.css';

  const { createMessage } = useMessage();

  const activeType = ref<string>(PRINT_TABS.find((t) => t.enabled)!.type);
  const billNo = ref<string>('');
  const data = ref<WmsPrintDataVO | null>(null);
  const loading = ref(false);
  const localOptions = ref<{ value: string; label: string }[]>([]);

  const currentTab = computed(() => PRINT_TABS.find((t) => t.type === activeType.value)!);
  const placeholder = computed(() => BILL_NO_HINTS[activeType.value] || '输入单据号');

  onMounted(() => {
    loadLocalBills('');
  });

  function onTabChange() {
    billNo.value = '';
    data.value = null;
    localOptions.value = [];
    loadLocalBills('');
  }

  /** 本地类型（盘盈亏/销售出库）才拉下拉；5 类金蝶单据直接手输单号 */
  async function loadLocalBills(keyword: string) {
    if (currentTab.value.source !== 'local') {
      localOptions.value = [];
      return;
    }
    try {
      const list = (await getLocalBills({ billType: activeType.value, keyword })) || [];
      localOptions.value = list.map((item: any) => ({
        value: item.billNo,
        label: item.printable === '1' ? item.label : `${item.label} — 未回写金蝶`,
      }));
    } catch (e: any) {
      localOptions.value = [];
      createMessage.warn(e?.message || '本地单号加载失败');
    }
  }

  let searchTimer: any = null;
  function onSearch(value: string) {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => loadLocalBills(value), 300);
  }

  function onSelect(value: string) {
    billNo.value = value;
  }

  async function handleQuery() {
    const no = (billNo.value || '').trim();
    if (!no) {
      createMessage.warning('请先选择或输入单据号');
      return;
    }
    loading.value = true;
    try {
      data.value = await getPrintData({ billType: activeType.value, billNo: no });
    } catch (e: any) {
      data.value = null;
      createMessage.error(e?.message || '查询失败');
    } finally {
      loading.value = false;
    }
  }

  function handlePrint() {
    if (!data.value) return;
    window.print();
  }
</script>

<style scoped>
  .toolbar {
    display: flex;
    gap: 12px;
    align-items: center;
    margin-bottom: 8px;
  }

  .preview-wrap {
    margin-top: 16px;
    padding: 16px;
    background: #fff;
    overflow-x: auto;
  }
</style>
