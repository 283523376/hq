<template>
  <div :class="`${prefixCls}`">
    <a-alert
      type="warning"
      show-icon
      banner
      style="margin-bottom: 12px"
      message="该功能已因项目范围调整停用（原材料/焊接线边仓已移出范围），仅保留历史数据查询。"
    />
    <BasicTable @register="registerTable" :rowSelection="rowSelection">
      <template #tableTitle>
        <a-button type="primary" preIcon="ant-design:plus-outlined" v-auth="'wms:order:add'" @click="handleCreate">
          新增
        </a-button>
        <a-dropdown v-if="selectedRowKeys.length > 0">
          <template #overlay>
            <a-menu>
              <a-menu-item key="1" @click="batchHandleDelete">
                <Icon icon="ant-design:delete-outlined" />
                批量删除
              </a-menu-item>
            </a-menu>
          </template>
          <a-button v-auth="'wms:order:delete'">
            批量操作
            <Icon icon="ant-design:down-outlined" />
          </a-button>
        </a-dropdown>
      </template>
      <template #action="{ record }">
        <TableAction :actions="getTableAction(record)" />
      </template>
    </BasicTable>
    <OrderModal @register="registerModal" @success="handleSuccess" />
    <AuditModal @register="registerAuditModal" @success="handleSuccess" />
  </div>
</template>

<script lang="ts" name="wms-order" setup>
  import { BasicTable, TableAction } from '/@/components/Table';
  import { useModal } from '/@/components/Modal';
  import { useListPage } from '/@/hooks/system/useListPage';
  import { useDesign } from '/@/hooks/web/useDesign';
  import { useMessage } from '/@/hooks/web/useMessage';
  import OrderModal from './components/OrderModal.vue';
  import AuditModal from './components/AuditModal.vue';
  import { columns, searchFormSchema } from './order.data';
  import { list, deleteOrder, batchDeleteOrder, dispatchOrder, resyncOrder } from './order.api';

  const { prefixCls } = useDesign('wms-order');
  const { createMessage } = useMessage();

  const [registerModal, { openModal }] = useModal();
  const [registerAuditModal, { openModal: openAuditModal }] = useModal();

  const { tableContext } = useListPage({
    tableProps: {
      api: list,
      columns,
      rowKey: 'id',
      formConfig: { schemas: searchFormSchema },
      actionColumn: { width: 260 },
    },
  });

  const [registerTable, { reload }, { rowSelection, selectedRowKeys }] = tableContext;

  function handleCreate() {
    openModal(true, { isUpdate: false });
  }

  function handleEdit(record) {
    openModal(true, { record, isUpdate: true });
  }

  function handleAudit(record) {
    openAuditModal(true, { record });
  }

  /** 下达：成功与否看返回的 synced 字段——下达本身成功但同步可能失败 */
  async function handleDispatch(record) {
    const res: any = await dispatchOrder({ id: record.id });
    if (res && res.synced === false) {
      createMessage.warning(
        `已下达，但同步 ERP/MES 失败（已重试 ${res.retryCount} 次）：${res.syncError}。可在列表中重新同步。`,
        8
      );
    } else if (res && res.synced === true) {
      createMessage.success('下达成功，已同步 ERP/MES');
    } else {
      createMessage.info('该订单正在被其它操作处理，本次未重复同步');
    }
    reload();
  }

  async function handleResync(record) {
    const res: any = await resyncOrder({ id: record.id });
    if (res && res.synced === true) {
      createMessage.success('同步成功');
    } else {
      createMessage.warning(`同步仍失败（已重试 ${res?.retryCount} 次）：${res?.syncError}`, 8);
    }
    reload();
  }

  async function handleDelete(record) {
    await deleteOrder({ id: record.id }, handleSuccess);
  }

  async function batchHandleDelete() {
    const ids = selectedRowKeys.value.filter((key) => !String(key).includes('loadChild'));
    if (ids.length === 0) {
      return;
    }
    await batchDeleteOrder({ ids: ids.join(',') }, handleSuccess);
  }

  function handleSuccess() {
    selectedRowKeys.value = [];
    reload();
  }

  function getTableAction(record) {
    const editable = record.orderStatus === '待审核' || record.orderStatus === '已驳回';
    const resyncable =
      record.orderStatus === '已下达' && (record.syncStatus === '同步失败' || record.syncStatus === '待同步');
    return [
      {
        label: '编辑',
        auth: 'wms:order:edit',
        ifShow: editable,
        onClick: handleEdit.bind(null, record),
      },
      {
        label: '审核',
        auth: 'wms:order:audit',
        ifShow: record.orderStatus === '待审核',
        onClick: handleAudit.bind(null, record),
      },
      {
        label: '下达',
        auth: 'wms:order:dispatch',
        ifShow: record.orderStatus === '已审核',
        popConfirm: {
          title: '确定下达该订单吗? 下达后将同步至 ERP/MES。',
          confirm: handleDispatch.bind(null, record),
        },
      },
      {
        label: '重新同步',
        auth: 'wms:order:resync',
        ifShow: resyncable,
        onClick: handleResync.bind(null, record),
      },
      {
        label: '删除',
        auth: 'wms:order:delete',
        ifShow: editable,
        popConfirm: {
          title: '确定删除吗?',
          confirm: handleDelete.bind(null, record),
        },
      },
    ];
  }
</script>
