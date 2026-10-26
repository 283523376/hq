<template>
  <a-card :bordered="false" style="height: 100%">
    <a-spin :spinning="loading">
      <div class="wms-tree-header">
        <span class="wms-tree-title">库区</span>
        <a-button
          type="link"
          size="small"
          preIcon="ant-design:plus-outlined"
          v-auth="'wms:warehouse:add'"
          @click="handleAddTop"
        >
          新增库区
        </a-button>
        <a-button type="link" size="small" preIcon="ant-design:sync-outlined" :loading="syncing" @click="handleSyncKingdee"> 同步金蝶 </a-button>
        <a-button type="link" size="small" preIcon="ant-design:database-outlined" :loading="initializing" @click="handleInitMasterData">
          初始化档案
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
            <!-- 类型节点是合成的分组，不可编辑/删除；只有库区节点才有操作 -->
            <span v-if="node.isWarehouse" class="wms-tree-node__actions">
              <a title="编辑" v-auth="'wms:warehouse:edit'" @click.stop="handleEdit(node)">
                <Icon icon="ant-design:edit-outlined" />
              </a>
              <a title="停用" v-auth="'wms:warehouse:edit'" @click.stop="handleDisable(node)">
                <Icon icon="ant-design:stop-outlined" />
              </a>
              <a title="删除" v-auth="'wms:warehouse:delete'" @click.stop="handleDelete(node)">
                <Icon icon="ant-design:delete-outlined" />
              </a>
            </span>
          </span>
        </template>
      </a-tree>
      <a-empty v-else description="暂无库区" />
    </a-spin>
    <WarehouseModal @register="registerModal" @success="handleModalSuccess" />
  </a-card>
</template>

<script lang="ts" name="wms-warehouse-tree" setup>
  import { onMounted, ref } from 'vue';
  import { Modal } from 'ant-design-vue';
  import { useModal } from '/@/components/Modal';
  import { useMessage } from '/@/hooks/web/useMessage';
  import WarehouseModal from './WarehouseModal.vue';
  import {
    queryTreeList,
    deleteWarehouse,
    disableWarehouse,
    checkWarehouseDelete,
    syncWarehouseFromKingdee,
    initWarehouseMasterData,
  } from '../warehouse.api';
  import { showDeleteBlocked } from '../../common/useWmsDeleteCheck';

  const emit = defineEmits(['select', 'refresh']);
  const { createMessage } = useMessage();
  const [registerModal, { openModal }] = useModal();

  const loading = ref(false);
  const syncing = ref(false);
  const initializing = ref(false);
  const treeData = ref<any[]>([]);
  /** 虚拟根：用于清除库区过滤 */
  const selectedKeys = ref<any[]>(['__all__']);
  const expandedKeys = ref<any[]>(['__all__']);
  /** 扁平化的库区节点，供生成弹窗带出维度 */
  const warehouseNodes = ref<any[]>([]);

  /** 从金蝶同步仓库档案；白名单未配置时后端直接返回 0 行 */
  async function handleSyncKingdee() {
    syncing.value = true;
    try {
      const res: any = await syncWarehouseFromKingdee();
      const vo = res?.result ?? {};
      createMessage.success(vo?.message || '同步完成');
      await loadTree();
      emit('refresh');
    } catch (e: any) {
      createMessage.error(e?.message || '同步失败');
    } finally {
      syncing.value = false;
    }
  }

  /** 初始化本地档案：LB/MJ 改名、CJ/TC 建档（幂等） */
  async function handleInitMasterData() {
    initializing.value = true;
    try {
      await initWarehouseMasterData();
      createMessage.success('初始化完成');
      await loadTree();
      emit('refresh');
    } catch (e: any) {
      createMessage.error(e?.message || '初始化失败');
    } finally {
      initializing.value = false;
    }
  }

  async function loadTree() {
    loading.value = true;
    try {
      const res: any = await queryTreeList();
      const list = Array.isArray(res) ? res : res?.result ?? [];
      const flat: any[] = [];
      const children = (list || []).map((typeNode: any) => {
        const typeKey = String(typeNode.key ?? '');
        return {
          key: typeKey,
          title: typeNode.title,
          // 类型节点的 key 带 "type:" 前缀，与库区真实 id 区分开
          isWarehouse: false,
          children: (typeNode.children || []).map((w: any) => {
            const node = {
              key: w.key,
              title: w.title,
              typeKey,
              typeName: typeNode.title,
              isWarehouse: true,
            };
            flat.push(node);
            return node;
          }),
        };
      });
      warehouseNodes.value = flat;
      treeData.value = [{ key: '__all__', title: '全部库区', isWarehouse: false, children }];
      // **默认展开全部分组**：这是两层树，而「批量生成」「新增库位」都要先选中
      // **具体库区**才可用。若只展开根、把类型分组收起，用户第一眼只看到
      // 「立库/敏捷仓/线边柜」，按钮全是灰的 —— 会以为按钮坏了。
      // （浏览器实测发现的易用性问题，非样式偏好。）
      expandedKeys.value = ['__all__', ...children.map((c) => c.key)];
    } finally {
      loading.value = false;
    }
  }

  onMounted(loadTree);

  /** 选中 → 通知右侧表格过滤。类型节点按类型过滤，库区节点按 id 过滤。 */
  function onSelect(keys, info) {
    const key = keys && keys.length ? keys[0] : '';
    if (!key || key === '__all__') {
      selectedKeys.value = ['__all__'];
      emit('select', {});
      return;
    }
    selectedKeys.value = [key];
    const node = info?.selectedNodes?.[0];
    if (String(key).startsWith('type:')) {
      emit('select', { warehouseType: String(key).substring('type:'.length) });
    } else {
      emit('select', {
        warehouseId: node?.key ?? key,
        warehouseName: node?.title ?? '',
        warehouseType: node?.typeKey ? String(node.typeKey).substring('type:'.length) : '',
      });
    }
  }

  function handleAddTop() {
    openModal(true, { isUpdate: false });
  }

  function handleEdit(node) {
    openModal(true, { isUpdate: true, record: { id: node.key } });
  }

  function handleDisable(node) {
    Modal.confirm({
      title: '确认停用',
      content: `确定停用库区「${node.title}」吗？停用后不能向其生成库位或绑定设备。`,
      okText: '确认',
      cancelText: '取消',
      onOk: async () => {
        await disableWarehouse({ id: node.key });
        createMessage.success('停用成功');
        await loadTree();
        emit('refresh');
      },
    });
  }

  async function handleDelete(node) {
    // 预检先行：库区下有库位/已绑定设备时直接弹「无法删除」并列出原因；后端守卫仍在删除时兜底
    const check: any = await checkWarehouseDelete({ id: node.key });
    if (!check?.deletable) {
      showDeleteBlocked(check?.blockers);
      return;
    }
    Modal.confirm({
      title: '确认删除',
      content: `确定删除库区「${node.title}」吗？`,
      okText: '确认',
      cancelText: '取消',
      onOk: async () => {
        await deleteWarehouse({ id: node.key }, () => {});
        createMessage.success('删除成功');
        if (selectedKeys.value[0] === node.key) {
          selectedKeys.value = ['__all__'];
          emit('select', {});
        }
        await loadTree();
        emit('refresh');
      },
    });
  }

  async function handleModalSuccess() {
    await loadTree();
    emit('refresh');
  }

  defineExpose({ loadTree, warehouseNodes });
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