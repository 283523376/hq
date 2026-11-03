<template>
  <div class="wms-sync-run">
    <!-- 执行对账 -->
    <a-card :bordered="false" style="margin-bottom: 12px">
      <a-form layout="inline" style="row-gap: 8px">
        <a-form-item label="对账范围">
          <a-select v-model:value="scopeType" :options="SYNC_SCOPE_OPTIONS" style="width: 150px" />
        </a-form-item>
        <a-form-item v-if="scopeType === 'WAREHOUSE'" label="指定仓库">
          <a-select
            v-model:value="warehouseCodes"
            mode="multiple"
            :options="warehouseOptions"
            :max-tag-count="3"
            placeholder="选择要参与对账的仓库（可多选）"
            allow-clear
            style="min-width: 360px"
          />
        </a-form-item>
        <a-form-item>
          <a-button
            type="primary"
            preIcon="ant-design:play-circle-outlined"
            v-auth="'wms:sync:run'"
            :loading="running"
            @click="handleRun"
          >
            执行对账
          </a-button>
        </a-form-item>
        <a-form-item label="批次状态">
          <a-select
            v-model:value="statusFilter"
            :options="SYNC_RUN_STATUS_OPTIONS"
            placeholder="全部"
            allow-clear
            style="width: 120px"
            @change="handleQuery"
          />
        </a-form-item>
        <a-form-item>
          <a-button preIcon="ant-design:reload-outlined" :loading="loading" @click="handleQuery">刷新</a-button>
        </a-form-item>
      </a-form>
      <a-alert type="info" show-icon style="margin-top: 12px">
        <template #message>对账口径</template>
        <template #description>
          全量库存 = 当前时点全部 WMS 库存与金蝶即时库存比对；今日变动 = 当天 00:00 至当前（不产生「仅ERP」）；
          指定仓库 = 只比对所选仓库的全量库存。批次异步执行，执行中的行每 3 秒自动刷新状态。
        </template>
      </a-alert>
    </a-card>

    <!-- 批次列表 -->
    <a-card title="对账批次" :bordered="false">
      <template #extra>
        <span class="tip">差异不是故障，只是账实不一致的信号；点「差异」查看并处理</span>
      </template>
      <a-table
        :columns="scaledRunColumns"
        :data-source="records"
        :loading="loading"
        :pagination="pagination"
        row-key="id"
        size="small"
        :scroll="{ x: runScrollX }"
        :locale="{ emptyText: '暂无对账批次，选择范围后点「执行对账」' }"
        @change="handleTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'action'">
            <a-space>
              <a-button type="link" size="small" @click="goPage('/wms/sync/snapshot', record)">待推送包</a-button>
              <a-button type="link" size="small" @click="goPage('/wms/sync/diff', record)">差异</a-button>
              <a-button type="link" size="small" @click="goPage('/wms/sync/flow', record)">流水</a-button>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-card>
  </div>
</template>

<script lang="ts" name="wms-sync-run" setup>
  import { computed, onMounted, onUnmounted, ref } from 'vue';
  import { useRouter } from 'vue-router';
  import { Modal } from 'ant-design-vue';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { scaleScrollX, scaleTableColumns } from '/@/hooks/web/useFontScale';
  import { SYNC_RUN_STATUS_OPTIONS, SYNC_SCOPE_OPTIONS, runColumns } from './sync.data';
  import { createRun, getRun, listWarehouseOptions, pageRuns } from './sync.api';

  defineOptions({ name: 'WmsSyncRunIndex' });

  const { createMessage } = useMessage();
  const router = useRouter();

  const scaledRunColumns = computed(() => scaleTableColumns(runColumns));
  const runScrollX = computed(() => scaleScrollX(1600));

  // ---------------------------------------------------------------- 执行对账
  const scopeType = ref('TODAY');
  const warehouseCodes = ref<string[]>([]);
  const warehouseOptions = ref<{ label: string; value: string }[]>([]);
  const running = ref(false);

  async function fetchWarehouseOptions() {
    try {
      const res: any = await listWarehouseOptions({ pageNo: 1, pageSize: 500 });
      warehouseOptions.value = (res.records || []).map((w: any) => ({
        label: `${w.warehouseCode} ${w.warehouseName}${w.status === '0' ? '（停用）' : ''}`,
        value: w.warehouseCode,
      }));
    } catch (e: any) {
      // defHttp 已统一弹错误提示
    }
  }

  function handleRun() {
    if (scopeType.value === 'WAREHOUSE' && warehouseCodes.value.length === 0) {
      createMessage.warning('「指定仓库」范围至少要选一个仓库');
      return;
    }
    const scopeHint =
      scopeType.value === 'FULL'
        ? '全量库存：汇总 WMS 全部库存并与金蝶即时库存全量比对，耗时长。'
        : scopeType.value === 'TODAY'
          ? '今日变动：只比对当天 00:00 至当前的库存变动，不产生「仅ERP」。'
          : `指定仓库：只比对所选 ${warehouseCodes.value.length} 个仓库的全量库存。`;

    Modal.confirm({
      title: '执行对账确认',
      content: `${scopeHint}对账批次异步执行，可在下方批次列表查看进度。`,
      okText: '开始对账',
      cancelText: '取消',
      onOk: async () => {
        running.value = true;
        try {
          const runId: any = await createRun({
            scopeType: scopeType.value,
            warehouseCodes: scopeType.value === 'WAREHOUSE' ? warehouseCodes.value : undefined,
          });
          createMessage.success(`对账批次已创建：${runId}`);
          pageNo.value = 1;
          await fetchRuns();
        } catch (e: any) {
          // defHttp 已统一弹错误提示；兜住避免未处理的 rejection
        } finally {
          running.value = false;
        }
      },
    });
  }

  // ---------------------------------------------------------------- 批次列表
  const loading = ref(false);
  const records = ref<any[]>([]);
  const total = ref(0);
  const pageNo = ref(1);
  const pageSize = ref(10);
  const statusFilter = ref<string | undefined>(undefined);

  const pagination = computed(() => ({
    current: pageNo.value,
    pageSize: pageSize.value,
    total: total.value,
    showSizeChanger: true,
    pageSizeOptions: ['10', '20', '50'],
    showTotal: (t: number) => `共 ${t} 条`,
  }));

  async function fetchRuns() {
    loading.value = true;
    try {
      const res: any = await pageRuns({
        pageNo: pageNo.value,
        pageSize: pageSize.value,
        status: statusFilter.value || undefined,
      });
      records.value = res.records || [];
      total.value = Number(res.total || 0);
      syncPolling();
    } finally {
      loading.value = false;
    }
  }

  function handleQuery() {
    pageNo.value = 1;
    fetchRuns();
  }

  function handleTableChange(pag: { current?: number; pageSize?: number }) {
    if (pag.pageSize && pag.pageSize !== pageSize.value) {
      pageSize.value = pag.pageSize;
      pageNo.value = 1;
    } else if (pag.current) {
      pageNo.value = pag.current;
    }
    fetchRuns();
  }

  function goPage(path: string, record: any) {
    router.push({ path, query: { runId: record.id } });
  }

  // ---------------------------------------------------------------- 执行中轮询
  let pollTimer: ReturnType<typeof setInterval> | null = null;
  let polling = false;

  /** 有「执行中」行才起定时器；全部终态时主动停 */
  function syncPolling() {
    const busy = records.value.some((r) => r.status === '执行中');
    if (busy && pollTimer === null) {
      pollTimer = setInterval(pollRunning, 3000);
    } else if (!busy) {
      stopPolling();
    }
  }

  /** 逐行轮询 run/{id}，把最新状态并回当前行（不重拉整页，避免滚动位置/页码跳动） */
  async function pollRunning() {
    if (polling) {
      return;
    }
    const runningRows = records.value.filter((r) => r.status === '执行中');
    if (runningRows.length === 0) {
      stopPolling();
      return;
    }
    polling = true;
    try {
      await Promise.all(
        runningRows.map(async (row: any) => {
          try {
            const detail: any = await getRun(row.id);
            if (detail) {
              Object.assign(row, detail);
            }
          } catch (e: any) {
            // 单行查询失败不打断其它行；下一轮继续
          }
        }),
      );
    } finally {
      polling = false;
    }
    if (!records.value.some((r) => r.status === '执行中')) {
      stopPolling();
    }
  }

  function stopPolling() {
    if (pollTimer !== null) {
      clearInterval(pollTimer);
      pollTimer = null;
    }
  }

  onMounted(() => {
    fetchWarehouseOptions();
    fetchRuns();
  });

  onUnmounted(() => {
    stopPolling();
  });
</script>

<style lang="less" scoped>
  .tip {
    color: @text-color-secondary;
  }
</style>
