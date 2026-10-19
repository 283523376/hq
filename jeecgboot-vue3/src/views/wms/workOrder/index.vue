<template>
  <div class="wms-work-order">
    <BasicTable @register="registerTable">
      <template #tableTitle>
        <a-button type="primary" preIcon="ant-design:plus-outlined" v-auth="'wms:work-order:add'" @click="handleCreate">
          新增
        </a-button>
      </template>
      <template #action="{ record }">
        <TableAction :actions="getTableAction(record)" />
      </template>
    </BasicTable>
    <WorkOrderModal @register="registerModal" @success="handleSuccess" />
  </div>
</template>

<script lang="ts" name="wms-work-order" setup>
  import { BasicTable, TableAction } from '/@/components/Table';
  import { useModal } from '/@/components/Modal';
  import { useListPage } from '/@/hooks/system/useListPage';
  import WorkOrderModal from './components/WorkOrderModal.vue';
  import { columns, searchFormSchema } from './workOrder.data';
  import { deleteWorkOrder, listWorkOrder } from './workOrder.api';

  const [registerModal, { openModal }] = useModal();

  const { tableContext } = useListPage({
    tableProps: {
      api: listWorkOrder,
      columns,
      rowKey: 'id',
      formConfig: { schemas: searchFormSchema },
      actionColumn: { width: 160 },
      useSearchForm: true,
    },
  });
  const [registerTable, { reload }] = tableContext;

  function handleCreate() {
    openModal(true, { isUpdate: false });
  }

  function handleEdit(record: any) {
    openModal(true, { record, isUpdate: true });
  }

  async function handleDelete(record: any) {
    await deleteWorkOrder({ id: record.id }, handleSuccess);
  }

  function handleSuccess() {
    reload();
  }

  function getTableAction(record: any) {
    return [
      {
        label: '编辑',
        auth: 'wms:work-order:edit',
        onClick: () => handleEdit(record),
      },
      {
        label: '删除',
        auth: 'wms:work-order:delete',
        color: 'error',
        popConfirm: {
          title: `确认删除工单 ${record.workOrderNo}？已被生产入库引用的工单不可删除。`,
          confirm: () => handleDelete(record),
        },
      },
    ];
  }
</script>

<style lang="less" scoped>
  .wms-work-order {
    padding: 16px;
  }
</style>
