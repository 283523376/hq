<template>
  <div class="wms-work-order">
    <BasicTable @register="registerTable">
      <template #tableTitle>
        <a-button type="primary" ghost preIcon="ant-design:sync-outlined" :loading="syncing" v-auth="'wms:work-order:sync'" @click="openSync">
          从ERP同步
        </a-button>
      </template>
      <template #action="{ record }">
        <TableAction :actions="[{ label: '详情', onClick: () => openDetail(record) }]" />
      </template>
    </BasicTable>

    <a-modal v-model:visible="syncVisible" title="从金蝶同步生产工单" :confirm-loading="syncing" @ok="handleSyncKingdee">
      <a-form layout="vertical">
        <a-form-item label="工单号（可空；精确同步一单）">
          <a-input v-model:value="syncForm.billNo" placeholder="留空则按日期窗口批量同步" />
        </a-form-item>
        <a-form-item label="开始日期">
          <a-input v-model:value="syncForm.startDate" placeholder="yyyy-MM-dd（可空）" />
        </a-form-item>
        <a-form-item label="结束日期">
          <a-input v-model:value="syncForm.endDate" placeholder="yyyy-MM-dd（可空）" />
        </a-form-item>
        <a-form-item label="单次行数上限">
          <a-input-number v-model:value="syncForm.top" :min="1" :max="200" style="width: 100%" />
        </a-form-item>
        <a-alert v-if="syncWindowHint" :message="syncWindowHint" type="info" show-icon />
      </a-form>
    </a-modal>

    <a-drawer v-model:visible="detailVisible" :title="'工单详情 ' + detail?.workOrderNo" width="560">
      <a-descriptions v-if="detail" :column="2" size="small" bordered>
        <a-descriptions-item label="物料">{{ detail.materialCode }} {{ detail.materialName }}</a-descriptions-item>
        <a-descriptions-item label="产品类型">{{ detail.productType }}</a-descriptions-item>
        <a-descriptions-item label="计划数量">{{ detail.planQty }}</a-descriptions-item>
        <a-descriptions-item label="批次号">{{ detail.batchNo }}</a-descriptions-item>
        <a-descriptions-item label="状态">{{ detail.workOrderStatus }}</a-descriptions-item>
        <a-descriptions-item label="金蝶原始状态">{{ detail.erpDocumentStatus || '-' }}</a-descriptions-item>
        <a-descriptions-item label="来源">{{ detail.source }}</a-descriptions-item>
        <a-descriptions-item label="同步状态">{{ detail.syncStatus }}</a-descriptions-item>
      </a-descriptions>
      <a-divider>关联入库记录</a-divider>
      <a-empty v-if="!inboundRecords.length" description="暂无入库记录" />
      <a-table v-else :data-source="inboundRecords" :pagination="false" size="small" row-key="id">
        <a-table-column title="入库单号" data-index="inboundNo" />
        <a-table-column title="良品" data-index="goodQty" :width="80" />
        <a-table-column title="不良" data-index="badQty" :width="80" />
        <a-table-column title="状态" data-index="status" :width="100" />
        <a-table-column title="提交时间" data-index="submitTime" :width="160" />
      </a-table>
    </a-drawer>
  </div>
</template>

<script lang="ts" name="wms-work-order" setup>
  import { reactive, ref } from 'vue';
  import { BasicTable } from '/@/components/Table';
  import { useListPage } from '/@/hooks/system/useListPage';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { columns, searchFormSchema } from './workOrder.data';
  import { fetchInboundRecords, listWorkOrder, syncKingdeeWorkOrder } from './workOrder.api';

  const { createMessage } = useMessage();
  const syncing = ref(false);
  const syncVisible = ref(false);
  const syncForm = reactive<{ billNo?: string; startDate?: string; endDate?: string; top?: number }>({ top: 50 });
  const syncWindowHint = ref('');
  const detailVisible = ref(false);
  const detail = ref<any>(null);
  const inboundRecords = ref<any[]>([]);

  const { tableContext } = useListPage({
    tableProps: {
      api: listWorkOrder,
      columns,
      rowKey: 'id',
      formConfig: { schemas: searchFormSchema },
      actionColumn: { width: 80 },
      useSearchForm: true,
    },
  });
  const [registerTable, { reload }] = tableContext;

  function openSync() {
    syncWindowHint.value = '';
    syncVisible.value = true;
  }

  async function handleSyncKingdee() {
    syncing.value = true;
    try {
      const res: any = await syncKingdeeWorkOrder({ ...syncForm });
      const summary = `同步完成：新增 ${res.imported}，更新 ${res.updated}，跳过 ${res.skipped}`;
      syncWindowHint.value = res.effectiveStartDate || res.effectiveEndDate
        ? `本次实际同步窗口：${res.effectiveStartDate || '不限'} ~ ${res.effectiveEndDate || '不限'}`
        : '本次按单号精确同步';
      if (res.skipped > 0 && res.messages?.length) {
        createMessage.warning(`${summary}；${res.messages.slice(0, 3).join('；')}`, 8);
      } else {
        createMessage.success(summary);
      }
      reload();
    } catch (e) {
      createMessage.error('金蝶工单同步失败，请检查金蝶配置或后端日志');
    } finally {
      syncing.value = false;
    }
  }

  async function openDetail(record: any) {
    detail.value = record;
    detailVisible.value = true;
    inboundRecords.value = (await fetchInboundRecords(record.workOrderNo)) || [];
  }
</script>

<style lang="less" scoped>
  .wms-work-order {
    padding: 16px;
  }
</style>
