<template>
  <div :class="`${prefixCls}`">
    <a-row :gutter="10">
      <a-col :xl="6" :lg="24" :md="24" style="margin-bottom: 10px">
        <CategoryTree ref="categoryTreeRef" @select="onTreeSelect" @refresh="onCategoryRefresh" />
      </a-col>
      <a-col :xl="18" :lg="24" :md="24" style="margin-bottom: 10px">
        <BasicTable @register="registerTable" :rowSelection="rowSelection">
          <template #tableTitle>
            <a-button
              type="primary"
              preIcon="ant-design:plus-outlined"
              v-auth="'wms:material:add'"
              @click="handleCreate"
            >
              新增
            </a-button>
            <j-upload-button
              type="primary"
              preIcon="ant-design:import-outlined"
              v-auth="'wms:material:import'"
              @click="onImportXls"
            >
              导入
            </j-upload-button>
            <a-button
              type="primary"
              preIcon="ant-design:export-outlined"
              v-auth="'wms:material:export'"
              @click="onExportXls"
            >
              导出
            </a-button>
            <a-button
              type="primary"
              preIcon="ant-design:file-add-outlined"
              v-auth="'wms:material:import'"
              @click="onDownloadTemplate"
            >
              模板下载
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
              <a-button v-auth="'wms:material:delete'">
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
    <MaterialModal @register="registerModal" @success="handleSuccess" />
  </div>
</template>

<script lang="ts" name="wms-material" setup>
  import { computed, ref } from 'vue';
  import { BasicTable, TableAction } from '/@/components/Table';
  import { useModal } from '/@/components/Modal';
  import { useListPage } from '/@/hooks/system/useListPage';
  import { useMethods } from '/@/hooks/system/useMethods';
  import { useDesign } from '/@/hooks/web/useDesign';
  import CategoryTree from './components/CategoryTree.vue';
  import MaterialModal from './components/MaterialModal.vue';
  import { columns, searchFormSchema } from './material.data';
  import {
    list,
    deleteMaterial,
    batchDeleteMaterial,
    disableMaterial,
    getExportUrl,
    getImportUrl,
    getTemplateUrl,
    checkMaterialDelete,
    checkMaterialDeleteBatch,
  } from './material.api';
  import { useWmsDeleteCheck } from '../common/useWmsDeleteCheck';

  const { prefixCls } = useDesign('wms-material');
  const categoryTreeRef = ref();
  /** 左侧分类树当前选中项，空串表示「全部分类」 */
  const categoryId = ref('');

  const [registerModal, { openModal }] = useModal();
  const { handleExportXls } = useMethods();

  const { onExportXls, onImportXls, tableContext } = useListPage({
    tableProps: {
      api: list,
      columns,
      rowKey: 'id',
      formConfig: { schemas: searchFormSchema },
      actionColumn: { width: 150 },
      beforeFetch(params) {
        // 必须返回合并结果：useListPage.onExportXls 会用 beforeFetch 的返回值覆盖 paramsForm，
        // 而表格自身请求（useDataSource）有 `|| params` 兜底、导出侧没有；不返回会导致
        // Object.assign(undefined, …) 抛 TypeError，表现为「点导出没反应」。
        return Object.assign(params, { categoryId: categoryId.value });
      },
    },
    exportConfig: {
      name: '物料列表',
      url: getExportUrl,
      params: () => ({ categoryId: categoryId.value }),
    },
    importConfig: {
      url: getImportUrl,
    },
  });

  const [registerTable, { reload }, { rowSelection, selectedRowKeys }] = tableContext;

  /** 左侧树选择 → 右侧按分类过滤 */
  function onTreeSelect(node) {
    categoryId.value = node?.id ?? '';
    reload();
  }

  /** 分类增删改后，物料列表的分类名可能变化，一并刷新 */
  function onCategoryRefresh() {
    reload();
  }

  /** 下载空白导入模板（Excel） */
  function onDownloadTemplate() {
    handleExportXls('物料导入模板', getTemplateUrl);
  }

  /** 勾选行 id（过滤树形加载占位行） */
  const batchIds = computed(() =>
    selectedRowKeys.value.filter((key) => !String(key).includes('loadChild')),
  );

  // 删除守卫统一交互：预检先行，后端守卫仍兜底（spec 2026-10-16 §4）
  const { confirmDelete, confirmBatchDelete } = useWmsDeleteCheck(
    {
      checkDelete: checkMaterialDelete,
      checkDeleteBatch: checkMaterialDeleteBatch,
      remove: deleteMaterial,
      removeBatch: batchDeleteMaterial,
    },
    handleSuccess,
  );

  function handleCreate() {
    openModal(true, { isUpdate: false, categoryId: categoryId.value });
  }

  function handleEdit(record) {
    openModal(true, { record, isUpdate: true });
  }

  async function handleDisable(record) {
    await disableMaterial({ id: record.id });
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
        auth: 'wms:material:edit',
        onClick: handleEdit.bind(null, record),
      },
      {
        label: '停用',
        auth: 'wms:material:edit',
        popConfirm: {
          title: '确定停用吗?',
          confirm: handleDisable.bind(null, record),
        },
      },
      {
        label: '删除',
        auth: 'wms:material:delete',
        onClick: confirmDelete.bind(null, record),
      },
    ];
  }
</script>
