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
        <a-button-group>
          <a-button
            v-for="opt in PERIOD_OPTIONS"
            :key="opt.value"
            :type="period === opt.value ? 'primary' : 'default'"
            @click="onPeriodChange(opt.value)"
          >
            {{ opt.label }}
          </a-button>
        </a-button-group>
        <a-input-search
          v-model:value="keyword"
          :placeholder="searchPlaceholder"
          allow-clear
          style="width: 300px"
          @search="loadList(1)"
        />
        <span class="count">共 {{ total > 0 ? total : '—' }} 条，第 {{ pageNo }} 页</span>
        <a-button :disabled="pageNo <= 1" @click="loadList(pageNo - 1)">上一页</a-button>
        <a-button :disabled="!hasMore" @click="loadList(pageNo + 1)">下一页</a-button>
        <a-divider type="vertical" />
        <a-switch v-model:checked="editing" :disabled="!data" checked-children="编辑中" un-checked-children="只读" />
        <a-button :disabled="!data || !editing" @click="handleRestore">还原内容</a-button>
        <a-button type="primary" :disabled="!data" @click="handlePrint">打印</a-button>
      </div>
      <div v-if="editing" class="edit-tip">
        可直接在下方预览中修改文字（表头、表格内容、签名、公司信息），<b>修改仅作用于本次打印，不会保存到单据、也不会回写金蝶</b>。
      </div>

      <a-table
        :data-source="records"
        :columns="listColumns"
        :loading="listLoading"
        :pagination="false"
        row-key="billNo"
        size="small"
        :custom-row="customRow"
        :row-class-name="(record: any) => (record.billNo === selectedNo ? 'row-selected' : '')"
        :scroll="{ y: 280 }"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'action'">
            <a @click.stop="handlePreview(record.billNo)">预览</a>
          </template>
        </template>
      </a-table>
    </a-card>

    <a-spin :spinning="previewLoading">
      <div v-if="data" class="preview-wrap">
        <PrintPreview :data="data" :editable="editing" />
      </div>
      <a-empty v-else description="点击上方列表中的单据进行预览" />
    </a-spin>
  </div>
</template>

<script lang="ts" setup>
  import { computed, onMounted, ref } from 'vue';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { PRINT_TABS, SEARCH_PLACEHOLDERS, type WmsPrintDataVO } from './print.data';
  import { getBills, getPrintData } from './print.api';
  import PrintPreview from './PrintPreview.vue';
  import './print.css';

  const { createMessage } = useMessage();

  const PAGE_SIZE = 20;

  const PERIOD_OPTIONS = [
    { value: 'TODAY', label: '今天' },
    { value: 'WEEK', label: '本周' },
    { value: 'MONTH', label: '本月' },
    { value: 'ALL', label: '全部' },
  ];

  const activeType = ref<string>(PRINT_TABS.find((t) => t.enabled)!.type);
  const keyword = ref('');
  const period = ref<string>('ALL');
  const pageNo = ref(1);
  const total = ref(-1);
  const hasMore = ref(false);
  const records = ref<any[]>([]);
  const listLoading = ref(false);
  const previewLoading = ref(false);
  const data = ref<WmsPrintDataVO | null>(null);
  const selectedNo = ref('');
  const editing = ref(false);

  const searchPlaceholder = computed(() => SEARCH_PLACEHOLDERS[activeType.value] || '按单号模糊搜索');

  const listColumns = [
    { title: '单据号', dataIndex: 'billNo', key: 'billNo', width: 320 },
    { title: '日期', dataIndex: 'date', key: 'date', width: 130 },
    { title: '状态', dataIndex: 'status', key: 'status', width: 130 },
    { title: '操作', key: 'action', width: 90 },
  ];

  onMounted(() => loadList(1));

  function onTabChange() {
    keyword.value = '';
    data.value = null;
    selectedNo.value = '';
    editing.value = false;
    loadList(1);
  }

  function onPeriodChange(value: string) {
    period.value = value;
    loadList(1);
  }

  async function loadList(page: number) {
    const p = Math.max(1, page);
    listLoading.value = true;
    try {
      const res: any = await getBills({
        billType: activeType.value,
        keyword: (keyword.value || '').trim(),
        period: period.value,
        pageNo: p,
        pageSize: PAGE_SIZE,
      });
      records.value = res?.records || [];
      total.value = res?.total ?? -1;
      hasMore.value = !!res?.hasMore;
      pageNo.value = res?.pageNo || p;
    } catch (e: any) {
      records.value = [];
      total.value = 0;
      hasMore.value = false;
      createMessage.error(e?.message || '单据列表加载失败');
    } finally {
      listLoading.value = false;
    }
  }

  function customRow(record: any) {
    return {
      onClick: () => handlePreview(record.billNo),
      style: { cursor: 'pointer' },
    };
  }

  async function handlePreview(billNo: string) {
    if (!billNo) return;
    selectedNo.value = billNo;
    previewLoading.value = true;
    editing.value = false;
    try {
      data.value = await getPrintData({ billType: activeType.value, billNo });
    } catch (e: any) {
      data.value = null;
      createMessage.error(e?.message || '查询失败');
    } finally {
      previewLoading.value = false;
    }
  }

  /** 还原：重新拉取原始数据（丢弃手工修改） */
  async function handleRestore() {
    if (!selectedNo.value) return;
    await handlePreview(selectedNo.value);
    createMessage.success('已还原为单据原始内容');
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
    flex-wrap: wrap;
  }

  .count {
    color: #666;
    font-size: 12px;
  }

  .edit-tip {
    margin-bottom: 8px;
    padding: 6px 10px;
    background: #fffbe6;
    border: 1px solid #ffe58f;
    border-radius: 4px;
    font-size: 12px;
    color: #614700;
  }

  .preview-wrap {
    margin-top: 16px;
    padding: 16px;
    background: #fff;
    overflow-x: auto;
  }

  :deep(.row-selected) > td {
    background: #e6f4ff !important;
  }
</style>
