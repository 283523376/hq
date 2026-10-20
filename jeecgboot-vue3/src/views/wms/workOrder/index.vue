<template>
  <div class="wms-work-order">
    <BasicTable @register="registerTable">
      <template #tableTitle>
        <a-button type="primary" preIcon="ant-design:plus-outlined" v-auth="'wms:work-order:add'" @click="handleCreate">
          新增
        </a-button>
        <a-button
          type="primary"
          ghost
          preIcon="ant-design:sync-outlined"
          :loading="syncing"
          v-auth="'wms:work-order:edit'"
          style="margin-left: 8px"
          @click="handleSyncKingdee"
        >
          从金蝶同步
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
  import { ref } from 'vue';
  import { BasicTable, TableAction } from '/@/components/Table';
  import { useModal } from '/@/components/Modal';
  import { useListPage } from '/@/hooks/system/useListPage';
  import { useMessage } from '/@/hooks/web/useMessage';
  import WorkOrderModal from './components/WorkOrderModal.vue';
  import { columns, searchFormSchema } from './workOrder.data';
  import { deleteWorkOrder, listWorkOrder, syncKingdeeWorkOrder } from './workOrder.api';

  const [registerModal, { openModal }] = useModal();
  const { createMessage } = useMessage();
  const syncing = ref(false);

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

  async function handleSyncKingdee() {
    syncing.value = true;
    try {
      const res: any = await syncKingdeeWorkOrder({ top: 50 });
      const summary = `同步完成：新增 ${res.imported}，更新 ${res.updated}，跳过 ${res.skipped}`;
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
