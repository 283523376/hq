<template>
  <div class="wms-sync-snapshot">
    <a-card :bordered="false" style="margin-bottom: 12px">
      <a-form layout="inline" style="row-gap: 8px">
        <a-form-item label="对账批次">
          <a-select
            v-model:value="runId"
            :options="runOptions"
            show-search
            option-filter-prop="label"
            placeholder="选择对账批次"
            allow-clear
            style="width: 420px"
            @change="handleRunChange"
          />
        </a-form-item>
        <a-form-item>
          <a-space>
            <a-button
              type="primary"
              preIcon="ant-design:search-outlined"
              :loading="loading"
              :disabled="!runId"
              @click="handleQuery"
            >
              查询
            </a-button>
            <a-button
              preIcon="ant-design:file-excel-outlined"
              v-auth="'wms:sync:run'"
              :loading="exporting === 'xlsx'"
              :disabled="!runId || !!exporting"
              @click="handleExport('xlsx')"
            >
              导出 Excel
            </a-button>
            <a-button
              preIcon="ant-design:file-text-outlined"
              v-auth="'wms:sync:run'"
              :loading="exporting === 'json'"
              :disabled="!runId || !!exporting"
              @click="handleExport('json')"
            >
              导出 JSON
            </a-button>
          </a-space>
        </a-form-item>
      </a-form>
      <div class="tip">
        待推送包按「仓库 + 物料 + 批次」聚合（库位/容器已聚合掉），导出即交给上位系统的数据包；
        JSON 含批次头与快照、流水两部分。
      </div>
    </a-card>

    <a-card title="待推送包明细" :bordered="false">
      <template #extra>
        <span class="tip">金蝶数量取自即时库存 FBaseQty；未参与比对时为空</span>
      </template>
      <a-table
        :columns="scaledColumns"
        :data-source="records"
        :loading="loading"
        :pagination="pagination"
        row-key="id"
        size="small"
        :scroll="{ x: tableScrollX }"
        :locale="{ emptyText: runId ? '该批次暂无快照数据' : '请先选择对账批次' }"
        @change="handleTableChange"
      />
    </a-card>
  </div>
</template>

<script lang="ts" name="wms-sync-snapshot" setup>
  import { computed, onMounted, ref } from 'vue';
  import { useRoute } from 'vue-router';
  import { scaleScrollX, scaleTableColumns } from '/@/hooks/web/useFontScale';
  import { SYNC_SCOPE_LABEL, snapshotColumns } from './sync.data';
  import { exportSnapshot, pageRuns, pageSnapshots } from './sync.api';
  import { useExportDownload } from './sync.export';

  interface RunOption {
    label: string;
    value: string;
    runNo: string;
  }

  defineOptions({ name: 'WmsSyncSnapshot' });

  const { handleExportResponse } = useExportDownload();
  const route = useRoute();

  const scaledColumns = computed(() => scaleTableColumns(snapshotColumns));
  const tableScrollX = computed(() => scaleScrollX(1600));

  const runOptions = ref<RunOption[]>([]);
  const runId = ref<string | undefined>(undefined);
  const exporting = ref<'' | 'xlsx' | 'json'>('');

  const selectedRun = computed(() => runOptions.value.find((item) => item.value === runId.value));

  async function fetchRunOptions() {
    try {
      const res: any = await pageRuns({ pageNo: 1, pageSize: 100 });
      runOptions.value = (res.records || []).map((r: any) => ({
        label: `${r.runNo}（${SYNC_SCOPE_LABEL[r.scopeType] || r.scopeType} · ${r.status}）`,
        value: r.id,
        runNo: r.runNo,
      }));
      const queryRunId = route.query.runId as string | undefined;
      if (queryRunId) {
        runId.value = queryRunId;
      } else if (runOptions.value.length > 0) {
        runId.value = runOptions.value[0].value;
      }
    } catch (e: any) {
      // defHttp 已统一弹错误提示
    }
  }

  // ---------------------------------------------------------------- 列表
  const loading = ref(false);
  const records = ref<any[]>([]);
  const total = ref(0);
  const pageNo = ref(1);
  const pageSize = ref(20);

  const pagination = computed(() => ({
    current: pageNo.value,
    pageSize: pageSize.value,
    total: total.value,
    showSizeChanger: true,
    pageSizeOptions: ['20', '50', '100'],
    showTotal: (t: number) => `共 ${t} 条`,
  }));

  async function fetchData() {
    if (!runId.value) {
      records.value = [];
      total.value = 0;
      return;
    }
    loading.value = true;
    try {
      const res: any = await pageSnapshots({
        runId: runId.value,
        pageNo: pageNo.value,
        pageSize: pageSize.value,
      });
      records.value = res.records || [];
      total.value = Number(res.total || 0);
    } finally {
      loading.value = false;
    }
  }

  function handleQuery() {
    pageNo.value = 1;
    fetchData();
  }

  function handleRunChange() {
    pageNo.value = 1;
    fetchData();
  }

  function handleTableChange(pag: { current?: number; pageSize?: number }) {
    if (pag.pageSize && pag.pageSize !== pageSize.value) {
      pageSize.value = pag.pageSize;
      pageNo.value = 1;
    } else if (pag.current) {
      pageNo.value = pag.current;
    }
    fetchData();
  }

  // ---------------------------------------------------------------- 导出
  /**
   * 成功/失败只能靠 Content-Disposition 判断：成功导出 JSON 包时 Content-Type 也是
   * application/json，和失败体一模一样。详见 `sync.export.ts`。
   */
  async function handleExport(format: 'xlsx' | 'json') {
    if (!runId.value) {
      return;
    }
    exporting.value = format;
    try {
      const response: any = await exportSnapshot({ runId: runId.value, format });
      const baseName = selectedRun.value?.runNo || 'SB001-待推送包';
      await handleExportResponse(response, `${baseName}-待推送包.${format}`);
    } catch (e: any) {
      // defHttp 已统一弹错误提示
    } finally {
      exporting.value = '';
    }
  }

  onMounted(async () => {
    await fetchRunOptions();
    await fetchData();
  });
</script>

<style lang="less" scoped>
  .tip {
    color: @text-color-secondary;
  }
</style>
