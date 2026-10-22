<template>
  <a-drawer v-model:open="visible" title="调拨单详情" width="1080" destroy-on-close>
    <template #extra>
      <a-button size="small" preIcon="ant-design:reload-outlined" @click="load">刷新</a-button>
    </template>

    <a-spin :spinning="loading">
      <template v-if="detail">
        <a-alert
          v-if="order.status === '待ERP审核'"
          type="warning"
          show-icon
          style="margin-bottom: 12px"
          message="待ERP审核（实物已完成）"
          :description="`实物已完成，等待金蝶直接调拨单审批到 C 才算「已完成」。已等待约 ${d.waitingHours ?? 0} 小时。`"
        />
        <a-alert
          v-if="order.status === '异常'"
          type="error"
          show-icon
          style="margin-bottom: 12px"
          message="异常"
          :description="order.rejectReason || order.lastSyncError || '请检查设备回执、库位回执或 ERP 审批结果'"
        />

        <a-descriptions :column="3" size="small" bordered>
          <a-descriptions-item label="调拨单号">{{ order.transferNo }}</a-descriptions-item>
          <a-descriptions-item label="调拨模式">{{ order.transferMode }}</a-descriptions-item>
          <a-descriptions-item label="状态"><a-tag>{{ order.status }}</a-tag></a-descriptions-item>
          <a-descriptions-item label="调出库区">{{ order.outWarehouseCode || '-' }}</a-descriptions-item>
          <a-descriptions-item label="调入库区">{{ order.inWarehouseCode || '-' }}</a-descriptions-item>
          <a-descriptions-item label="区域">{{ order.outZoneCode || '-' }} → {{ order.inZoneCode || '-' }}</a-descriptions-item>
          <a-descriptions-item label="实物状态">{{ order.execStatus }}</a-descriptions-item>
          <a-descriptions-item label="ERP 状态">
            {{ order.syncStatus }}{{ order.erpDocumentStatus ? ` (${order.erpDocumentStatus})` : '' }}
          </a-descriptions-item>
          <a-descriptions-item label="金蝶单号">{{ order.erpBillNo || '-' }}</a-descriptions-item>
          <a-descriptions-item label="创建时间">{{ order.createTime || '-' }}</a-descriptions-item>
          <a-descriptions-item label="实物完成">{{ order.execFinishTime || '-' }}</a-descriptions-item>
          <a-descriptions-item label="审核通过">{{ order.erpApproveTime || '-' }}</a-descriptions-item>
          <a-descriptions-item v-if="order.closeReason" label="关单原因" :span="3">{{ order.closeReason }}</a-descriptions-item>
          <a-descriptions-item v-if="order.remark" label="备注" :span="3">{{ order.remark }}</a-descriptions-item>
        </a-descriptions>

        <a-divider orientation="left">双时钟</a-divider>
        <a-timeline>
          <a-timeline-item>
            <b>建单</b>：{{ order.createTime || '-' }}（exec=待执行 / sync=待同步）
          </a-timeline-item>
          <a-timeline-item :color="execDone ? 'green' : 'blue'">
            <b>实物</b>：{{ order.execStatus }}<span v-if="order.execFinishTime">（完成于 {{ order.execFinishTime }}）</span>
          </a-timeline-item>
          <a-timeline-item :color="order.syncStatus === '已审核' ? 'green' : 'gray'">
            <b>ERP</b>：{{ order.syncStatus }}<span v-if="order.erpDocumentStatus">（金蝶 {{ order.erpDocumentStatus }}）</span>
          </a-timeline-item>
          <a-timeline-item :color="order.status === '已完成' ? 'green' : 'gray'">
            <b>单据</b>：{{ order.status }}
            <span v-if="order.status === '待ERP审核'">—— 已等 {{ d.waitingHours ?? 0 }} 小时</span>
          </a-timeline-item>
        </a-timeline>

        <a-divider orientation="left">调拨明细</a-divider>
        <a-table :data-source="d.details" :columns="detailColumns" :pagination="false" size="small" rowKey="id" />

        <template v-if="d.tasks && d.tasks.length">
          <a-divider orientation="left">搬运任务（同仓区域）</a-divider>
          <a-table :data-source="d.tasks" :columns="taskColumns" :pagination="false" size="small" rowKey="id" />
        </template>

        <a-divider orientation="left">操作</a-divider>
        <a-space wrap>
          <a-button v-if="order.status === '待执行'" type="primary" v-auth="'wms:transfer:dispatch'" @click="act(dispatchTransfer)">
            分配并下发
          </a-button>
          <template v-if="order.transferMode === '跨仓'">
            <a-button v-if="order.execStatus === '执行中'" v-auth="'wms:transfer:execute'" @click="act(departTransfer)">
              出发确认
            </a-button>
            <a-button v-if="order.execStatus === '在途'" v-auth="'wms:transfer:execute'" @click="act(arriveTransfer)">
              到达确认
            </a-button>
            <a-button v-if="order.execStatus === '待入库'" v-auth="'wms:transfer:execute'" @click="act(putawayTransfer)">
              上架确认
            </a-button>
          </template>
          <a-button v-auth="'wms:transfer:sync'" disabled title="Phase B：需先确认金蝶审批口径">提交金蝶（Phase B）</a-button>
          <a-button v-auth="'wms:transfer:reverse'" disabled title="Phase B：需先确认驳回处置责任人">反向冲销（Phase B）</a-button>
          <a-button v-if="!isTerminal" danger v-auth="'wms:transfer:close'" @click="handleClose">关单</a-button>
        </a-space>
      </template>
    </a-spin>
  </a-drawer>
</template>

<script lang="ts" setup>
  import { computed, h, ref, watch } from 'vue';
  import { Modal } from 'ant-design-vue';
  import { useMessage } from '/@/hooks/web/useMessage';
  import {
    arriveTransfer,
    closeTransfer,
    departTransfer,
    dispatchTransfer,
    getTransferDetail,
    putawayTransfer,
  } from '../transfer.api';

  const props = defineProps<{ open: boolean; transferId: string }>();
  const emit = defineEmits(['update:open', 'refresh']);

  const { createMessage } = useMessage();

  const visible = computed({
    get: () => props.open,
    set: (v) => emit('update:open', v),
  });

  const loading = ref(false);
  const detail = ref<any>(null);
  const order = computed(() => detail.value?.order || {});
  const execDone = computed(() => order.value.execStatus === '实物完成');
  const isTerminal = computed(() => ['已完成', '已关闭'].includes(order.value.status));

  const detailColumns = [
    { title: '行号', dataIndex: 'lineNo', width: 60 },
    { title: '物料编码', dataIndex: 'materialCode', width: 160 },
    { title: '物料名称', dataIndex: 'materialName', width: 200 },
    { title: '批次', dataIndex: 'batchNo', width: 120 },
    { title: '料箱', dataIndex: 'containerCode', width: 140 },
    { title: '数量', dataIndex: 'transferQty', width: 100 },
    { title: '调出库位', dataIndex: 'outLocationCode', width: 140 },
    { title: '调入库位', dataIndex: 'inLocationCode', width: 140 },
  ];

  const taskColumns = [
    { title: '任务号', dataIndex: 'taskNo', width: 200 },
    { title: '容器', dataIndex: 'containerCode', width: 140 },
    { title: '目标区域', dataIndex: 'zoneCode', width: 120 },
    { title: '任务状态', dataIndex: 'taskStatus', width: 100 },
    { title: '模拟', dataIndex: 'mocked', width: 70 },
    { title: '失败原因', dataIndex: 'taskError' },
  ];

  watch(
    () => [props.open, props.transferId],
    () => {
      if (props.open && props.transferId) load();
    }
  );

  async function load() {
    if (!props.transferId) return;
    loading.value = true;
    try {
      const res: any = await getTransferDetail(props.transferId);
      detail.value = res;
    } finally {
      loading.value = false;
    }
  }

  async function act(action: (id: string) => Promise<any>) {
    await action(props.transferId);
    createMessage.success('操作成功');
    await load();
    emit('refresh');
  }

  function handleClose() {
    let reason = '';
    Modal.confirm({
      title: `关单 ${order.value.transferNo}？`,
      content: h('div', [
        h('p', '关单将释放已锁定库存并取消设备任务（尽力而为），不可恢复。'),
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
        await closeTransfer(props.transferId, reason.trim());
        createMessage.success('已关单');
        await load();
        emit('refresh');
      },
    });
  }
</script>
