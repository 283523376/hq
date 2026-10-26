<template>
  <a-modal
    v-model:open="open"
    :title="title"
    width="1000px"
    :confirm-loading="submitting"
    :ok-text="'提交'"
    cancel-text="取消"
    destroy-on-close
    @ok="handleSubmit"
  >
    <!-- ---------------------------------------------------------- CK-001 领料 -->
    <template v-if="mode === 'pick'">
      <a-form layout="inline" class="wms-outbound-create__scan">
        <a-form-item label="生产工单号">
          <a-input
            v-model:value="pickForm.workOrderNo"
            placeholder="扫码枪扫描或手工录入，回车查询"
            allow-clear
            style="width: 300px"
            @press-enter="handleScan"
          />
        </a-form-item>
        <a-form-item>
          <a-button type="primary" :loading="scanning" @click="handleScan">查询需求</a-button>
        </a-form-item>
      </a-form>

      <a-descriptions v-if="scan" :column="3" size="small" bordered class="wms-outbound-create__info">
        <a-descriptions-item label="工单号">{{ scan.workOrderNo }}</a-descriptions-item>
        <a-descriptions-item label="产品">{{ scan.materialCode }} {{ scan.materialName }}</a-descriptions-item>
        <a-descriptions-item label="计划数量">{{ scan.planQty }}</a-descriptions-item>
        <a-descriptions-item label="产品类型">{{ scan.productType }}</a-descriptions-item>
        <a-descriptions-item label="批次">{{ scan.batchNo || '-' }}</a-descriptions-item>
        <a-descriptions-item label="需求来源">{{ requirementSource }}</a-descriptions-item>
      </a-descriptions>
      <a-alert v-if="scan && scan.warning" type="warning" :message="scan.warning" show-icon class="wms-outbound-create__alert" />
    </template>

    <!-- ---------------------------------------------------------- CK-002 销售（ERP → WMS） -->
    <template v-else>
      <a-form layout="inline">
        <a-form-item label="销售订单号" required>
          <a-input v-model:value="salesForm.salesOrderNo" placeholder="金蝶销售订单号，如 XSDD1000002318" style="width: 260px" />
        </a-form-item>
        <a-form-item>
          <a-button :loading="salesLoading" @click="loadSalesOrder">从 ERP 拉取</a-button>
        </a-form-item>
        <a-form-item label="要货日期">
          <a-date-picker v-model:value="salesForm.deliveryDate" value-format="YYYY-MM-DD" style="width: 160px" />
        </a-form-item>
      </a-form>
      <a-alert
        v-if="salesPreview && salesPreview.order?.erpDocumentStatus !== 'C'"
        type="error"
        show-icon
        style="margin-bottom: 12px"
        :message="`该订单金蝶状态为 ${salesPreview.order?.erpDocumentStatus || '未知'}，只有已审核(C)的订单可以出库`"
      />
      <a-descriptions v-if="salesPreview" :column="3" size="small" bordered class="wms-outbound-create__info">
        <a-descriptions-item label="客户">{{ salesPreview.order?.customerName }}（{{ salesPreview.order?.customerCode }}）</a-descriptions-item>
        <a-descriptions-item label="销售组织">{{ salesPreview.order?.saleOrgNumber || '-' }}</a-descriptions-item>
        <a-descriptions-item label="金蝶状态">{{ salesPreview.order?.erpDocumentStatus }}</a-descriptions-item>
        <a-descriptions-item label="订单日期">{{ salesPreview.order?.orderDate || '-' }}</a-descriptions-item>
      </a-descriptions>
    </template>

    <!-- ---------------------------------------------------------- 明细 -->
    <div class="wms-outbound-create__details-title">
      <span>出库明细{{ mode === 'sales' ? '（来自 ERP 销售订单）' : '' }}</span>
      <a-button v-if="mode !== 'sales'" type="link" size="small" @click="addRow">+ 手工添加明细</a-button>
    </div>
    <a-table :data-source="rows" :columns="rowColumns" :pagination="false" size="small" row-key="_key" bordered>
      <template #bodyCell="{ column, record, index }">
        <template v-if="column.key === 'material'">
          <a-select
            v-model:value="record.materialCode"
            show-search
            allow-clear
            :filter-option="false"
            :options="materialOptions"
            :loading="materialSearching"
            placeholder="搜索物料编码/名称"
            style="width: 100%"
            @search="handleMaterialSearch"
            @change="(code) => handleMaterialChange(index, code)"
          />
        </template>
        <template v-else-if="column.key === 'batchNo'">
          <a-input v-model:value="record.batchNo" placeholder="可空" />
        </template>
        <template v-else-if="column.key === 'requiredQty'">
          <a-input-number v-model:value="record.requiredQty" :min="0" :precision="3" style="width: 100%" />
        </template>
        <template v-else-if="column.key === 'unit'">
          <span>{{ record.unit || '-' }}</span>
        </template>
        <template v-else-if="column.key === 'source'">
          <a-tag :color="record.manual ? 'orange' : 'blue'">{{ record.manual ? '手工' : '用料清单' }}</a-tag>
        </template>
        <template v-else-if="column.key === 'matched'">
          <a-tag v-if="record.manual" color="default">-</a-tag>
          <a-tag v-else :color="record.matched ? 'success' : 'error'">{{ record.matched ? '已匹配' : '未匹配' }}</a-tag>
        </template>
        <template v-else-if="column.key === 'action'">
          <a-button type="link" danger size="small" @click="removeRow(index)">删除</a-button>
        </template>
      </template>
    </a-table>
  </a-modal>
</template>

<script lang="ts" setup>
  import { computed, reactive, ref, watch } from 'vue';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { createPick, createSales, createSample, listMaterialOptions, previewSalesOrder, scanPick } from '../outbound.api';

  const props = defineProps<{ mode: 'pick' | 'sales' | 'sample' }>();
  const emit = defineEmits(['update:open', 'success']);

  const { createMessage } = useMessage();

  const open = defineModel<boolean>('open', { default: false });

  const submitting = ref(false);
  const scanning = ref(false);
  const scan = ref<any>(null);
  const materialOptions = ref<any[]>([]);
  const materialSearching = ref(false);
  const materialLabelMap: Record<string, string> = {};

  const pickForm = reactive({ workOrderNo: '' });
  const salesForm = reactive<{
    salesOrderNo: string;
    deliveryDate?: string;
  }>({ salesOrderNo: '', deliveryDate: undefined });

  /** ERP 销售订单预览（CK-002 方向反转）：明细只读来自金蝶 */
  const salesPreview = ref<any>(null);
  const salesLoading = ref(false);

  type Row = {
    _key: number;
    materialCode?: string;
    materialName?: string;
    batchNo?: string;
    requiredQty: number;
    unit?: string;
    sourceEntryId?: string;
    matched?: boolean;
    manual: boolean;
  };
  const rows = ref<Row[]>([]);
  let rowSeq = 0;

  const rowColumns = [
    { title: '物料', key: 'material', width: 300 },
    { title: '批次号', key: 'batchNo', width: 140 },
    { title: '需求数量', key: 'requiredQty', width: 140 },
    { title: '单位', key: 'unit', width: 80 },
    { title: '来源', key: 'source', width: 100 },
    { title: '匹配', key: 'matched', width: 90 },
    { title: '操作', key: 'action', width: 80 },
  ];

  const title = computed(() => (props.mode === 'pick' ? '新建领料出库单' : '新建销售出库单'));

  const manualAdded = computed(() => rows.value.some((r) => r.manual));
  const requirementSource = computed(() => (manualAdded.value ? '手工' : scan.value?.requirementSource || '金蝶用料清单'));

  function resetAll() {
    scan.value = null;
    rows.value = [];
    pickForm.workOrderNo = '';
    salesForm.salesOrderNo = '';
    salesForm.deliveryDate = undefined;
    salesPreview.value = null;
  }

  // 每次打开时重置（父组件通过 v-model:open 控制）
  watch(open, (v) => {
    if (v) {
      resetAll();
      void loadMaterialOptions();
    }
  });

  async function handleScan() {
    if (!pickForm.workOrderNo || !pickForm.workOrderNo.trim()) {
      createMessage.warning('请先扫码或录入工单号');
      return;
    }
    scanning.value = true;
    try {
      const res: any = await scanPick(pickForm.workOrderNo.trim());
      scan.value = res;
      rows.value = (res.details || []).map((d: any) => ({
        _key: ++rowSeq,
        materialCode: d.materialCode,
        materialName: d.materialName,
        batchNo: d.batchNo,
        requiredQty: Number(d.requiredQty || 0),
        unit: d.unit,
        sourceEntryId: d.sourceEntryId,
        matched: d.matched,
        manual: false,
      }));
      if (!rows.value.length) {
        createMessage.warning(res.warning || '该工单无用料清单明细，请手工添加');
      }
      await loadMaterialOptions();
    } finally {
      scanning.value = false;
    }
  }

  function addRow() {
    rows.value.push({ _key: ++rowSeq, requiredQty: 0, manual: true });
  }

  function removeRow(index: number) {
    rows.value.splice(index, 1);
  }

  async function loadMaterialOptions(keyword?: string) {
    materialSearching.value = true;
    try {
      const res: any = await listMaterialOptions(keyword);
      const options = (res?.records || []).map((m: any) => ({
        label: `${m.materialCode} ${m.materialName}`,
        value: m.materialCode,
      }));
      // 合并已选项，避免搜索后已选物料在下拉里显示为编码
      const selected = rows.value
        .map((r) => r.materialCode)
        .filter(Boolean)
        .map((code) => ({ value: code, label: materialLabelMap[code!] || code! }));
      const merged: Record<string, any> = {};
      [...selected, ...options].forEach((o) => (merged[o.value] = o));
      materialOptions.value = Object.values(merged);
      for (const o of options) {
        materialLabelMap[o.value] = o.label;
      }
    } catch (e) {
      // 物料加载失败不阻塞页面，提交时由后端返回文案
    } finally {
      materialSearching.value = false;
    }
  }

  let materialSearchTimer: any = null;
  function handleMaterialSearch(keyword: string) {
    if (materialSearchTimer) {
      clearTimeout(materialSearchTimer);
    }
    materialSearchTimer = setTimeout(() => loadMaterialOptions(keyword), 300);
  }

  function handleMaterialChange(index: number, code: string) {
    const label = materialLabelMap[code] || '';
    const name = label.startsWith(code) ? label.slice(code.length).trim() : label;
    rows.value[index].materialName = name;
  }

  function validateRows(): boolean {
    if (!rows.value.length) {
      createMessage.warning('至少需要一条出库明细');
      return false;
    }
    for (const r of rows.value) {
      if (!r.materialCode) {
        createMessage.warning('请为每一行选择物料');
        return false;
      }
      if (!r.requiredQty || Number(r.requiredQty) <= 0) {
        createMessage.warning(`物料 ${r.materialCode} 的需求数量必须大于 0`);
        return false;
      }
    }
    return true;
  }

  function buildDetails() {
    return rows.value.map((r) => ({
      materialCode: r.materialCode,
      batchNo: r.batchNo || undefined,
      requiredQty: Number(r.requiredQty),
      unit: r.unit || undefined,
      sourceEntryId: r.sourceEntryId || undefined,
    }));
  }

  async function handleSubmit() {
    if (submitting.value) {
      return;
    }
    // 样品出库：单物料单批次，不绑工单
    if (props.mode === 'sample') {
      const rows = rows.value.filter((r: any) => r.materialCode && r.requiredQty > 0);
      if (rows.length !== 1) {
        createMessage.warning('样品出库需且仅需一行明细');
        return;
      }
      submitting.value = true;
      try {
        const res: any = await createSample({
          materialCode: rows[0].materialCode,
          qty: rows[0].requiredQty,
          batchNo: rows[0].batchNo,
          remark: '样品出库',
        });
        createMessage.success(`创建成功：${res.outboundNo}`);
        open.value = false;
        emit('success');
      } finally {
        submitting.value = false;
      }
      return;
    }
    if (props.mode === 'pick') {
      if (!pickForm.workOrderNo || !pickForm.workOrderNo.trim()) {
        createMessage.warning('请先扫码或录入工单号');
        return;
      }
      if (!validateRows()) {
        return;
      }
      submitting.value = true;
      try {
        const res: any = await createPick({
          workOrderNo: pickForm.workOrderNo.trim(),
          requirementSource: requirementSource.value,
          details: buildDetails(),
        });
        createMessage.success(`创建成功：${res.outboundNo}`);
        open.value = false;
        emit('success');
      } finally {
        submitting.value = false;
      }
      return;
    }

    if (!salesForm.salesOrderNo || !salesForm.salesOrderNo.trim()) {
      createMessage.warning('请填写销售订单号');
      return;
    }
    if (!salesPreview.value) {
      createMessage.warning('请先从 ERP 拉取销售订单');
      return;
    }
    if (salesPreview.value.order?.erpDocumentStatus !== 'C') {
      createMessage.error('该销售订单未审核（非 C），不能出库');
      return;
    }
    if (!validateRows()) {
      return;
    }
    submitting.value = true;
    try {
      const res: any = await createSales({
        salesOrderNo: salesForm.salesOrderNo.trim(),
        deliveryDate: salesForm.deliveryDate || undefined,
        details: buildDetails(),
      });
      createMessage.success(`创建成功：${res.outboundNo}`);
      open.value = false;
      emit('success');
    } finally {
      submitting.value = false;
    }
  }

  /** 从 ERP 拉取销售订单并带出分录（数量默认取剩余可出量）。 */
  async function loadSalesOrder() {
    if (!salesForm.salesOrderNo || !salesForm.salesOrderNo.trim()) {
      createMessage.warning('请填写销售订单号');
      return;
    }
    salesLoading.value = true;
    try {
      const res: any = await previewSalesOrder(salesForm.salesOrderNo.trim());
      salesPreview.value = res;
      salesForm.deliveryDate = res?.order?.deliveryDate || undefined;
      rows.value = (res?.lines || []).map((d: any) => ({
        _key: ++rowSeq,
        materialCode: d.materialCode,
        materialName: d.materialName,
        batchNo: '',
        requiredQty: Number(d.remainingQty || 0),
        unit: d.unit,
        matched: true,
        manual: false,
      }));
      if (!rows.value.length) {
        createMessage.warning('该销售订单没有可出库分录');
      }
    } finally {
      salesLoading.value = false;
    }
  }
</script>

<style lang="less" scoped>
  .wms-outbound-create__scan {
    margin-bottom: 12px;
  }

  .wms-outbound-create__info {
    margin-bottom: 12px;
  }

  .wms-outbound-create__alert {
    margin-bottom: 12px;
  }

  .wms-outbound-create__details-title {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin: 16px 0 8px;
    font-weight: 600;
  }
</style>
