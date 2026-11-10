<!-- eslint-disable vue/multi-word-component-names -->
<template>
  <div class="wms-production">
    <a-alert
      v-if="manualOnly"
      type="warning"
      show-icon
      class="wms-production__alert"
      message="自动同步已关闭：单据停在「待同步」，请在下方列表逐单点「同步金蝶」"
    />
    <a-card :bordered="false" size="small" title="工单作业台（工单由ERP同步，录入单号即自动同步/查询）" class="wms-production__workbench">
      <!-- 不用 a-form layout="inline"：inline 表单项在窄容器里会换行，导致标签与输入框错位 -->
      <div class="wms-production__scan">
        <label class="wms-production__scan-label" for="wms-work-order-no">工单号</label>
        <a-input
          id="wms-work-order-no"
          ref="workOrderInputRef"
          v-model:value="workOrderNo"
          class="wms-production__scan-input"
          placeholder="录入ERP生产订单号，回车查询（本地无单自动从ERP同步）"
          allow-clear
          @press-enter="handleScan"
        />
        <a-button type="primary" :loading="scanning" v-auth="'wms:production:scan'" @click="handleScan">
          查询工单
        </a-button>
      </div>

      <a-descriptions v-if="workOrder" :column="4" size="small" bordered class="wms-production__info">
        <a-descriptions-item label="工单号">{{ workOrder.workOrderNo }}</a-descriptions-item>
        <a-descriptions-item label="产品类型">
          <a-tag :color="workOrder.productType === '成品' ? 'blue' : 'green'">{{ workOrder.productType }}</a-tag>
        </a-descriptions-item>
        <a-descriptions-item label="物料">{{ workOrder.materialCode }} {{ workOrder.materialName }}</a-descriptions-item>
        <a-descriptions-item label="批次号">{{ workOrder.batchNo }}</a-descriptions-item>
        <a-descriptions-item label="计划数量">{{ workOrder.planQty }}</a-descriptions-item>
        <a-descriptions-item label="已占用">{{ workOrder.inboundedQty }}</a-descriptions-item>
        <a-descriptions-item label="剩余可入库">
          <span :class="{ 'wms-production__remaining--empty': Number(workOrder.remainingQty) <= 0 }">
            {{ workOrder.remainingQty }}
          </span>
        </a-descriptions-item>
        <a-descriptions-item label="绑定标识">{{ workOrder.identType }}</a-descriptions-item>
      </a-descriptions>

      <a-form v-if="workOrder" layout="inline" class="wms-production__form">
        <a-form-item label="良品数量">
          <a-input-number v-model:value="form.goodQty" :min="0" :precision="3" style="width: 140px" />
        </a-form-item>
        <a-form-item label="不良品数量">
          <a-input-number v-model:value="form.badQty" :min="0" :precision="3" style="width: 140px" />
        </a-form-item>
        <a-form-item label="不良原因">
          <a-input v-model:value="form.defectRemark" placeholder="有不良品时填写" style="width: 180px" />
        </a-form-item>
        <a-form-item label="批次号">
          <a-input v-model:value="form.batchNo" style="width: 180px" />
        </a-form-item>
        <a-form-item :label="carrierLabel">
          <a-input
            ref="carrierInputRef"
            v-model:value="form.carrierCode"
            :placeholder="carrierPlaceholder"
            style="width: 240px"
            @press-enter="handleSubmit"
          />
        </a-form-item>
        <a-form-item>
          <a-button type="primary" :loading="submitting" v-auth="'wms:production:submit'" @click="handleSubmit">
            提交入库
          </a-button>
        </a-form-item>
      </a-form>
    </a-card>

    <BasicTable @register="registerTable">
      <template #tableTitle>
        <a-button preIcon="ant-design:reload-outlined" @click="reload()">刷新</a-button>
        <a-button
          preIcon="ant-design:cloud-download-outlined"
          v-auth="'wms:production:manual'"
          @click="openOperationReport"
        >
          同步工序汇报
        </a-button>
        <span class="wms-production__hint">任务状态每 5 秒自动刷新</span>
      </template>
      <template #action="{ record }">
        <TableAction :actions="getTableAction(record)" />
      </template>
    </BasicTable>

    <!-- 工序汇报 → 半成品入库草稿（阶段 C） -->
    <a-modal
      v-model:open="oprOpen"
      title="从金蝶工序汇报同步半成品入库草稿"
      :confirm-loading="oprLoading"
      width="560px"
      @ok="handleOperationReport"
    >
      <a-alert
        type="info"
        show-icon
        message="只生成草稿：不分配库位、不下发 WCS、不回写金蝶"
        style="margin-bottom: 12px"
      />
      <a-form layout="vertical">
        <a-form-item label="单据日期区间（留空=全部已审核）">
          <a-range-picker v-model:value="oprRange" value-format="YYYY-MM-DD" style="width: 100%" />
        </a-form-item>
        <a-form-item label="最多处理行数">
          <a-input-number v-model:value="oprMaxRows" :min="1" :max="5000" style="width: 160px" />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-drawer v-model:open="detailOpen" title="生产入库详情" width="720" destroy-on-close>
      <template v-if="detail">
        <a-descriptions :column="2" size="small" bordered>
          <a-descriptions-item label="入库单号">{{ detail.inboundNo }}</a-descriptions-item>
          <a-descriptions-item label="工单号">{{ detail.workOrderNo }}</a-descriptions-item>
          <a-descriptions-item label="产品类型">{{ detail.productType }}</a-descriptions-item>
          <a-descriptions-item label="物料">{{ detail.materialCode }} {{ detail.materialName }}</a-descriptions-item>
          <a-descriptions-item label="批次号">{{ detail.batchNo }}</a-descriptions-item>
          <a-descriptions-item label="本次总量">{{ detail.quantity }}</a-descriptions-item>
          <a-descriptions-item label="良品 / 不良">{{ detail.goodQty }} / {{ detail.badQty }}</a-descriptions-item>
          <a-descriptions-item label="状态">
            <a-tag>{{ detail.status }}</a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="载具码">{{ detail.carrierCode }}</a-descriptions-item>
          <a-descriptions-item label="标识类型">{{ detail.identType }}</a-descriptions-item>
          <a-descriptions-item label="任务号">{{ detail.wcsTaskNo || '-' }}</a-descriptions-item>
          <a-descriptions-item label="任务状态">{{ detail.taskStatus }}</a-descriptions-item>
          <a-descriptions-item label="库位">{{ detail.locationCode || detail.locationId || '-' }}</a-descriptions-item>
          <a-descriptions-item label="RMS 库位">{{ detail.stackCode || '-' }}</a-descriptions-item>
          <a-descriptions-item label="同步状态">{{ detail.syncStatus }}</a-descriptions-item>
          <a-descriptions-item label="金蝶单号">{{ detail.erpBillNo || '-' }}</a-descriptions-item>
          <a-descriptions-item label="重试次数">{{ detail.retryCount ?? 0 }}</a-descriptions-item>
          <a-descriptions-item label="不良原因" :span="2">{{ detail.defectRemark || '-' }}</a-descriptions-item>
          <a-descriptions-item v-if="detail.taskError" label="任务失败原因" :span="2">
            {{ detail.taskError }}
          </a-descriptions-item>
          <a-descriptions-item v-if="detail.lastSyncError" label="同步失败原因" :span="2">
            {{ detail.lastSyncError }}
          </a-descriptions-item>
          <a-descriptions-item v-if="detail.closeReason" label="关单原因" :span="2">
            {{ detail.closeReason }}
          </a-descriptions-item>
          <a-descriptions-item label="提交时间">{{ detail.submitTime || '-' }}</a-descriptions-item>
          <a-descriptions-item label="完成时间">{{ detail.completeTime || '-' }}</a-descriptions-item>
        </a-descriptions>
      </template>
    </a-drawer>
  </div>
</template>

<script lang="ts" name="wms-production" setup>
  import { computed, h, onMounted, onUnmounted, reactive, ref } from 'vue';
  import { Input, Modal } from 'ant-design-vue';
  import { BasicTable, TableAction } from '/@/components/Table';
  import { useListPage } from '/@/hooks/system/useListPage';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { useWmsSyncMode } from '../common/useWmsSyncMode';
  import { columns, searchFormSchema } from './production.data';
  import {
    closeProductionInbound,
    dispatchProductionInbound,
    getProductionInbound,
    listProductionInbound,
    manualCompleteProductionInbound,
    promoteProductionInbound,
    resyncProductionInbound,
    scanWorkOrder,
    submitProductionInbound,
    syncOperationReport,
  } from './production.api';

  const { createMessage } = useMessage();
  /** 自动同步关闭时，待同步单据需要手工「同步金蝶」 */
  const { manualOnly } = useWmsSyncMode();

  const workOrderInputRef = ref();
  const carrierInputRef = ref();
  const workOrderNo = ref('');
  const workOrder = ref<any>(null);
  const scanning = ref(false);
  const submitting = ref(false);
  const form = reactive<{
    goodQty: number;
    badQty: number;
    defectRemark: string;
    batchNo: string;
    carrierCode: string;
  }>({ goodQty: 0, badQty: 0, defectRemark: '', batchNo: '', carrierCode: '' });

  const isFinished = computed(() => workOrder.value?.productType === '成品');
  const carrierLabel = computed(() => (isFinished.value ? '料箱二维码' : '周转盘 RFID'));
  const carrierPlaceholder = computed(() =>
    isFinished.value ? '扫描料箱上的二维码（一箱一码）' : '扫描周转盘上的 RFID 标签（一盘一码）'
  );

  function focusWorkOrder() {
    workOrderInputRef.value?.focus?.();
  }

  // ---------------------------------------------- 工序汇报 → 半成品入库草稿
  const oprOpen = ref(false);
  const oprLoading = ref(false);
  const oprRange = ref<string[]>([]);
  const oprMaxRows = ref(1000);

  function openOperationReport() {
    oprRange.value = [];
    oprMaxRows.value = 1000;
    oprOpen.value = true;
  }

  async function handleOperationReport() {
    oprLoading.value = true;
    try {
      const res: any = await syncOperationReport({
        startDate: oprRange.value?.[0],
        endDate: oprRange.value?.[1],
        maxRows: oprMaxRows.value,
      });
      oprOpen.value = false;
      const lines = [
        `拉取 ${res?.fetched ?? 0} 行`,
        `新建草稿 ${res?.created ?? 0}`,
        `更新 ${res?.updated ?? 0}`,
        `跳过 ${res?.skipped ?? 0}`,
      ];
      const reasons = (res?.messages || []).slice(0, 5);
      Modal.info({
        title: '工序汇报同步完成',
        content: h('div', [
          h('div', lines.join('　')),
          reasons.length
            ? h('div', { style: 'margin-top:8px;color:#8c8c8c;font-size:12px' },
                reasons.map((m: string) => h('div', m)))
            : null,
        ]),
      });
      reload();
    } finally {
      oprLoading.value = false;
    }
  }

  async function handleScan() {
    if (!workOrderNo.value || !workOrderNo.value.trim()) {
      createMessage.warning('请先录入工单号');
      return;
    }
    scanning.value = true;
    try {
      const res: any = await scanWorkOrder(workOrderNo.value.trim());
      workOrder.value = res;
      form.batchNo = res.batchNo || '';
      form.goodQty = 0;
      form.badQty = 0;
      form.defectRemark = '';
      form.carrierCode = '';
      carrierInputRef.value?.focus?.();
    } finally {
      scanning.value = false;
    }
  }

  async function handleSubmit() {
    if (submitting.value) {
      return;
    }
    if (!workOrder.value) {
      createMessage.warning('请先录入工单号调出工单');
      return;
    }
    // 输入框被手工改过但未重新查询：禁止把货记到上一次的工单上
    if (!workOrderNo.value || workOrderNo.value.trim() !== workOrder.value.workOrderNo) {
      createMessage.warning('工单号已变更，请先回车查询工单');
      return;
    }
    if (!form.carrierCode || !form.carrierCode.trim()) {
      createMessage.warning(`请扫描${carrierLabel.value}`);
      return;
    }
    const good = Number(form.goodQty || 0);
    const bad = Number(form.badQty || 0);
    if (good < 0 || bad < 0 || good + bad <= 0) {
      createMessage.warning('良品与不良品数量之和必须大于 0');
      return;
    }
    submitting.value = true;
    try {
      const res: any = await submitProductionInbound({
        workOrderNo: workOrder.value.workOrderNo,
        goodQty: good,
        badQty: bad,
        carrierCode: form.carrierCode.trim(),
        batchNo: form.batchNo || undefined,
        defectRemark: form.defectRemark || undefined,
      });
      createMessage.success(
        `提交成功：${res.inboundNo}（${res.status}${res.taskStatus ? ' / ' + res.taskStatus : ''}）`
      );
      form.goodQty = 0;
      form.badQty = 0;
      form.defectRemark = '';
      form.carrierCode = '';
      reload();
      await handleScan();
      carrierInputRef.value?.focus?.();
    } finally {
      submitting.value = false;
    }
  }

  // ------------------------------------------------------------ 列表与轮询

  const { tableContext } = useListPage({
    tableProps: {
      api: listProductionInbound,
      columns,
      rowKey: 'id',
      formConfig: { schemas: searchFormSchema },
      actionColumn: { width: 260 },
    },
  });
  const [registerTable, { reload }] = tableContext;

  let pollTimer: any = null;
  onMounted(() => {
    focusWorkOrder();
    pollTimer = setInterval(() => {
      // 后台标签页不轮询，避免长时间挂机持续打接口
      if (document.visibilityState === 'visible') {
        reload();
      }
    }, 5000);
  });
  onUnmounted(() => {
    if (pollTimer) {
      clearInterval(pollTimer);
    }
  });

  // ------------------------------------------------------------ 详情与操作

  const detailOpen = ref(false);
  const detail = ref<any>(null);

  async function openDetail(record: any) {
    detail.value = await getProductionInbound(record.id);
    detailOpen.value = true;
  }

  function getTableAction(record: any) {
    const actions: any[] = [{ label: '详情', onClick: () => openDetail(record) }];
    if (record.status === '草稿') {
      // 工序汇报草稿（方案 B）：推进后才占立库位、下发设备任务并计入本仓库存
      actions.push({
        label: '推进',
        auth: 'wms:production:submit',
        onClick: () => handlePromote(record),
      });
    }
    if (['待上架', '异常待人工'].includes(record.status) && ['失败', '未下发'].includes(record.taskStatus)) {
      actions.push({
        label: '重新下发',
        auth: 'wms:production:dispatch',
        onClick: () => handleDispatch(record),
      });
    }
    if (record.status === '异常待人工') {
      actions.push({
        label: '转人工完成',
        auth: 'wms:production:manual',
        onClick: () => handleManualComplete(record),
      });
    }
    if (['待上架', '异常待人工'].includes(record.status)) {
      actions.push({
        label: '关单',
        auth: 'wms:production:close',
        color: 'error',
        onClick: () => handleClose(record),
      });
    }
    if (record.syncStatus === '同步失败' || (manualOnly.value && record.syncStatus === '待同步')) {
      // 自动同步关闭时后台不再续跑，待同步的单据要靠这里手工推；同步失败则两种模式都可重试
      actions.push({
        label: manualOnly.value ? '同步金蝶' : '重试同步',
        auth: 'wms:production:sync',
        onClick: () => handleResync(record),
      });
    }
    // 工序汇报来源（方案 B）永远「无需同步」，定时对账也挂在自动同步闸门后面：
    // 关掉自动同步后必须留一个手工对账入口，否则这些单再也回填不了 ERP 单号
    if (record.erpEntryKey && record.syncStatus === '无需同步' && record.status === '已入库') {
      actions.push({
        label: '对账 ERP',
        auth: 'wms:production:sync',
        onClick: () => handleResync(record),
      });
    }
    return actions;
  }

  async function handlePromote(record: any) {
    let carrierCode = '';
    Modal.confirm({
      title: '推进工序汇报草稿',
      content: () =>
        h('div', { style: 'padding-top:8px' }, [
          h('div', { style: 'margin-bottom:8px;color:#8c8c8c' }, '推进后占用立库库位并下发设备任务；ERP 侧由车间汇报入库，不重复开单。'),
          h(Input, {
            placeholder: '载具码（必填，≤64 字）',
            maxlength: 64,
            'onUpdate:value': (v: string) => (carrierCode = v),
          }),
        ]),
      onOk: async () => {
        if (!carrierCode || !carrierCode.trim()) {
          createMessage.warning('载具码不能为空');
          throw new Error('载具码不能为空');
        }
        await promoteProductionInbound(record.id, carrierCode.trim());
        createMessage.success('已推进，等待上架');
        reload();
      },
    });
  }

  async function handleDispatch(record: any) {
    await dispatchProductionInbound(record.id);
    createMessage.success('已重新下发');
    reload();
  }

  function handleManualComplete(record: any) {
    let carrier = '';
    Modal.confirm({
      title: `转人工完成 ${record.inboundNo}？`,
      content: h('div', [
        h('p', '确认设备已人工完成上架。系统将写入良品库存并触发同步。'),
        h('input', {
          class: 'ant-input',
          placeholder: '新载具码（可选；扫错码时填写）',
          style: 'width:100%',
          onInput: (e: any) => (carrier = e.target.value),
        }),
      ]),
      okText: '确认完成',
      cancelText: '取消',
      async onOk() {
        await manualCompleteProductionInbound(record.id, {
          carrierCode: carrier && carrier.trim() ? carrier.trim() : undefined,
          remark: '人工确认完成',
        });
        createMessage.success('已转人工完成');
        reload();
      },
    });
  }

  function handleClose(record: any) {
    let reason = '';
    Modal.confirm({
      title: `关单 ${record.inboundNo}？`,
      content: h('div', [
        h('p', '关单不写入良品库存；半成品会释放已占用的立库库位，且不可恢复。'),
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
        await closeProductionInbound(record.id, reason.trim());
        createMessage.success('已关单');
        reload();
      },
    });
  }

  async function handleResync(record: any) {
    try {
      await resyncProductionInbound(record.id);
      createMessage.success('同步成功');
    } finally {
      reload();
    }
  }
</script>

<style lang="less" scoped>
  .wms-production {
    padding: 16px;
  }

  .wms-production__alert {
    margin-bottom: 12px;
  }

  .wms-production__workbench {
    margin-bottom: 16px;
  }

  /* 工单号录入行：用 flex 保证标签、输入框、按钮在同一基线上垂直居中 */
  .wms-production__scan {
    display: flex;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;
  }

  .wms-production__scan-label {
    flex: none;
    margin: 0;
    font-weight: 500;
    white-space: nowrap;
    color: rgba(0, 0, 0, 0.85);
  }

  .wms-production__scan-input {
    flex: 0 1 360px;
    min-width: 240px;
  }

  .wms-production__info {
    margin: 12px 0;
  }

  .wms-production__form {
    margin-top: 8px;
  }

  .wms-production__remaining--empty {
    color: #ff4d4f;
    font-weight: 600;
  }

  .wms-production__hint {
    margin-left: 12px;
    color: #999;
  }
</style>
