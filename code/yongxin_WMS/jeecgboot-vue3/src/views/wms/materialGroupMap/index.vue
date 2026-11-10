<!-- eslint-disable vue/multi-word-component-names -->
<template>
  <div :class="`${prefixCls}`">
    <a-alert
      type="info"
      show-icon
      banner
      style="margin-bottom: 12px"
      message="口径：分类类型的批量归属在本页按分组认领，保存后由金蝶分组同步（sync-from-kingdee）自动刷新；分类管理页手工新增/编辑的分类不会被同步覆盖——同步按 erp_group_number 匹配、category_code 兜底，手工分类编码不同即不冲突；物料同步只在映射命中时才覆盖物料的分类，未命中保留人工指定。"
    />
    <BasicTable @register="registerTable">
      <template #tableTitle>
        <a-button
          type="primary"
          preIcon="ant-design:appstore-add-outlined"
          v-auth="['wms:materialGroupMap:create', 'wms:materialGroupMap:edit']"
          @click="openClaimDrawer"
        >
          从未映射分组认领
        </a-button>
        <span class="tip">按物料数从多到少认领，覆盖高频分组收益最大</span>
      </template>
      <template #action="{ record }">
        <TableAction :actions="getTableAction(record)" />
      </template>
    </BasicTable>
    <MaterialGroupMapModal @register="registerModal" @success="handleSuccess" />
    <UnmappedGroupDrawer v-model:open="drawerOpen" @success="handleSuccess" />
  </div>
</template>

<script lang="ts" name="wms-material-group-map" setup>
  import { ref } from 'vue';
  import { BasicTable, TableAction } from '/@/components/Table';
  import { useModal } from '/@/components/Modal';
  import { useListPage } from '/@/hooks/system/useListPage';
  import { useDesign } from '/@/hooks/web/useDesign';
  import MaterialGroupMapModal from './components/MaterialGroupMapModal.vue';
  import UnmappedGroupDrawer from './components/UnmappedGroupDrawer.vue';
  import { columns, searchFormSchema } from './materialGroupMap.data';
  import { deleteOne, list } from './materialGroupMap.api';

  const { prefixCls } = useDesign('wms-material-group-map');

  const [registerModal, { openModal }] = useModal();
  const drawerOpen = ref(false);

  const { tableContext } = useListPage({
    tableProps: {
      api: list,
      columns,
      rowKey: 'id',
      formConfig: { schemas: searchFormSchema },
      actionColumn: { width: 140 },
    },
  });

  const [registerTable, { reload }] = tableContext;

  function openClaimDrawer() {
    drawerOpen.value = true;
  }

  function handleEdit(record) {
    openModal(true, { record, isUpdate: true });
  }

  async function handleDelete(record) {
    await deleteOne(record.id);
    reload();
  }

  /** 编辑保存 / 认领保存 / 删除成功后统一刷新主列表 */
  function handleSuccess() {
    reload();
  }

  function getTableAction(record) {
    return [
      {
        label: '编辑',
        auth: 'wms:materialGroupMap:edit',
        onClick: handleEdit.bind(null, record),
      },
      {
        label: '删除',
        auth: 'wms:materialGroupMap:delete',
        popConfirm: {
          title: '删除后该分组将回到「未映射分组」列表，确定删除吗?',
          confirm: handleDelete.bind(null, record),
        },
      },
    ];
  }
</script>

<style lang="less" scoped>
  .tip {
    color: @text-color-secondary;
  }
</style>
