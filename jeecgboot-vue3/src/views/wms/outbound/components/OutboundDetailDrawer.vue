<template>
  <a-drawer v-model:open="open" title="出库单详情" width="1100" destroy-on-close>
    <template #extra>
      <a-button size="small" preIcon="ant-design:reload-outlined" @click="load">刷新</a-button>
    </template>

    <a-spin :spinning="loading">
      <template v-if="detail">
        <a-alert
          v-if="order.status === '复核异常'"
          type="error"
          show-icon
          class="wms-outbound-detail__alert"
          message="复核不一致"
          :description="reviewExceptionText"
        />
        <a-alert
          v-if="order.status === '异常待人工'"
          type="error"
          show-icon
          class="wms-outbound-detail__alert"
          message="异常待人工"
          :description="order.lastSyncError || '请检查库存、库位/料箱或设备状态后重新分配/下发'"
        />
        <a-alert
          v-if="order.status === '已完成' && order.syncStatus === '同步失败'"
          type="warning"
          show-icon
          class="wms-outbound-detail__alert"
          message="出库单同步失败"
          :description="order.lastSyncError || '可点击「重新同步」重试'"
        />

        <a-descriptions :column="3" size="small" bordered>
          <a-descriptions-item label="出库单号">{{ order.outboundNo }}</a-descriptions-item>
          <a-descriptions-item label="出库类型">{{ order.outboundType }}</a-descriptions-item>
          <a-descriptions-item label="状态">
            <a-tag>{{ order.status }}</a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="工单号">{{ order.workOrderNo || '-' }}</a-descriptions-item>
          <a-descriptions-item label="销售订单号">{{ order.salesOrderNo || '-' }}</a-descriptions-item>
          <a-descriptions-item label="客户">{{ order.customerName || order.customerCode || '-' }}</a-descriptions-item>
          <a-descriptions-item label="需求来源">{{ order.requirementSource || '-' }}</a-descriptions-item>
          <a-descriptions-item label="同步状态">{{ order.syncStatus || '-' }}</a-descriptions-item>
          <a-descriptions-item label="创建时间">{{ order.createTime || '-' }}</a-descriptions-item>
          <a-descriptions-item label="提交时间">{{ order.submitTime || '-' }}</a-descriptions-item>
          <a-descriptions-item label="完成时间">{{ order.completeTime || '-' }}</a-descriptions-item>
          <a-descriptions-item v-if="order.closeReason" label="关单原因" :span="3">{{ order.closeReason }}</a-descriptions-item>
          <a-descriptions-item v-if="order.remark" label="备注" :span="3">{{ order.remark }}</a-descriptions-item>
        </a-descriptions>

        <div class="wms-outbound-detail__title">需求明细</div>
        <a-table :data-source="detail.details" :columns="detailColumns" :pagination="false" size="small" row-key="id" bordered>
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'material'">{{ record.materialCode }} {{ record.materialName }}</template>
            <template v-else-if="column.key === 'qty'">
              {{ record.requiredQty }} / {{ record.allocatedQty }} / {{ record.actualQty ?? '-' }}
            </template>
          </template>
        </a-table>

        <div class="wms-outbound-detail__title">分配与任务</div>
        <a-table
          :data-source="detail.allocations"
          :columns="allocationColumns"
          :pagination="false"
          size="small"
          row-key="id"
          bordered
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'qty'">{{ record.allocatedQty }} / {{ record.actualQty ?? '-' }}</template>
            <template v-else-if="column.key === 'location'">{{ record.locationCode || '-' }}</template>
            <template v-else-if="column.key === 'container'">{{ record.containerCode || '-' }}</template>
            <template v-else-if="column.key === 'task'">
              <div>{{ record.taskNo || '-' }}</div>
              <a-tag :color="taskStatusColor[record.taskStatus] || 'default'">{{ record.taskStatus || '-' }}</a-tag>
            </template>
            <template v-else-if="column.key === 'reviewStatus'">
              <a-tag :color="reviewStatusColor[record.reviewStatus] || 'default'">{{ record.reviewStatus || '-' }}</a-tag>
            </template>
            <template v-else-if="column.key === 'scanCode'">
              <a-input
                v-if="canReview"
                v-model:value="record._scanCode"
                size="small"
                placeholder="扫描物料/料箱码"
                allow-clear
              />
              <span v-else>{{ record.scanCode || '-' }}</span>
            </template>
            <template v-else-if="column.key === 'actualQty'">
              <a-input-number
                v-if="canReview"
                v-model:value="record._actualQty"
                size="small"
                :min="0"
                :precision="3"
                style="width: 100%"
              />
              <span v-else>{{ record.actualQty ?? '-' }}</span>
            </template>
            <template v-else-if="column.key === 'reviewNote'">{{ record.reviewNote || '-' }}</template>
            <template v-else-if="column.key === 'taskError'">
              <span v-if="record.taskError" class="wms-outbound-detail__error">{{ record.taskError }}</span>
              <span v-else>-</span>
            </template>
          </template>
        </a-table>

        <template v-if="canReview">
          <div class="wms-outbound-detail__review">
            <a-checkbox v-model:checked="releaseMismatch">放行不一致项</a-checkbox>
            <a-input
              v-if="releaseMismatch"
              v-model:value="releaseReason"
              placeholder="请填写放行原因（必填）"
              style="width: 360px; margin-left: 12px"
            />
          </div>
        </template>

        <div class="wms-outbound-detail__actions">
          <a-button v-if="canAllocate" type="primary" :loading="acting" v-auth="'wms:outbound:allocate'" @click="handleAllocate">
            分配
          </a-button>
          <a-button v-if="canDispatch" type="primary" :loading="acting" v-auth="'wms:outbound:dispatch'" @click="handleDispatch">
            下发
          </a-button>
          <a-button v-if="canReview" type="primary" :loading="acting" v-auth="'wms:outbound:review'" @click="handleReview">
            提交复核
          </a-button>
          <a-button
            v-if="canConfirmPick"
            type="primary"
            :loading="acting"
            v-auth="'wms:outbound:confirm'"
            @click="handleConfirmPick"
          >
            取料确认
          </a-button>
          <a-button v-if="canConfirm" type="primary" :loading="acting" v-auth="'wms:outbound:confirm'" @click="handleConfirm">
            确认出库
          </a-button>
          <a-button v-if="canResync" :loading="acting" v-auth="'wms:outbound:sync'" @click="handleResync">重新同步</a-button>
          <a-button v-if="canClose" danger :loading="acting" v-auth="'wms:outbound:close'" @click="handleClose">关单</a-button>
        </div>
      </template>
    </a-spin>
  </a-drawer>
</template>

<script lang="ts" setup>
  import { computed, h, ref, watch } from 'vue';
  import { Modal } from 'ant-design-vue';
  import { useMessage } from '/@/hooks/web/useMessage';
  import {
    allocateOutbound,
    closeOutbound,
    confirmOutbound,
    confirmPick,
    dispatchOutbound,
    getOutboundDetail,
    reviewOutbound,
    syncOutbound,
  } from '../outbound.api';

  const props = defineProps<{ outboundId: string }>();
  const emit = defineEmits(['refresh']);
  const open = defineModel<boolean>('open', { default: false });

  const { createMessage } = useMessage();

  const loading = ref(false);
  const acting = ref(false);
  const detail = ref<any>(null);
  const releaseMismatch = ref(false);
  const releaseReason = ref('');

  const order = computed(() => detail.value?.order || {});
  const allocations = computed<any[]>(() => detail.value?.allocations || []);

  const taskStatusColor: Record<string, string> = {
    未下发: 'default',
    已受理: 'blue',
    执行中: 'processing',
    已完成: 'success',
    失败: 'error',
    已关闭: 'default',
  };
  const reviewStatusColor: Record<string, string> = {
    待复核: 'orange',
    一致: 'success',
    不一致: 'error',
    已放行: 'blue',
  };

  const detailColumns = [
    { title: '行号', key: 'lineNo', dataIndex: 'lineNo', width: 70 },
    { title: '物料', key: 'material', width: 240 },
    { title: '批次号', key: 'batchNo', dataIndex: 'batchNo', width: 130 },
    { title: '需求 / 已分配 / 实收', key: 'qty', width: 180 },
    { title: '单位', key: 'unit', dataIndex: 'unit', width: 80 },
  ];

  const allocationColumns = computed(() => {
    const cols: any[] = [
      { title: '库位', key: 'location', width: 120 },
      { title: '料箱/载具', key: 'container', width: 140 },
      { title: '已分配 / 实收', key: 'qty', width: 140 },
      { title: '任务号 / 状态', key: 'task', width: 180 },
      { title: '复核状态', key: 'reviewStatus', width: 100 },
      { title: '扫描码', key: 'scanCode', width: 170 },
      { title: '实收数量', key: 'actualQty', width: 130 },
      { title: '复核说明', key: 'reviewNote', width: 150 },
    ];
    if (order.value.status === '异常待人工') {
      cols.push({ title: '任务失败原因', key: 'taskError', width: 200 });
    }
    return cols;
  });

  const canReview = computed(() => ['待复核', '复核异常'].includes(order.value.status));
  const canAllocate = computed(() => ['待分配', '异常待人工'].includes(order.value.status));
  // 设备故障后修复设备，应优先「重新下发」而不是重新分配（重新分配会重新锁库存）
  const canDispatch = computed(() => ['已分配', '异常待人工'].includes(order.value.status));
  const canConfirmPick = computed(
    () => order.value.outboundType === '生产领料' && order.value.status === '待复核'
  );
  // 样品出库与销售出库共用「复核后确认」路径（样品不走生产领料的拣货确认）
  const canConfirm = computed(
    () => ['销售出库', '样品出库'].includes(order.value.outboundType) && order.value.status === '待复核',
  );
  const canResync = computed(() => order.value.status === '已完成' && order.value.syncStatus === '同步失败');
  const canClose = computed(() => !['已完成', '已关闭'].includes(order.value.status));

  const reviewExceptionText = computed(() => {
    const mismatched = allocations.value.filter((a) => a.reviewStatus === '不一致');
    if (!mismatched.length) {
      return '请核对扫描码与数量后重新复核';
    }
    return `以下分配行不一致：${mismatched
      .map((a) => `${a.locationCode || a.containerCode || a.taskNo}（扫描：${a.scanCode || '空'}）`)
      .join('；')}`;
  });

  async function load() {
    if (!props.outboundId) {
      return;
    }
    loading.value = true;
    try {
      const res: any = await getOutboundDetail(props.outboundId);
      detail.value = res;
      releaseMismatch.value = false;
      releaseReason.value = '';
      // 分配时 actual_qty 只是占位 0，输入框默认必须回落到分配数量；
      // 若原样提交 0，后端会当成「操作员显式确认 0」而不扣库存。
      (res.allocations || []).forEach((a: any) => {
        a._scanCode = a.scanCode || '';
        const stored = Number(a.actualQty ?? 0);
        a._actualQty = stored > 0 ? stored : Number(a.allocatedQty ?? 0);
      });
    } finally {
      loading.value = false;
    }
  }

  watch(
    () => [open.value, props.outboundId],
    ([v]) => {
      if (v) {
        void load();
      }
    },
    { immediate: true }
  );

  function afterAction() {
    emit('refresh');
    void load();
  }

  async function withAction(fn: () => Promise<void>) {
    if (acting.value) {
      return;
    }
    acting.value = true;
    try {
      await fn();
    } finally {
      // 失败也要刷新：复核不一致时后端已落库扫描码/实收数量，旧界面会误导操作员
      afterAction();
      acting.value = false;
    }
  }

  function buildReviewDetails() {
    return allocations.value.map((a) => ({
      allocationId: a.id,
      scanCode: a._scanCode || undefined,
      actualQty: a._actualQty != null ? Number(a._actualQty) : undefined,
    }));
  }

  function buildConfirmDetails() {
    return allocations.value.map((a) => ({
      allocationId: a.id,
      actualQty: a._actualQty != null ? Number(a._actualQty) : undefined,
    }));
  }

  function handleAllocate() {
    Modal.confirm({
      title: `分配 ${order.value.outboundNo}？`,
      content: '系统将按先进先出锁定库存并分配库位/料箱；库存不足会转「异常待人工」并告警。',
      okText: '开始分配',
      cancelText: '取消',
      async onOk() {
        await withAction(async () => {
          await allocateOutbound(order.value.id);
          createMessage.success('分配成功');
        });
      },
    });
  }

  function handleDispatch() {
    Modal.confirm({
      title: `下发 ${order.value.outboundNo}？`,
      content: '将向 WCS/RMS 下发设备任务；设备故障会转「异常待人工」并告警。',
      okText: '下发',
      cancelText: '取消',
      async onOk() {
        await withAction(async () => {
          await dispatchOutbound(order.value.id);
          createMessage.success('已下发');
        });
      },
    });
  }

  async function handleReview() {
    if (releaseMismatch.value && (!releaseReason.value || !releaseReason.value.trim())) {
      createMessage.warning('放行不一致项时必须填写放行原因');
      return;
    }
    await withAction(async () => {
      await reviewOutbound(order.value.id, {
        details: buildReviewDetails(),
        releaseMismatch: releaseMismatch.value,
        releaseReason: releaseMismatch.value ? releaseReason.value.trim() : undefined,
      });
      createMessage.success('复核通过');
    });
  }

  function handleConfirmPick() {
    Modal.confirm({
      title: `取料确认 ${order.value.outboundNo}？`,
      content: '确认后扣减库存并同步金蝶生产领料单；库存不足或设备未完成时不能确认。',
      okText: '确认取料',
      cancelText: '取消',
      async onOk() {
        await withAction(async () => {
          const vo: any = await confirmPick(order.value.id, { details: buildConfirmDetails() });
          if (vo?.message) {
            createMessage.info(vo.message, 6);
          } else {
            createMessage.success('取料确认成功');
          }
        });
      },
    });
  }

  async function handleConfirm() {
    await withAction(async () => {
      const vo: any = await confirmOutbound(order.value.id, { details: buildConfirmDetails() });
      createMessage.success(vo?.message || '出库完成');
    });
  }

  async function handleResync() {
    await withAction(async () => {
      await syncOutbound(order.value.id);
      createMessage.success('同步成功');
    });
  }

  function handleClose() {
    let reason = '';
    Modal.confirm({
      title: `关单 ${order.value.outboundNo}？`,
      content: h('div', [
        h('p', '关单将释放已锁定库存并取消设备任务，且不可恢复。'),
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
        await withAction(async () => {
          await closeOutbound(order.value.id, { reason: reason.trim() });
          createMessage.success('已关单');
        });
      },
    });
  }

  defineExpose({ load });
</script>

<style lang="less" scoped>
  .wms-outbound-detail__title {
    margin: 16px 0 8px;
    font-weight: 600;
  }

  .wms-outbound-detail__alert {
    margin-bottom: 12px;
  }

  .wms-outbound-detail__review {
    display: flex;
    align-items: center;
    margin-top: 12px;
  }

  .wms-outbound-detail__actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 20px;
    padding-top: 16px;
    border-top: 1px solid #f0f0f0;
  }

  .wms-outbound-detail__error {
    color: #ff4d4f;
  }
</style>
