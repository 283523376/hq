<!-- eslint-disable vue/multi-word-component-names -->
<template>
  <div class="wms-outbound">
    <a-alert
      v-if="manualOnly"
      type="warning"
      show-icon
      class="wms-outbound__alert"
      message="自动同步已关闭：单据停在「待同步」，请在下方列表逐单点「同步金蝶」"
    />
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
        <a-button type="primary" preIcon="ant-design:thunderbolt-outlined" v-auth="'wms:outbound:add'" :loading="generatingDemands" @click="handleGeneratePickDemands">
          生成领料待办
        </a-button>
        <a-button type="primary" preIcon="ant-design:carry-out-outlined" v-auth="'wms:outbound:sales'" :loading="generatingSales" @click="handleGenerateSalesDemands">
          生成销售待办
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
  import { h, onMounted, ref } from 'vue';
  import { Modal } from 'ant-design-vue';
  import { BasicTable, TableAction } from '/@/components/Table';
  import { useListPage } from '/@/hooks/system/useListPage';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { useWmsSyncMode } from '../common/useWmsSyncMode';
  import { columns, searchFormSchema, setDeviceNameMap } from './outbound.data';
  import {
    allocateOutbound,
    cancelPick,
    closeOutbound,
    dispatchOutbound,
    generatePickDemands,
    generateSalesDemands,
    listDeviceOptions,
    listOutbound,
    syncOutbound,
    syncSubcontractOrders,
  } from './outbound.api';
  import OutboundCreateModal from './components/OutboundCreateModal.vue';
  import OutboundDetailDrawer from './components/OutboundDetailDrawer.vue';

  const { createMessage } = useMessage();
  /** 自动同步关闭时，待同步单据需要手工「同步金蝶」 */
  const { manualOnly } = useWmsSyncMode();

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

  /**
   * 设备名称：出库单只落 deviceId，列表「设备」列靠设备档案映射。
   *
   * 先加载设备再 reload 一次，保证首屏就能看到名称而不是裸 ID。
   */
  onMounted(async () => {
    try {
      const res: any = await listDeviceOptions(undefined, 500);
      setDeviceNameMap(res?.records || []);
      reload();
    } catch (e) {
      // 设备档案加载失败只影响「设备」列显示，不影响列表本身
    }
  });

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

  /** 出库单反向生成：按金蝶用料清单自动补领料待办（无缺口/无用料清单的工单自动跳过） */
  const generatingDemands = ref(false);
  async function handleGeneratePickDemands() {
    generatingDemands.value = true;
    try {
      const res: any = await generatePickDemands();
      const vo = res?.result || {};
      const msg = vo.message || '生成完成';
      if (vo.created > 0) {
        createMessage.success(`${msg}：${(vo.createdOrders || []).join('、')}`);
      } else {
        createMessage.warning(msg);
      }
      reload();
    } catch (e: any) {
      createMessage.error(e?.message || '生成失败');
    } finally {
      generatingDemands.value = false;
    }
  }

  /** 销售出库待办：按金蝶发货通知单（已审核 C）生成；仓库映射未配置时后端会全部跳过并给原因 */
  const generatingSales = ref(false);
  async function handleGenerateSalesDemands() {
    generatingSales.value = true;
    try {
      const res: any = await generateSalesDemands();
      const vo = res?.result || {};
      const msg = vo.message || '生成完成';
      if (vo.created > 0) {
        createMessage.success(`${msg}：${(vo.createdOrders || []).join('、')}`);
      } else {
        createMessage.warning(`${msg}${vo.skippedReasons?.length ? '；' + vo.skippedReasons[0] : ''}`);
      }
      reload();
    } catch (e: any) {
      createMessage.error(e?.message || '生成失败');
    } finally {
      generatingSales.value = false;
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
    // 自动同步关闭时后台不再续跑，待同步的单据要靠这里手工推；同步失败则两种模式都可重试
    if (record.syncStatus === '同步失败' || (manualOnly.value && record.syncStatus === '待同步')) {
      actions.push({
        label: manualOnly.value ? '同步金蝶' : '重试同步',
        auth: 'wms:outbound:sync',
        onClick: () => handleSync(record),
      });
    }
    // 取消领料：只对生产领料的可撤销状态开放，其余状态由后端状态机拒绝
    if (record.outboundType === '生产领料' && ['草稿', '待分配', '已分配', '异常待人工'].includes(record.status)) {
      actions.push({
        label: '取消领料',
        auth: 'wms:outbound:pick:cancel',
        color: 'error',
        onClick: () => handleCancelPick(record),
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

  function handleCancelPick(record: any) {
    let reason = '';
    Modal.confirm({
      title: `取消领料 ${record.outboundNo}？`,
      content: h('div', [
        h('p', '取消后将通知计划员重新安排领料；请填写原因（领料物料不符 / 库存不足等）。'),
        h('textarea', {
          class: 'ant-input',
          rows: 3,
          placeholder: '请填写取消原因（必填）',
          onInput: (e: any) => (reason = e.target.value),
        }),
      ]),
      okText: '确认取消领料',
      okType: 'danger',
      cancelText: '返回',
      async onOk() {
        if (!reason || !reason.trim()) {
          createMessage.warning('取消原因不能为空');
          return Promise.reject();
        }
        await cancelPick(record.id, { reason: reason.trim() });
        createMessage.success('已取消领料，计划员将收到通知');
        reload();
      },
    });
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

  .wms-outbound__alert {
    margin-bottom: 12px;
  }
</style>
