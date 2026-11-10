<!-- eslint-disable vue/multi-word-component-names -->
<template>
  <div class="wms-transfer">
    <BasicTable @register="registerTable">
      <template #tableTitle>
        <a-button type="primary" preIcon="ant-design:plus-outlined" v-auth="'wms:transfer:add'" @click="openCreate">
          新建调拨
        </a-button>
      </template>
      <template #action="{ record }">
        <TableAction :actions="getTableAction(record)" />
      </template>
    </BasicTable>

    <TransferCreateModal v-model:open="createOpen" @success="reload" />
    <TransferDetailDrawer v-model:open="detailOpen" :transfer-id="detailId" @refresh="reload" />
  </div>
</template>

<script lang="ts" setup>
  import { ref } from 'vue';
  import { BasicTable, TableAction } from '/@/components/Table';
  import { useListPage } from '/@/hooks/system/useListPage';
  import { columns, searchFormSchema } from './transfer.data';
  import { listTransfer } from './transfer.api';
  import TransferCreateModal from './components/TransferCreateModal.vue';
  import TransferDetailDrawer from './components/TransferDetailDrawer.vue';

  const { tableContext } = useListPage({
    tableProps: {
      api: listTransfer,
      columns,
      rowKey: 'id',
      formConfig: { schemas: searchFormSchema },
      actionColumn: { width: 180 },
    },
  });
  const [registerTable, { reload }] = tableContext;

  const createOpen = ref(false);
  const detailOpen = ref(false);
  const detailId = ref('');

  function openCreate() {
    createOpen.value = true;
  }

  function openDetail(record: any) {
    detailId.value = record.id;
    detailOpen.value = true;
  }

  function getTableAction(record: any) {
    const actions: any[] = [{ label: '详情', onClick: () => openDetail(record) }];
    if (record.status === '待执行') {
      actions.push({ label: '下发', auth: 'wms:transfer:dispatch', onClick: () => openDetail(record) });
    }
    if (record.status === '待ERP审核') {
      actions.push({ label: '等金蝶审批', disabled: true, onClick: () => undefined });
    }
    return actions;
  }
</script>

<style lang="less" scoped>
  .wms-transfer {
    padding: 16px;
  }
</style>
