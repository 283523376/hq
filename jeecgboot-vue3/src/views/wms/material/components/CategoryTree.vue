<template>
  <a-card :bordered="false" style="height: 100%">
    <a-spin :spinning="loading">
      <div class="wms-tree-header">
        <span class="wms-tree-title">物料分类</span>
        <a-button
          type="link"
          size="small"
          preIcon="ant-design:plus-outlined"
          v-auth="'wms:materialCategory:add'"
          @click="handleAddTop"
        >
          新增
        </a-button>
        <a-button
          type="link"
          size="small"
          preIcon="ant-design:sync-outlined"
          v-auth="'wms:materialCategory:edit'"
          :loading="syncing"
          @click="handleSyncKingdee"
        >
          同步金蝶
        </a-button>
      </div>
      <a-tree
        v-if="treeData.length"
        showLine
        :tree-data="treeData"
        :selected-keys="selectedKeys"
        v-model:expandedKeys="expandedKeys"
        @select="onSelect"
        style="overflow-y: auto; height: calc(100vh - 330px)"
      >
        <template #title="node">
          <span class="wms-tree-node">
            <span class="wms-tree-node__text">{{ node.title }}</span>
            <span v-if="node.id" class="wms-tree-node__actions">
              <a title="新增下级" v-auth="'wms:materialCategory:add'" @click.stop="handleAddSub(node)">
                <Icon icon="ant-design:plus-outlined" />
              </a>
              <a title="编辑" v-auth="'wms:materialCategory:edit'" @click.stop="handleEdit(node)">
                <Icon icon="ant-design:edit-outlined" />
              </a>
              <a title="停用" v-auth="'wms:materialCategory:edit'" @click.stop="handleDisable(node)">
                <Icon icon="ant-design:stop-outlined" />
              </a>
              <a title="删除" v-auth="'wms:materialCategory:delete'" @click.stop="handleDelete(node)">
                <Icon icon="ant-design:delete-outlined" />
              </a>
            </span>
          </span>
        </template>
      </a-tree>
      <a-empty v-else description="暂无分类" />
    </a-spin>
    <CategoryModal @register="registerModal" @success="handleModalSuccess" />
  </a-card>
</template>

<script lang="ts" name="wms-category-tree" setup>
  import { onMounted, ref } from 'vue';
  import { Modal } from 'ant-design-vue';
  import { useModal } from '/@/components/Modal';
  import { useMessage } from '/@/hooks/web/useMessage';
  import CategoryModal from './CategoryModal.vue';
  import { queryTreeList, deleteCategory, disableCategory, checkCategoryDelete, syncCategoryFromKingdee } from '../category.api';
  import { showDeleteBlocked } from '../../common/useWmsDeleteCheck';

  const emit = defineEmits(['select', 'refresh']);
  const { createMessage } = useMessage();
  const [registerModal, { openModal }] = useModal();

  const loading = ref(false);
  const syncing = ref(false);
  const treeData = ref<any[]>([]);
  /** 虚拟根：用于清除分类过滤 */
  const selectedKeys = ref<any[]>(['__all__']);
  const expandedKeys = ref<any[]>(['__all__']);

  /** 从金蝶同步物料分组映射；category-map 未配置时后端只空转并返回提示 */
  async function handleSyncKingdee() {
    syncing.value = true;
    try {
      const res: any = await syncCategoryFromKingdee();
      createMessage.success(res?.message || '同步完成');
      await loadTree();
      emit('refresh');
    } catch (e: any) {
      createMessage.error(e?.message || '同步失败');
    } finally {
      syncing.value = false;
    }
  }

  async function loadTree() {
    loading.value = true;
    try {
      const res: any = await queryTreeList();
      const list = Array.isArray(res) ? res : res?.result ?? [];
      treeData.value = [{ key: '__all__', id: '', title: '全部分类', children: mapTree(list) }];
    } finally {
      loading.value = false;
    }
  }

  function mapTree(list: any[]): any[] {
    return (list || []).map((n) => {
      const node: any = { key: n.key, id: n.key, title: n.title };
      if (n.children && n.children.length) {
        node.children = mapTree(n.children);
      }
      return node;
    });
  }

  onMounted(loadTree);

  /** 选中分类 → 通知右侧表格按分类过滤 */
  function onSelect(keys, info) {
    const key = keys && keys.length ? keys[0] : '';
    if (!key || key === '__all__') {
      selectedKeys.value = ['__all__'];
      emit('select', { id: '', title: '全部分类' });
      return;
    }
    selectedKeys.value = [key];
    const node = info?.selectedNodes?.[0];
    emit('select', { id: node?.id ?? key, title: node?.title ?? '' });
  }

  function handleAddTop() {
    openModal(true, { isUpdate: false });
  }

  function handleAddSub(node) {
    openModal(true, { isUpdate: false, record: { parentId: node.id } });
  }

  function handleEdit(node) {
    openModal(true, { isUpdate: true, record: { id: node.id, title: node.title } });
  }

  function handleDisable(node) {
    Modal.confirm({
      title: '确认停用',
      content: `确定停用分类「${node.title}」吗？`,
      okText: '确认',
      cancelText: '取消',
      onOk: async () => {
        await disableCategory({ id: node.id });
        createMessage.success('停用成功');
        await loadTree();
        emit('refresh');
      },
    });
  }

  async function handleDelete(node) {
    // 预检先行：分类下有物料/子分类时直接弹「无法删除」并列出原因；后端守卫仍在删除时兜底
    const check: any = await checkCategoryDelete({ id: node.id });
    if (!check?.deletable) {
      showDeleteBlocked(check?.blockers);
      return;
    }
    Modal.confirm({
      title: '确认删除',
      content: `确定删除分类「${node.title}」吗？`,
      okText: '确认',
      cancelText: '取消',
      onOk: async () => {
        await deleteCategory({ id: node.id }, () => {});
        createMessage.success('删除成功');
        await loadTree();
        emit('refresh');
      },
    });
  }

  async function handleModalSuccess() {
    await loadTree();
    emit('refresh');
  }

  defineExpose({ loadTree });
</script>

<style lang="less" scoped>
  .wms-tree-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 8px;

    .wms-tree-title {
      font-weight: 600;
    }
  }

  .wms-tree-node {
    display: inline-flex;
    align-items: center;
    justify-content: space-between;
    width: 100%;

    &__actions {
      display: none;
      margin-left: 8px;

      a {
        margin-left: 8px;
      }
    }
  }

  :deep(.ant-tree-node-content-wrapper:hover) .wms-tree-node__actions {
    display: inline-block;
  }
</style>
