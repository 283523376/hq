<!-- eslint-disable vue/multi-word-component-names -->
<template>
  <div :class="`${prefixCls}`">
    <BasicTable @register="registerTable" :rowSelection="rowSelection">
      <template #tableTitle>
        <a-button type="primary" preIcon="ant-design:plus-outlined" v-auth="'wms:customer:create'" @click="handleCreate">
          新增
        </a-button>
        <a-button type="primary" preIcon="ant-design:export-outlined" v-auth="'wms:customer:list'" @click="onExportXls">
          导出
        </a-button>
        <a-dropdown v-if="selectedRowKeys.length > 0">
          <template #overlay>
            <a-menu>
              <a-menu-item key="1" @click="confirmBatchDelete(batchIds)">
                <Icon icon="ant-design:delete-outlined" />
                批量删除
              </a-menu-item>
            </a-menu>
          </template>
          <a-button v-auth="'wms:customer:delete'">
            批量操作
            <Icon icon="ant-design:down-outlined" />
          </a-button>
        </a-dropdown>
      </template>
      <template #action="{ record }">
        <TableAction :actions="getTableAction(record)" />
      </template>
    </BasicTable>
    <CustomerModal @register="registerModal" @success="handleSuccess" />
  </div>
</template>

<script lang="ts" name="wms-customer" setup>
  import { computed } from 'vue';
  import { BasicTable, TableAction } from '/@/components/Table';
  import { useModal } from '/@/components/Modal';
  import { useListPage } from '/@/hooks/system/useListPage';
  import { useDesign } from '/@/hooks/web/useDesign';
  import CustomerModal from './components/CustomerModal.vue';
  import { columns, searchFormSchema } from './customer.data';
  import {
    list,
    deleteCustomer,
    batchDeleteCustomer,
    disableCustomer,
    checkCustomerDelete,
    checkCustomerDeleteBatch,
    getExportUrl,
  } from './customer.api';
  import { useWmsDeleteCheck } from '../common/useWmsDeleteCheck';

  const { prefixCls } = useDesign('wms-customer');

  const [registerModal, { openModal }] = useModal();

  const { onExportXls, tableContext } = useListPage({
    tableProps: {
      api: list,
      columns,
      rowKey: 'id',
      formConfig: { schemas: searchFormSchema },
      actionColumn: { width: 180 },
    },
    exportConfig: {
      name: '客户档案',
      url: getExportUrl,
    },
  });

  const [registerTable, { reload }, { rowSelection, selectedRowKeys }] = tableContext;

  /** 勾选行 id（过滤树形加载占位行） */
  const batchIds = computed(() =>
    selectedRowKeys.value.filter((key) => !String(key).includes('loadChild')),
  );

  // 删除守卫统一交互：预检先行，后端守卫仍兜底（spec 2026-10-16 §4）
  const { confirmDelete, confirmBatchDelete } = useWmsDeleteCheck(
    {
      checkDelete: checkCustomerDelete,
      checkDeleteBatch: checkCustomerDeleteBatch,
      remove: deleteCustomer,
      removeBatch: batchDeleteCustomer,
    },
    handleSuccess,
  );

  function handleCreate() {
    openModal(true, { isUpdate: false });
  }

  function handleEdit(record) {
    openModal(true, { record, isUpdate: true });
  }

  async function handleDisable(record) {
    await disableCustomer({ id: record.id });
    reload();
  }

  function handleSuccess() {
    selectedRowKeys.value = [];
    reload();
  }

  function getTableAction(record) {
    return [
      {
        label: '编辑',
        auth: 'wms:customer:edit',
        onClick: handleEdit.bind(null, record),
      },
      {
        label: '停用',
        auth: 'wms:customer:edit',
        ifShow: record.status === '1',
        popConfirm: {
          title: '确定停用吗?',
          confirm: handleDisable.bind(null, record),
        },
      },
      {
        label: '删除',
        auth: 'wms:customer:delete',
        onClick: confirmDelete.bind(null, record),
      },
    ];
  }
</script>
