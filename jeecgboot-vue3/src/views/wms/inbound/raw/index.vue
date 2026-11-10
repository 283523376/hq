<!-- eslint-disable vue/multi-word-component-names -->
<template>
  <div class="wms-raw-inbound">
    <a-alert
      type="info"
      show-icon
      class="wms-raw-inbound__alert"
      message="改刀料与段差棒同口径：直接入半成品立库，登记后需上架入库"
    >
      <template #description>
        <div>
          「改刀料（CONVERT）」与「段差棒直入（DIRECT）」处理方式一致——登记后为「待分配」，
          在行内点「上架」选择立库货位后才入库存，并按外购/受托回写金蝶。
        </div>
        <div>受托材料单独记「受托库存」，与自有库存分开；两类均不经过任何加工环节。</div>
      </template>
    </a-alert>

    <BasicTable @register="registerTable">
      <template #tableTitle>
        <a-button type="primary" preIcon="ant-design:import-outlined" v-auth="'wms:inbound:consigned'" @click="openReceive">
          到货登记
        </a-button>
        <span class="wms-raw-inbound__hint">外购需金蝶采购订单、受托需受托加工订单，登记前会先到金蝶校验订单</span>
      </template>
      <template #action="{ record }">
        <TableAction :actions="getActions(record)" />
      </template>
    </BasicTable>

    <RawReceiveModal v-model:open="receiveOpen" @success="reload" />
    <RawPutawayModal v-model:open="putawayOpen" :record="currentRecord" @success="reload" />
  </div>
</template>

<script lang="ts" name="wms-raw-inbound" setup>
  import { ref } from 'vue';
  import { Modal } from 'ant-design-vue';
  import { BasicTable, TableAction } from '/@/components/Table';
  import { useListPage } from '/@/hooks/system/useListPage';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { columns, isSyncFailed, searchFormSchema } from './raw.data';
  import { listRawInbound, resyncRawInbound } from './raw.api';
  import RawReceiveModal from './components/RawReceiveModal.vue';
  import RawPutawayModal from './components/RawPutawayModal.vue';

  const { createMessage } = useMessage();

  const { tableContext } = useListPage({
    tableProps: {
      api: listRawInbound,
      columns,
      rowKey: 'id',
      formConfig: { schemas: searchFormSchema },
      actionColumn: { width: 100 },
    },
  });
  const [registerTable, { reload }] = tableContext;

  const receiveOpen = ref(false);
  const putawayOpen = ref(false);
  const currentRecord = ref<any>(null);

  function openReceive() {
    receiveOpen.value = true;
  }

  function getActions(record: any) {
    const actions: any[] = [];
    // V2.6：改刀料与段差棒同口径，凡是「待分配」都要上架（历史大改小单据状态为「已审核」，自然不会命中）
    if (record.inboundStatus === '待分配') {
      actions.push({
        label: '上架',
        auth: 'wms:inbound:consigned',
        onClick: () => openPutaway(record),
      });
    }
    if (isSyncFailed(record)) {
      // 补偿入口：上架时金蝶回写失败的单，用户在这里补一次
      actions.push({
        label: '重试回写',
        auth: 'wms:inbound:consigned',
        color: 'error',
        onClick: () => handleResync(record),
      });
    }
    return actions;
  }

  async function handleResync(record: any) {
    const result: any = await resyncRawInbound(record.id);
    if (result?.syncStatus === '同步成功') {
      createMessage.success(`回写成功${result.erpBillNo ? `：${result.erpBillNo}` : ''}`);
    } else {
      Modal.warning({
        title: '回写仍未成功',
        content: `原因：${result?.lastSyncError || '未知错误'}。请检查金蝶配置或单据状态后重试。`,
        okText: '知道了',
      });
    }
    reload();
  }

  function openPutaway(record: any) {
    currentRecord.value = record;
    putawayOpen.value = true;
  }
</script>

<style lang="less" scoped>
  .wms-raw-inbound {
    padding: 16px;
  }

  .wms-raw-inbound__alert {
    margin-bottom: 12px;
  }

  .wms-raw-inbound__hint {
    margin-left: 12px;
    color: #999;
  }
</style>
