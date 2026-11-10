<template>
  <a-modal
    v-model:open="open"
    title="不良品入库登记"
    width="720px"
    :confirm-loading="submitting"
    ok-text="登记"
    cancel-text="取消"
    destroy-on-close
    @ok="handleSubmit"
  >
    <a-form layout="vertical">
      <a-row :gutter="16">
        <a-col :span="12">
          <a-form-item label="来源类型" required>
            <a-radio-group v-model:value="form.sourceType" button-style="solid" @change="handleSourceTypeChange">
              <a-radio-button v-for="item in DEFECT_SOURCE_OPTIONS" :key="item.value" :value="item.value">
                {{ item.label }}
              </a-radio-button>
            </a-radio-group>
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="来源单号" required>
            <a-input
              v-model:value="form.sourceBillNo"
              allow-clear
              :placeholder="form.sourceType === 'CONSIGNED' ? '受托入库单号（用于反查受托方）' : '生产入库单号'"
            />
          </a-form-item>
        </a-col>
      </a-row>

      <a-row :gutter="16">
        <a-col :span="12">
          <a-form-item label="物料" required>
            <a-select
              v-model:value="form.materialId"
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
          <a-form-item label="批次号" :required="form.sourceType === 'CONSIGNED'">
            <a-input v-model:value="form.batchNo" allow-clear :placeholder="batchPlaceholder" />
          </a-form-item>
        </a-col>
      </a-row>

      <a-row :gutter="16">
        <a-col :span="12">
          <a-form-item label="不良数量" required>
            <a-input-number v-model:value="form.qty" :min="0.001" :precision="3" style="width: 100%" />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="处置方式" required>
            <a-select v-model:value="form.disposalType" :options="DEFECT_DISPOSAL_OPTIONS" />
          </a-form-item>
        </a-col>
      </a-row>

      <a-form-item label="不良原因">
        <a-textarea v-model:value="form.reason" :rows="3" placeholder="如：来料不良 / 加工不良 / 尺寸超差" />
      </a-form-item>

      <a-alert
        v-if="form.disposalType === 'RETURN'"
        type="warning"
        show-icon
        message="退货走 ERP 采购管理，需来源单号能反查到受托方"
      >
        <template #description>
          <div>退货只登记台账（待处理），需在「不良品记录」里点「退货」才真正生成金蝶受托加工材料退料单。</div>
        </template>
      </a-alert>
      <a-alert
        v-else-if="form.sourceType === 'CONSIGNED'"
        type="info"
        show-icon
        message="受托不良入废品仓：扣减受托库存，货权仍属受托方（不转自有）"
      />
    </a-form>
  </a-modal>
</template>

<script lang="ts" setup>
  import { computed, reactive, ref, watch } from 'vue';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { recordInbound, searchMaterialOptions } from '../defect.api';
  import { DEFECT_DISPOSAL_OPTIONS, DEFECT_SOURCE_OPTIONS } from '../defect.data';

  const emit = defineEmits(['success']);
  const open = defineModel<boolean>('open', { default: false });
  const { createMessage } = useMessage();

  const submitting = ref(false);
  const materialOptions = ref<{ label: string; value: string }[]>([]);
  const materialSearching = ref(false);
  const materialLabelMap: Record<string, string> = {};

  const form = reactive({
    sourceType: 'PRODUCTION',
    sourceBillNo: '',
    materialId: undefined as string | undefined,
    batchNo: '',
    qty: 1,
    reason: '',
    disposalType: 'SCRAP',
  });

  const batchPlaceholder = computed(() =>
    form.sourceType === 'CONSIGNED' ? '必填（扣减受托库存按 物料+批次+受托方 匹配）' : '可空'
  );

  watch(open, (visible) => {
    if (visible) {
      resetAll();
      void loadMaterials();
    }
  });

  function resetAll() {
    form.sourceType = 'PRODUCTION';
    form.sourceBillNo = '';
    form.materialId = undefined;
    form.batchNo = '';
    form.qty = 1;
    form.reason = '';
    form.disposalType = 'SCRAP';
  }

  function handleSourceTypeChange() {
    // 受托必须带批次，切过去时给出提示，但不擅自填值
    if (form.sourceType === 'CONSIGNED' && !form.batchNo) {
      createMessage.info('受托不良必须填写批次号');
    }
  }

  async function loadMaterials(keyword?: string) {
    materialSearching.value = true;
    try {
      const res: any = await searchMaterialOptions(keyword);
      const options = (res?.records || []).map((m: any) => ({
        label: `${m.materialCode} ${m.materialName}`,
        value: m.id,
      }));
      const selected = form.materialId
        ? [{ value: form.materialId, label: materialLabelMap[form.materialId] || form.materialId }]
        : [];
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
    if (!form.sourceBillNo || !form.sourceBillNo.trim()) {
      createMessage.warning('来源单号不能为空');
      return false;
    }
    if (!form.materialId) {
      createMessage.warning('请选择物料');
      return false;
    }
    if (form.sourceType === 'CONSIGNED' && (!form.batchNo || !form.batchNo.trim())) {
      createMessage.warning('受托不良必须填写批次号');
      return false;
    }
    if (!form.qty || Number(form.qty) <= 0) {
      createMessage.warning('不良数量必须大于 0');
      return false;
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
      await recordInbound({
        sourceType: form.sourceType,
        sourceBillNo: form.sourceBillNo.trim(),
        materialId: form.materialId,
        batchNo: form.batchNo?.trim() || undefined,
        qty: Number(form.qty),
        reason: form.reason?.trim() || undefined,
        disposalType: form.disposalType,
      });
      createMessage.success('登记成功');
      open.value = false;
      emit('success');
    } finally {
      submitting.value = false;
    }
  }
</script>
