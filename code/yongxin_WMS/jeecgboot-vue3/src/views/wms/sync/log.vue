<template>
  <div class="wms-sync-log">
    <a-card :bordered="false" style="margin-bottom: 12px">
      <a-form layout="inline" style="row-gap: 8px">
        <a-form-item label="方向">
          <a-select
            v-model:value="query.direction"
            :options="SYNC_LOG_DIRECTION_OPTIONS"
            placeholder="全部"
            allow-clear
            style="width: 150px"
          />
        </a-form-item>
        <a-form-item label="对象类型">
          <a-select
            v-model:value="query.objectType"
            :options="SYNC_LOG_OBJECT_TYPE_OPTIONS"
            placeholder="全部"
            show-search
            option-filter-prop="label"
            allow-clear
            style="width: 170px"
          />
        </a-form-item>
        <a-form-item label="结果">
          <a-select
            v-model:value="query.status"
            :options="SYNC_LOG_STATUS_OPTIONS"
            placeholder="全部"
            allow-clear
            style="width: 120px"
          />
        </a-form-item>
        <a-form-item label="业务单号">
          <a-input
            v-model:value="query.objectNo"
            placeholder="支持模糊"
            allow-clear
            style="width: 180px"
            @press-enter="handleQuery"
          />
        </a-form-item>
        <a-form-item label="发生时间">
          <a-range-picker
            v-model:value="timeRange"
            show-time
            format="YYYY-MM-DD HH:mm:ss"
            :placeholder="['开始时间', '结束时间']"
            style="width: 360px"
          />
        </a-form-item>
        <a-form-item>
          <a-space>
            <a-button
              type="primary"
              preIcon="ant-design:search-outlined"
              v-auth="'wms:sync:log:view'"
              :loading="loading"
              @click="handleQuery"
            >
              查询
            </a-button>
            <a-button preIcon="ant-design:reload-outlined" @click="handleReset">重置</a-button>
          </a-space>
        </a-form-item>
      </a-form>
    </a-card>

    <a-card title="同步日志" :bordered="false">
      <template #extra>
        <span class="tip">报文已脱敏并截断至 8KB；点「查看报文」展开请求/响应</span>
      </template>
      <a-table
        :columns="scaledColumns"
        :data-source="records"
        :loading="loading"
        :pagination="pagination"
        row-key="id"
        size="small"
        :scroll="{ x: tableScrollX }"
        :locale="{ emptyText: '暂无同步日志' }"
        @change="handleTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'action'">
            <a-button type="link" size="small" @click="openPayload(record)">查看报文</a-button>
          </template>
        </template>
      </a-table>
    </a-card>

    <!-- 报文抽屉 -->
    <a-drawer
      v-model:open="payloadOpen"
      title="同步报文"
      width="820"
      placement="right"
      :destroy-on-close="true"
    >
      <a-descriptions bordered size="small" :column="2">
        <a-descriptions-item label="发生时间">{{ current?.createTime || '—' }}</a-descriptions-item>
        <a-descriptions-item label="方向">
          {{ SYNC_LOG_DIRECTION_LABEL[current?.direction] || current?.direction || '—' }}
        </a-descriptions-item>
        <a-descriptions-item label="对象类型">{{ current?.objectType || '—' }}</a-descriptions-item>
        <a-descriptions-item label="业务单号">{{ current?.objectNo || '—' }}</a-descriptions-item>
        <a-descriptions-item label="对端">{{ current?.target || '—' }}</a-descriptions-item>
        <a-descriptions-item label="结果">{{ current?.status || '—' }}</a-descriptions-item>
        <a-descriptions-item label="连续失败次数">{{ current?.retryCount ?? 0 }}</a-descriptions-item>
        <a-descriptions-item label="耗时(ms)">{{ current?.costMs ?? '—' }}</a-descriptions-item>
      </a-descriptions>

      <a-alert
        v-if="current?.errorMessage"
        type="error"
        show-icon
        style="margin-top: 12px"
        :message="current?.errorMessage"
      />

      <a-divider orientation="left">请求报文</a-divider>
      <pre class="payload">{{ prettyPayload(current?.requestPayload) }}</pre>

      <a-divider orientation="left">响应报文</a-divider>
      <pre class="payload">{{ prettyPayload(current?.responsePayload) }}</pre>
    </a-drawer>
  </div>
</template>

<script lang="ts" name="wms-sync-log" setup>
  import { computed, onMounted, reactive, ref } from 'vue';
  import dayjs from 'dayjs';
  import { scaleScrollX, scaleTableColumns } from '/@/hooks/web/useFontScale';
  import {
    SYNC_LOG_DIRECTION_LABEL,
    SYNC_LOG_DIRECTION_OPTIONS,
    SYNC_LOG_OBJECT_TYPE_OPTIONS,
    SYNC_LOG_STATUS_OPTIONS,
    logColumns,
  } from './sync.data';
  import { pageLogs } from './sync.api';

  defineOptions({ name: 'WmsSyncLog' });

  const scaledColumns = computed(() => scaleTableColumns(logColumns));
  const tableScrollX = computed(() => scaleScrollX(1400));

  const query = reactive({
    direction: undefined as string | undefined,
    objectType: undefined as string | undefined,
    status: undefined as string | undefined,
    objectNo: '',
  });
  const timeRange = ref<any>(undefined);

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
    loading.value = true;
    try {
      const params: any = {
        direction: query.direction || undefined,
        objectType: query.objectType || undefined,
        status: query.status || undefined,
        objectNo: query.objectNo || undefined,
        pageNo: pageNo.value,
        pageSize: pageSize.value,
      };
      if (Array.isArray(timeRange.value) && timeRange.value.length === 2) {
        params.startTime = dayjs(timeRange.value[0]).format('YYYY-MM-DD HH:mm:ss');
        params.endTime = dayjs(timeRange.value[1]).format('YYYY-MM-DD HH:mm:ss');
      }
      const res: any = await pageLogs(params);
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

  function handleReset() {
    query.direction = undefined;
    query.objectType = undefined;
    query.status = undefined;
    query.objectNo = '';
    timeRange.value = undefined;
    handleQuery();
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

  // ---------------------------------------------------------------- 报文
  const payloadOpen = ref(false);
  const current = ref<any>(null);

  function openPayload(record: any) {
    current.value = record;
    payloadOpen.value = true;
  }

  /** 能 JSON 化就格式化展示，否则原样（后端报文可能是拼接字符串） */
  function prettyPayload(payload: any): string {
    if (payload === null || payload === undefined || payload === '') {
      return '—';
    }
    try {
      return JSON.stringify(JSON.parse(payload), null, 2);
    } catch {
      return String(payload);
    }
  }

  onMounted(() => {
    fetchData();
  });
</script>

<style lang="less" scoped>
  .tip {
    color: @text-color-secondary;
  }

  .payload {
    max-height: 320px;
    overflow: auto;
    padding: 12px;
    margin: 0;
    background: @background-color-light;
    border: 1px solid @border-color-split;
    border-radius: 4px;
    font-size: 12px;
    white-space: pre-wrap;
    word-break: break-all;
  }
</style>
