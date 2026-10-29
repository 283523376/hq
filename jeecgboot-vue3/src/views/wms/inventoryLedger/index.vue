<template>
  <div class="wms-inventory-ledger">
    <!-- 查询条件 -->
    <a-card :bordered="false" style="margin-bottom: 12px">
      <a-form layout="inline" style="row-gap: 8px">
        <a-form-item label="物料">
          <a-input
            v-model:value="query.materialKeyword"
            placeholder="编码或名称"
            allow-clear
            style="width: 180px"
            @press-enter="handleQuery"
          />
        </a-form-item>
        <a-form-item label="批次号">
          <a-input v-model:value="query.batchNo" allow-clear style="width: 160px" @press-enter="handleQuery" />
        </a-form-item>
        <a-form-item label="库位">
          <a-input v-model:value="query.locationCode" allow-clear style="width: 160px" @press-enter="handleQuery" />
        </a-form-item>
        <a-form-item label="仓库">
          <a-select
            v-model:value="query.warehouseId"
            :options="warehouseOptions"
            placeholder="全部仓库"
            allow-clear
            style="width: 220px"
          />
        </a-form-item>
        <a-form-item>
          <a-checkbox v-model:checked="query.onlyPositive">只看有库存</a-checkbox>
        </a-form-item>
        <a-form-item>
          <a-space>
            <a-button type="primary" preIcon="ant-design:search-outlined" v-auth="'wms:inventory:ledger'" :loading="loading" @click="handleQuery">
              查询
            </a-button>
            <a-button preIcon="ant-design:reload-outlined" @click="handleReset">重置</a-button>
            <a-button preIcon="ant-design:download-outlined" v-auth="'wms:inventory:export'" :loading="exporting" @click="handleExport">
              导出 Excel
            </a-button>
          </a-space>
        </a-form-item>
      </a-form>
    </a-card>

    <!-- 库存明细 -->
    <a-card title="库存明细" :bordered="false" style="margin-bottom: 12px">
      <a-table
        :columns="ledgerColumns"
        :data-source="records"
        :loading="loading"
        :pagination="ledgerPagination"
        row-key="id"
        size="small"
        :scroll="{ x: 1800 }"
        :locale="{ emptyText: queried ? '未找到匹配的库存记录' : '请设置查询条件后点击查询' }"
        @change="handleTableChange"
      />
    </a-card>

    <!-- 我的导出任务 -->
    <a-card
      title="我的导出任务"
      :bordered="false"
    >
      <template #extra>
        <span style="color: #8c8c8c; font-size: 12px">文件保留 7 天，到期自动清理；仅本人可见</span>
      </template>
      <a-table
        :columns="taskColumns"
        :data-source="taskRecords"
        :loading="taskLoading"
        :pagination="taskPagination"
        row-key="id"
        size="small"
        :scroll="{ x: 1300 }"
        :locale="{ emptyText: '暂无导出任务' }"
        @change="handleTaskTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'action'">
            <a-space>
              <a-tooltip v-if="isExpired(record)" title="文件已过期（7 天）">
                <a-button size="small" disabled>下载</a-button>
              </a-tooltip>
              <a-button
                v-else
                size="small"
                type="primary"
                ghost
                :disabled="record.status !== 'SUCCESS'"
                @click="handleDownload(record)"
              >
                下载
              </a-button>
              <a-button
                v-if="record.status === 'FAILED'"
                size="small"
                @click="handleRetry(record)"
              >
                重试
              </a-button>
              <a-popconfirm
                v-if="record.status === 'SUCCESS' || record.status === 'FAILED'"
                title="删除后文件也一并删除，确定？"
                @confirm="handleDeleteTask(record)"
              >
                <a-button size="small" danger>删除</a-button>
              </a-popconfirm>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-card>
  </div>
</template>

<script lang="ts" setup>
  import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { useMethods } from '/@/hooks/system/useMethods';
  import { Modal } from 'ant-design-vue';
  import {
    createExportTask,
    deleteTask,
    listWarehouseOptions,
    queryLedger,
    queryTaskPage,
    retryTask,
  } from './inventoryLedger.api';
  import { ledgerColumns, taskColumns } from './inventoryLedger.data';

  const { createMessage } = useMessage();
  const { handleExportXlsx } = useMethods();

  // ---------- 库存明细 ----------
  const query = reactive({
    materialKeyword: '',
    batchNo: '',
    locationCode: '',
    warehouseId: undefined as string | undefined,
    onlyPositive: true,
  });
  const queried = ref(false);
  const loading = ref(false);
  const exporting = ref(false);
  const records = ref<any[]>([]);
  const total = ref(0);
  const pageNo = ref(1);
  const pageSize = ref(20);

  const ledgerPagination = computed(() => ({
    current: pageNo.value,
    pageSize: pageSize.value,
    total: total.value,
    showSizeChanger: true,
    pageSizeOptions: ['20', '50', '100'],
    showTotal: (t: number) => `共 ${t} 条`,
  }));

  async function fetchLedger() {
    loading.value = true;
    try {
      const res = await queryLedger({
        materialKeyword: query.materialKeyword || undefined,
        batchNo: query.batchNo || undefined,
        locationCode: query.locationCode || undefined,
        warehouseId: query.warehouseId || undefined,
        onlyPositive: query.onlyPositive,
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
    queried.value = true;
    fetchLedger();
  }

  function handleReset() {
    query.materialKeyword = '';
    query.batchNo = '';
    query.locationCode = '';
    query.warehouseId = undefined;
    query.onlyPositive = true;
    pageNo.value = 1;
    handleQuery();
  }

  function handleTableChange(pag: { current?: number; pageSize?: number }) {
    if (pag.pageSize && pag.pageSize !== pageSize.value) {
      pageSize.value = pag.pageSize;
      pageNo.value = 1;
    } else if (pag.current) {
      pageNo.value = pag.current;
    }
    fetchLedger();
  }

  // ---------- 导出与任务列表 ----------
  const taskLoading = ref(false);
  const taskRecords = ref<any[]>([]);
  const taskTotal = ref(0);
  const taskPageNo = ref(1);
  const taskPageSize = ref(10);
  let pollTimer: ReturnType<typeof setInterval> | null = null;

  const taskPagination = computed(() => ({
    current: taskPageNo.value,
    pageSize: taskPageSize.value,
    total: taskTotal.value,
    showSizeChanger: false,
    showTotal: (t: number) => `共 ${t} 条`,
  }));

  async function fetchTasks() {
    taskLoading.value = true;
    try {
      const res = await queryTaskPage({ pageNo: taskPageNo.value, pageSize: taskPageSize.value });
      taskRecords.value = res.records || [];
      taskTotal.value = Number(res.total || 0);
      syncPolling();
    } finally {
      taskLoading.value = false;
    }
  }

  /** 有排队/生成中的任务才轮询；全部终态时主动停 */
  function syncPolling() {
    const busy = taskRecords.value.some((r) => r.status === 'PENDING' || r.status === 'RUNNING');
    if (busy && pollTimer === null) {
      pollTimer = setInterval(() => fetchTasks(), 5000);
    } else if (!busy && pollTimer !== null) {
      clearInterval(pollTimer);
      pollTimer = null;
    }
  }

  function handleTaskTableChange(pag: { current?: number }) {
    if (pag.current) {
      taskPageNo.value = pag.current;
    }
    fetchTasks();
  }

  async function handleExport() {
    exporting.value = true;
    try {
      await createExportTask({
        materialKeyword: query.materialKeyword || undefined,
        batchNo: query.batchNo || undefined,
        locationCode: query.locationCode || undefined,
        warehouseId: query.warehouseId || undefined,
        onlyPositive: query.onlyPositive,
      });
      createMessage.success('导出任务已提交，可在下方任务列表查看进度');
      taskPageNo.value = 1;
      fetchTasks();
    } catch (e: any) {
      // defHttp 已统一弹错误提示；这里兜住不抛
    } finally {
      exporting.value = false;
    }
  }

  function handleDownload(record: any) {
    // handleExportXlsx 会自己补 .xlsx 后缀，这里传去掉后缀的名字，否则下载出来是 .xlsx.xlsx
    const name = (record.fileName || '库存台账').replace(/\.xlsx$/i, '');
    handleExportXlsx(name, '/wms/inventoryLedger/export/task/' + record.id + '/download');
  }

  async function handleRetry(record: any) {
    await retryTask(record.id);
    createMessage.success('已重新提交');
    fetchTasks();
  }

  function handleDeleteTask(record: any) {
    Modal.confirm({
      title: '删除导出任务',
      content: `确定删除「${record.fileName || '该任务'}」？生成的文件也会一并删除。`,
      onOk: async () => {
        await deleteTask(record.id);
        createMessage.success('已删除');
        fetchTasks();
      },
    });
  }

  function isExpired(record: any): boolean {
    if (record.status !== 'SUCCESS' || !record.expireTime) {
      return false;
    }
    return new Date(record.expireTime.replace(/-/g, '/')).getTime() < Date.now();
  }

  // ---------- 仓库下拉（含停用仓） ----------
  const warehouseOptions = ref<{ label: string; value: string }[]>([]);

  async function fetchWarehouseOptions() {
    const res = await listWarehouseOptions({ pageNo: 1, pageSize: 500 });
    warehouseOptions.value = (res.records || []).map((w: any) => ({
      label: w.warehouseCode + ' ' + w.warehouseName + (w.status === '0' ? '（停用）' : ''),
      value: w.id,
    }));
  }

  onMounted(() => {
    queried.value = true;
    fetchLedger();
    fetchTasks();
    fetchWarehouseOptions();
  });

  onUnmounted(() => {
    if (pollTimer !== null) {
      clearInterval(pollTimer);
      pollTimer = null;
    }
  });
</script>
