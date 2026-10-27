<!-- eslint-disable vue/multi-word-component-names -->
<template>
  <div class="wms-outbound">
    <BasicTable @register="registerTable">
      <template #tableTitle>
        <a-button type="primary" preIcon="ant-design:import-outlined" v-auth="'wms:outbound:add'" @click="openCreate('pick')">
          新建领料
        </a-button>
        <a-button type="primary" preIcon="ant-design:shopping-cart-outlined" v-auth="'wms:outbound:sales'" @click="openCreate('sales')">
          新建销售
        </a-button>
        <a-button type="primary" preIcon="ant-design:sync-outlined" v-auth="'wms:outbound:add'" :loading="syncingSub" @click="handleSyncSubcontract">
          同步委外订单
        </a-button>
        <a-button type="primary" preIcon="ant-design:experiment-outlined" v-auth="'wms:outbound:add'" @click="openCreate('sample')">
          新建样品
        </a-button>
      </template>
      <template #action="{ record }">
        <TableAction :actions="getTableAction(record)" />
      </template>
    </BasicTable>

    <OutboundCreateModal v-model:open="createOpen" :mode="createMode" @success="reload" />
    <OutboundDetailDrawer v-model:open="detailOpen" :outbound-id="detailId" @refresh="reload" />
  </div>
</template>

<script lang="ts" name="wms-outbound" setup>
  import { h, ref } from 'vue';
  import { Modal } from 'ant-design-vue';
  import { BasicTable, TableAction } from '/@/components/Table';
  import { useListPage } from '/@/hooks/system/useListPage';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { columns, searchFormSchema } from './outbound.data';
  import { allocateOutbound, closeOutbound, dispatchOutbound, listOutbound, syncOutbound, syncSubcontractOrders } from './outbound.api';
  import OutboundCreateModal from './components/OutboundCreateModal.vue';
  import OutboundDetailDrawer from './components/OutboundDetailDrawer.vue';

  const { createMessage } = useMessage();

  const { tableContext } = useListPage({
    tableProps: {
      api: listOutbound,
      columns,
      rowKey: 'id',
      formConfig: { schemas: searchFormSchema },
      actionColumn: { width: 300 },
    },
  });
  const [registerTable, { reload }] = tableContext;

  // ------------------------------------------------------------ 新建

  const createOpen = ref(false);
  const createMode = ref<'pick' | 'sales' | 'sample'>('pick');
  const syncingSub = ref(false);

  function openCreate(mode: 'pick' | 'sales' | 'sample') {
    createMode.value = mode;
    createOpen.value = true;
  }

  /** 外协出库：从金蝶同步委外订单为只读草稿（本期不含明细） */
  async function handleSyncSubcontract() {
    syncingSub.value = true;
    try {
      const res: any = await syncSubcontractOrders(50);
      const msg = res?.messages?.length ? res.messages.join('；') : `新增 ${res?.created ?? 0}，更新 ${res?.updated ?? 0}`;
      // 后端「跳过」也返回 Result.OK；这里按文案区分，避免把「没同步」显示成绿色成功
      if (String(msg).includes('跳过') || res?.queried === 0) {
        createMessage.warning(`未同步：${msg}`);
      } else {
        createMessage.success(`同步完成：${msg}`);
      }
      reload();
    } finally {
      syncingSub.value = false;
    }
  }

  // ------------------------------------------------------------ 详情

  const detailOpen = ref(false);
  const detailId = ref('');

  function openDetail(record: any) {
    detailId.value = record.id;
    detailOpen.value = true;
  }

  // ------------------------------------------------------------ 行内操作

  function getTableAction(record: any) {
    const actions: any[] = [{ label: '详情', onClick: () => openDetail(record) }];
    if (['待分配', '异常待人工'].includes(record.status)) {
      actions.push({
        label: '分配',
        auth: 'wms:outbound:allocate',
        onClick: () => handleAllocate(record),
      });
    }
    if (['已分配', '异常待人工'].includes(record.status)) {
      actions.push({
        label: '下发',
        auth: 'wms:outbound:dispatch',
        onClick: () => handleDispatch(record),
      });
    }
    if (record.syncStatus === '同步失败') {
      actions.push({
        label: '重试同步',
        auth: 'wms:outbound:sync',
        onClick: () => handleSync(record),
      });
    }
    if (!['已完成', '已关闭'].includes(record.status)) {
      actions.push({
        label: '关单',
        auth: 'wms:outbound:close',
        color: 'error',
        onClick: () => handleClose(record),
      });
    }
    return actions;
  }

  function handleAllocate(record: any) {
    Modal.confirm({
      title: `分配 ${record.outboundNo}？`,
      content: '系统将按先进先出锁定库存并分配库位/料箱；库存不足会转「异常待人工」并告警。',
      okText: '开始分配',
      cancelText: '取消',
      async onOk() {
        await allocateOutbound(record.id);
        createMessage.success('分配成功');
        reload();
      },
    });
  }

  function handleDispatch(record: any) {
    Modal.confirm({
      title: `下发 ${record.outboundNo}？`,
      content: '将向 WCS/RMS 下发设备任务；设备故障会转「异常待人工」并告警。',
      okText: '下发',
      cancelText: '取消',
      async onOk() {
        await dispatchOutbound(record.id);
        createMessage.success('已下发');
        reload();
      },
    });
  }

  async function handleSync(record: any) {
    try {
      await syncOutbound(record.id);
      createMessage.success('同步成功');
    } finally {
      reload();
    }
  }

  function handleClose(record: any) {
    let reason = '';
    Modal.confirm({
      title: `关单 ${record.outboundNo}？`,
      content: h('div', [
        h('p', '关单将释放已锁定库存，且不可恢复。'),
        h('textarea', {
          class: 'ant-input',
          rows: 3,
          placeholder: '请填写关单原因（必填）',
          onInput: (e: any) => (reason = e.target.value),
        }),
      ]),
      okText: '确认关单',
      okType: 'danger',
      cancelText: '取消',
      async onOk() {
        if (!reason || !reason.trim()) {
          createMessage.warning('关单原因不能为空');
          return Promise.reject();
        }
        await closeOutbound(record.id, { reason: reason.trim() });
        createMessage.success('已关单');
        reload();
      },
    });
  }
</script>

<style lang="less" scoped>
  .wms-outbound {
    padding: 16px;
  }
</style>
