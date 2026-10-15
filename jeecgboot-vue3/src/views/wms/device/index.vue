<template>
  <div :class="`${prefixCls}`">
    <BasicTable @register="registerTable" :rowSelection="rowSelection">
      <template #tableTitle>
        <a-button type="primary" preIcon="ant-design:plus-outlined" v-auth="'wms:device:add'" @click="handleCreate">
          新增
        </a-button>
        <a-button type="primary" preIcon="ant-design:link-outlined" v-auth="'wms:device:bind'" @click="handleBind">
          绑定库区
        </a-button>
        <a-button
          v-auth="'wms:device:bind'"
          :disabled="selectedRowKeys.length === 0"
          preIcon="ant-design:disconnect-outlined"
          @click="handleUnbind"
        >
          解绑
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
          <a-button v-auth="'wms:device:delete'">
            批量操作
            <Icon icon="ant-design:down-outlined" />
          </a-button>
        </a-dropdown>
      </template>
      <template #action="{ record }">
        <TableAction :actions="getTableAction(record)" />
      </template>
    </BasicTable>
    <DeviceModal @register="registerModal" @success="handleSuccess" />
    <DeviceBindModal @register="registerBindModal" @success="handleSuccess" />
  </div>
</template>

<script lang="ts" name="wms-device" setup>
  import { BasicTable, TableAction } from '/@/components/Table';
  import { useModal } from '/@/components/Modal';
  import { useListPage } from '/@/hooks/system/useListPage';
  import { useDesign } from '/@/hooks/web/useDesign';
  import { useMessage } from '/@/hooks/web/useMessage';
  import DeviceModal from './components/DeviceModal.vue';
  import DeviceBindModal from './components/DeviceBindModal.vue';
  import { columns, searchFormSchema } from './device.data';
  import { list, deleteDevice, batchDeleteDevice, unbindDevices } from './device.api';

  const { prefixCls } = useDesign('wms-device');
  const { createMessage } = useMessage();

  const [registerModal, { openModal }] = useModal();
  const [registerBindModal, { openModal: openBindModal }] = useModal();

  const { tableContext } = useListPage({
    tableProps: {
      api: list,
      columns,
      rowKey: 'id',
      formConfig: { schemas: searchFormSchema },
      actionColumn: { width: 140 },
    },
  });
  const [registerTable, { reload }, { rowSelection, selectedRowKeys }] = tableContext;

  function handleSuccess() {
    selectedRowKeys.value = [];
    reload();
  }

  function handleCreate() {
    openModal(true, { isUpdate: false });
  }

  function handleEdit(record) {
    openModal(true, { record, isUpdate: true });
  }

  function handleBind() {
    // 目标库区与设备列表都在弹窗内自查，避免「树选 A、弹窗选 B」这类歧义
    openBindModal(true, {});
  }

  async function handleUnbind() {
    await unbindDevices(selectedRowKeys.value);
    createMessage.success('解绑成功');
    handleSuccess();
  }

  async function handleDelete(record) {
    await deleteDevice({ id: record.id }, handleSuccess);
  }

  async function batchHandleDelete() {
    const ids = selectedRowKeys.value.filter((key) => !String(key).includes('loadChild'));
    if (ids.length === 0) {
      return;
    }
    await batchDeleteDevice({ ids: ids.join(',') }, handleSuccess);
  }

  function getTableAction(record) {
    return [
      { label: '编辑', auth: 'wms:device:edit', onClick: handleEdit.bind(null, record) },
      {
        label: '删除',
        auth: 'wms:device:delete',
        popConfirm: { title: '确定删除吗?', confirm: handleDelete.bind(null, record) },
      },
    ];
  }
</script>