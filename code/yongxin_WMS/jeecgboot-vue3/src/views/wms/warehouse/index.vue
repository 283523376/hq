<template>
  <div :class="`${prefixCls}`">
    <a-row :gutter="10">
      <a-col :xl="6" :lg="24" :md="24" style="margin-bottom: 10px">
        <WarehouseTree @select="onTreeSelect" @refresh="handleSuccess" />
      </a-col>
      <a-col :xl="18" :lg="24" :md="24" style="margin-bottom: 10px">
        <a-alert v-if="envelopeWarning" type="warning" show-icon class="mb-2" :message="envelopeWarning" />
        <BasicTable @register="registerTable" :rowSelection="rowSelection">
          <template #tableTitle>
            <a-button
              type="primary"
              preIcon="ant-design:thunderbolt-outlined"
              v-auth="'wms:location:generate'"
              :disabled="!currentWarehouseId"
              @click="handleGenerate"
            >
              批量生成
            </a-button>
            <a-button
              type="primary"
              preIcon="ant-design:plus-outlined"
              v-auth="'wms:location:add'"
              :disabled="!currentWarehouseId"
              @click="handleCreate"
            >
              新增库位
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
              <a-button v-auth="'wms:location:delete'">
                批量操作
                <Icon icon="ant-design:down-outlined" />
              </a-button>
            </a-dropdown>
          </template>
          <template #action="{ record }">
            <TableAction :actions="getTableAction(record)" />
          </template>
        </BasicTable>
      </a-col>
    </a-row>
    <GenerateModal @register="registerGenerateModal" @success="handleSuccess" />
    <LocationAttrModal @register="registerAttrModal" @success="handleSuccess" />
    <LocationModal @register="registerLocationModal" @success="handleSuccess" />
  </div>
</template>

<script lang="ts" name="wms-warehouse" setup>
  import { computed, ref } from 'vue';
  import { BasicTable, TableAction } from '/@/components/Table';
  import { useModal } from '/@/components/Modal';
  import { useListPage } from '/@/hooks/system/useListPage';
  import { useDesign } from '/@/hooks/web/useDesign';
  import { useMessage } from '/@/hooks/web/useMessage';
  import WarehouseTree from './components/WarehouseTree.vue';
  import GenerateModal from './components/GenerateModal.vue';
  import LocationAttrModal from './components/LocationAttrModal.vue';
  import LocationModal from './components/LocationModal.vue';
  import { columns, searchFormSchema } from './location.data';
  import {
    list,
    deleteLocation,
    releaseLocation,
    batchDeleteLocation,
    checkLocationDelete,
    checkLocationDeleteBatch,
  } from './location.api';
  import { queryById } from './warehouse.api';
  import { useWmsDeleteCheck } from '../common/useWmsDeleteCheck';

  const { prefixCls } = useDesign('wms-warehouse');
  const { createMessage } = useMessage();

  /** 左侧树筛选条件：按类型过滤 或 按单个库区过滤 */
  const filter = ref<{ warehouseId?: string; warehouseType?: string }>({});
  const currentWarehouseId = computed(() => filter.value.warehouseId ?? '');
  /** 选中库区的详情（含 outOfEnvelopeCount），供生成弹窗带出维度与越界提示 */
  const warehouseDetail = ref<any>(null);
  const envelopeWarning = ref('');

  const [registerGenerateModal, { openModal: openGenerateModal }] = useModal();
  const [registerAttrModal, { openModal: openAttrModal }] = useModal();
  const [registerLocationModal, { openModal: openLocationModal }] = useModal();

  const { tableContext } = useListPage({
    tableProps: {
      api: list,
      columns,
      rowKey: 'id',
      formConfig: { schemas: searchFormSchema },
      actionColumn: { width: 180 },
      beforeFetch(params) {
        // 必须返回合并结果：useListPage 的导出侧没有 `|| params` 兜底，
        // 不返回会导致 Object.assign(undefined, …) 抛 TypeError，表现为「点导出没反应」。
        return Object.assign(params, filter.value);
      },
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
      checkDelete: checkLocationDelete,
      checkDeleteBatch: checkLocationDeleteBatch,
      remove: deleteLocation,
      removeBatch: batchDeleteLocation,
    },
    handleSuccess,
  );

  function handleSuccess() {
    selectedRowKeys.value = [];
    reload();
  }

  async function onTreeSelect(node: any) {
    filter.value = node ?? {};
    warehouseDetail.value = null;
    envelopeWarning.value = '';
    if (node?.warehouseId) {
      // 维度可自由增减，缩维后已有库位可能落在新声明之外 —— 系统不拦截，但要让人看见
      const res: any = await queryById({ id: node.warehouseId });
      const detail = res?.result ?? res;
      warehouseDetail.value = detail ?? null;
      if (detail?.outOfEnvelopeCount > 0) {
        envelopeWarning.value = `有 ${detail.outOfEnvelopeCount} 个库位位于当前维度之外（库区声明为 ${detail.rowCount} 排 × ${detail.colCount} 列 × ${detail.layerCount} 层）。系统不做拦截，请确认是否需要调整库区维度或清理库位。`;
      }
    }
    reload();
  }

  function handleGenerate() {
    openGenerateModal(true, { warehouse: warehouseDetail.value });
  }

  function handleCreate() {
    openLocationModal(true, { isUpdate: false, warehouseId: currentWarehouseId.value });
  }

  function handleEdit(record) {
    openAttrModal(true, { record });
  }

  async function handleRelease(record) {
    await releaseLocation({ id: record.id });
    createMessage.success('释放成功');
    handleSuccess();
  }

  function getTableAction(record) {
    const actions: any[] = [
      { label: '属性', auth: 'wms:location:attr', onClick: handleEdit.bind(null, record) },
    ];
    // 释放仅对「占用」可见 —— 它是把占用改回空闲的唯一人工通道
    if (record.status === '占用') {
      actions.push({
        label: '释放',
        auth: 'wms:location:release',
        popConfirm: { title: '确认该库位已清空？释放后状态变为「空闲」', confirm: handleRelease.bind(null, record) },
      });
    }
    // 删除仅对「空闲」可见（后端同样强制）；预检先行
    if (record.status === '空闲') {
      actions.push({
        label: '删除',
        auth: 'wms:location:delete',
        onClick: confirmDelete.bind(null, record),
      });
    }
    return actions;
  }
</script>