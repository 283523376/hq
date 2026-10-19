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
        <a-button type="primary" preIcon="ant-design:plus-outlined" v-auth="'wms:supplier:add'" @click="handleCreate">
          新增
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
          <a-button v-auth="'wms:supplier:delete'">
            批量操作
            <Icon icon="ant-design:down-outlined" />
          </a-button>
        </a-dropdown>
      </template>
      <template #action="{ record }">
        <TableAction :actions="getTableAction(record)" />
      </template>
    </BasicTable>
    <SupplierModal @register="registerModal" @success="handleSuccess" />
  </div>
</template>

<script lang="ts" name="wms-supplier" setup>
  import { computed } from 'vue';
  import { BasicTable, TableAction } from '/@/components/Table';
  import { useModal } from '/@/components/Modal';
  import { useListPage } from '/@/hooks/system/useListPage';
  import { useDesign } from '/@/hooks/web/useDesign';
  import SupplierModal from './components/SupplierModal.vue';
  import { columns, searchFormSchema } from './supplier.data';
  import {
    list,
    deleteSupplier,
    batchDeleteSupplier,
    disableSupplier,
    checkSupplierDelete,
    checkSupplierDeleteBatch,
  } from './supplier.api';
  import { useWmsDeleteCheck } from '../common/useWmsDeleteCheck';

  const { prefixCls } = useDesign('wms-supplier');

  const [registerModal, { openModal }] = useModal();

  const { tableContext } = useListPage({
    tableProps: {
      api: list,
      columns,
      rowKey: 'id',
      formConfig: { schemas: searchFormSchema },
      actionColumn: { width: 180 },
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
      checkDelete: checkSupplierDelete,
      checkDeleteBatch: checkSupplierDeleteBatch,
      remove: deleteSupplier,
      removeBatch: batchDeleteSupplier,
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
    await disableSupplier({ id: record.id });
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
        auth: 'wms:supplier:edit',
        onClick: handleEdit.bind(null, record),
      },
      {
        label: '停用',
        auth: 'wms:supplier:edit',
        ifShow: record.status === '1',
        popConfirm: {
          title: '确定停用吗?',
          confirm: handleDisable.bind(null, record),
        },
      },
      {
        label: '删除',
        auth: 'wms:supplier:delete',
        onClick: confirmDelete.bind(null, record),
      },
    ];
  }
</script>
