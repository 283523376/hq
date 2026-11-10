<template>
  <div class="wms-batch-trace">
    <a-card :bordered="false" style="margin-bottom: 12px">
      <a-space>
        <a-input
          v-model:value="batchNo"
          placeholder="输入批次号，回车查询"
          allow-clear
          style="width: 320px"
          @press-enter="handleQuery"
        />
        <a-button type="primary" preIcon="ant-design:search-outlined" v-auth="'wms:inventory:batch'" :loading="loading" @click="handleQuery">
          查询
        </a-button>
      </a-space>
      <span v-if="queried" style="margin-left: 16px; color: #8c8c8c">
        批次 {{ queriedBatch }}：库存 {{ stockRows.length }} 行，流转 {{ flowRows.length }} 条
      </span>
    </a-card>

    <a-card title="当前库存明细" :bordered="false" style="margin-bottom: 12px">
      <a-table
        :columns="stockColumns"
        :data-source="stockRows"
        :loading="loading"
        :pagination="false"
        row-key="id"
        size="small"
        :scroll="{ x: 1100 }"
        :locale="{ emptyText: queried ? '该批次当前无库存' : '请输入批次号查询' }"
      />
    </a-card>

    <a-card title="流转记录" :bordered="false">
      <a-table
        :columns="flowColumns"
        :data-source="flowRows"
        :loading="loading"
        :pagination="{ pageSize: 20, showSizeChanger: true }"
        row-key="rowKey"
        size="small"
        :scroll="{ x: 1500 }"
        :locale="{ emptyText: queried ? '该批次暂无流转记录' : '请输入批次号查询' }"
      />
    </a-card>
  </div>
</template>

<script lang="ts" setup>
  import { ref } from 'vue';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { getBatchFlow, getBatchStock } from './inventory.api';
  import { flowColumns, stockColumns } from './inventory.data';

  const { createMessage } = useMessage();
  const batchNo = ref('');
  const queriedBatch = ref('');
  const queried = ref(false);
  const loading = ref(false);
  const stockRows = ref<any[]>([]);
  const flowRows = ref<any[]>([]);

  async function handleQuery() {
    const keyword = batchNo.value?.trim();
    if (!keyword) {
      createMessage.warning('请输入批次号');
      return;
    }
    loading.value = true;
    try {
      const [stock, flow]: any[] = await Promise.all([getBatchStock(keyword), getBatchFlow(keyword, 500)]);
      stockRows.value = stock || [];
      // 流转记录没有天然主键：拼一个行键，避免同单据同时间两条时 Vue 复用错行
      flowRows.value = (flow || []).map((row, index) => ({ ...row, rowKey: `${row.eventType}-${row.billNo}-${index}` }));
      queriedBatch.value = keyword;
      queried.value = true;
    } finally {
      loading.value = false;
    }
  }
</script>

<style lang="less" scoped>
  .wms-batch-trace {
    padding: 8px;
  }
</style>
