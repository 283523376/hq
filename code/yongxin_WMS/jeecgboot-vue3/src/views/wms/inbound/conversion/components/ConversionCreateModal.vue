<template>
  <a-modal
    v-model:open="open"
    title="新建改制单（大改小）"
    width="760px"
    :confirm-loading="submitting"
    ok-text="建单"
    cancel-text="取消"
    destroy-on-close
    @ok="handleSubmit"
  >
    <a-alert
      type="info"
      show-icon
      class="conversion-create__alert"
      message="生产资料不入 WMS 库存：投入数量只做登记，产出在「完成」时生成生产入库单入账"
    />

    <a-form layout="vertical">
      <a-form-item label="生产订单号" required>
        <a-select
          v-model:value="form.productionOrderNo"
          show-search
          allow-clear
          :filter-option="false"
          :options="workOrderOptions"
          :loading="workOrderSearching"
          placeholder="搜索 ERP 生产订单号（须已同步到 WMS 且已下达）"
          @search="handleWorkOrderSearch"
          @change="handleWorkOrderChange"
        />
        <div v-if="selectedWorkOrder" class="conversion-create__hint">
          成品：{{ selectedWorkOrder.materialCode }} {{ selectedWorkOrder.materialName }}　计划数量：{{ selectedWorkOrder.planQty }}　状态：{{
            selectedWorkOrder.workOrderStatus
          }}
        </div>
      </a-form-item>

      <a-row :gutter="16">
        <a-col :span="12">
          <a-form-item label="投入物料" required>
            <a-select
              v-model:value="form.inputMaterialCode"
              show-search
              allow-clear
              :filter-option="false"
              :options="materialOptions"
              :loading="materialSearching"
              placeholder="搜索物料编码/名称"
              @search="handleMaterialSearch"
            />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="投入批次号">
            <a-input v-model:value="form.inputBatchNo" allow-clear placeholder="可空" />
          </a-form-item>
        </a-col>
      </a-row>
      <a-row :gutter="16">
        <a-col :span="12">
          <a-form-item label="投入数量" required>
            <a-input-number v-model:value="form.inputQty" :min="0.001" :precision="3" style="width: 100%" />
          </a-form-item>
        </a-col>
      </a-row>

      <a-divider style="margin: 4px 0 16px">产出</a-divider>

      <a-row :gutter="16">
        <a-col :span="12">
          <a-form-item label="产出物料" required>
            <a-select
              v-model:value="form.outputMaterialCode"
              show-search
              allow-clear
              :filter-option="false"
              :options="materialOptions"
              :loading="materialSearching"
              placeholder="选择产出物料（须与生产订单成品一致）"
              @search="handleMaterialSearch"
            />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="产出批次号">
            <a-input v-model:value="form.outputBatchNo" allow-clear placeholder="为空时入库取生产订单批次" />
          </a-form-item>
        </a-col>
      </a-row>
      <a-row :gutter="16">
        <a-col :span="12">
          <a-form-item label="产出数量" required>
            <a-input-number v-model:value="form.outputQty" :min="0.001" :precision="3" style="width: 100%" />
            <div v-if="selectedWorkOrder" class="conversion-create__hint">不得超过生产订单计划数量 {{ selectedWorkOrder.planQty }}</div>
          </a-form-item>
        </a-col>
      </a-row>
    </a-form>
  </a-modal>
</template>

<script lang="ts" setup>
  import { computed, reactive, ref, watch } from 'vue';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { createConversion, searchMaterialOptions, searchWorkOrders } from '../conversion.api';

  const emit = defineEmits(['success']);
  const open = defineModel<boolean>('open', { default: false });
  const { createMessage } = useMessage();

  const submitting = ref(false);

  const workOrderOptions = ref<{ label: string; value: string }[]>([]);
  const workOrderSearching = ref(false);
  const workOrderMap: Record<string, any> = {};

  const materialOptions = ref<{ label: string; value: string }[]>([]);
  const materialSearching = ref(false);
  const materialLabelMap: Record<string, string> = {};

  const form = reactive({
    productionOrderNo: undefined as string | undefined,
    inputMaterialCode: undefined as string | undefined,
    inputBatchNo: '',
    inputQty: 1,
    outputMaterialCode: undefined as string | undefined,
    outputBatchNo: '',
    outputQty: 1,
  });

  const selectedWorkOrder = computed(() => (form.productionOrderNo ? workOrderMap[form.productionOrderNo] : null));

  watch(open, (visible) => {
    if (visible) {
      resetAll();
      void loadWorkOrders();
      void loadMaterials();
    }
  });

  function resetAll() {
    form.productionOrderNo = undefined;
    form.inputMaterialCode = undefined;
    form.inputBatchNo = '';
    form.inputQty = 1;
    form.outputMaterialCode = undefined;
    form.outputBatchNo = '';
    form.outputQty = 1;
  }

  async function loadWorkOrders(keyword?: string) {
    workOrderSearching.value = true;
    try {
      const res: any = await searchWorkOrders(keyword);
      for (const w of res?.records || []) {
        workOrderMap[w.workOrderNo] = w;
      }
      workOrderOptions.value = (res?.records || []).map((w: any) => ({
        label: `${w.workOrderNo} ${w.materialCode || ''} ${w.materialName || ''}（${w.workOrderStatus || ''}）`,
        value: w.workOrderNo,
      }));
    } catch (e) {
      // 加载失败不阻塞，提交时后端会给明确文案
    } finally {
      workOrderSearching.value = false;
    }
  }

  let workOrderTimer: any = null;
  function handleWorkOrderSearch(keyword: string) {
    if (workOrderTimer) {
      clearTimeout(workOrderTimer);
    }
    workOrderTimer = setTimeout(() => loadWorkOrders(keyword), 300);
  }

  /** 选工单后自动带出产出物料与产出数量上限（后端要求产出物料=生产订单成品） */
  function handleWorkOrderChange(workOrderNo: string) {
    const record = workOrderMap[workOrderNo];
    if (!record) {
      return;
    }
    form.outputMaterialCode = record.materialCode;
    if (!form.outputQty || Number(form.outputQty) <= 0) {
      form.outputQty = Number(record.planQty) || 1;
    }
  }

  async function loadMaterials(keyword?: string) {
    materialSearching.value = true;
    try {
      const res: any = await searchMaterialOptions(keyword);
      const options = (res?.records || []).map((m: any) => ({
        label: `${m.materialCode} ${m.materialName}`,
        value: m.materialCode,
      }));
      const selected = [form.inputMaterialCode, form.outputMaterialCode]
        .filter(Boolean)
        .map((code) => ({ value: code as string, label: materialLabelMap[code as string] || (code as string) }));
      const merged: Record<string, any> = {};
      [...selected, ...options].forEach((o) => (merged[o.value] = o));
      materialOptions.value = Object.values(merged);
      for (const o of options) {
        materialLabelMap[o.value] = o.label;
      }
    } catch (e) {
      // 物料加载失败不阻塞页面
    } finally {
      materialSearching.value = false;
    }
  }

  let materialTimer: any = null;
  function handleMaterialSearch(keyword: string) {
    if (materialTimer) {
      clearTimeout(materialTimer);
    }
    materialTimer = setTimeout(() => loadMaterials(keyword), 300);
  }

  function validate(): boolean {
    if (!form.productionOrderNo) {
      createMessage.warning('请选择生产订单');
      return false;
    }
    if (!form.inputMaterialCode) {
      createMessage.warning('请选择投入物料');
      return false;
    }
    if (!form.inputQty || Number(form.inputQty) <= 0) {
      createMessage.warning('投入数量必须大于 0');
      return false;
    }
    if (!form.outputMaterialCode) {
      createMessage.warning('请选择产出物料');
      return false;
    }
    if (!form.outputQty || Number(form.outputQty) <= 0) {
      createMessage.warning('产出数量必须大于 0');
      return false;
    }
    const workOrder = selectedWorkOrder.value;
    if (workOrder) {
      if (form.outputMaterialCode !== workOrder.materialCode) {
        createMessage.warning(`产出物料必须与生产订单成品一致（${workOrder.materialCode}）`);
        return false;
      }
      if (Number(form.outputQty) > Number(workOrder.planQty || 0)) {
        createMessage.warning('产出数量超出生产订单计划数量');
        return false;
      }
    }
    return true;
  }

  async function handleSubmit() {
    if (submitting.value) {
      return;
    }
    if (!validate()) {
      return;
    }
    submitting.value = true;
    try {
      const res: any = await createConversion({
        productionOrderNo: form.productionOrderNo,
        inputMaterialCode: form.inputMaterialCode,
        inputBatchNo: form.inputBatchNo?.trim() || undefined,
        inputQty: Number(form.inputQty),
        outputMaterialCode: form.outputMaterialCode,
        outputBatchNo: form.outputBatchNo?.trim() || undefined,
        outputQty: Number(form.outputQty),
      });
      createMessage.success(`建单成功：${res?.conversionNo || ''}`);
      open.value = false;
      emit('success');
    } finally {
      submitting.value = false;
    }
  }
</script>

<style lang="less" scoped>
  .conversion-create__alert {
    margin-bottom: 12px;
  }

  .conversion-create__hint {
    margin-top: 4px;
    color: rgba(0, 0, 0, 0.45);
    font-size: 12px;
    line-height: 1.5;
  }
</style>
