<template>
  <a-modal
    v-model:open="open"
    title="原材料到货登记"
    width="1000px"
    :confirm-loading="submitting"
    ok-text="登记"
    cancel-text="取消"
    destroy-on-close
    @ok="handleSubmit"
  >
    <a-form layout="vertical">
      <a-row :gutter="16">
        <a-col :span="8">
          <a-form-item label="来源类型" required>
            <a-radio-group v-model:value="form.sourceType" button-style="solid" @change="handleSourceTypeChange">
              <a-radio-button v-for="item in SOURCE_TYPE_OPTIONS" :key="item.value" :value="item.value">
                {{ item.label }}
              </a-radio-button>
            </a-radio-group>
          </a-form-item>
        </a-col>
        <a-col :span="8">
          <a-form-item label="处理方式" required>
            <a-radio-group v-model:value="form.handlingType" button-style="solid">
              <a-radio-button v-for="item in HANDLING_TYPE_OPTIONS" :key="item.value" :value="item.value">
                {{ item.label }}
              </a-radio-button>
            </a-radio-group>
          </a-form-item>
        </a-col>
        <a-col :span="8">
          <a-form-item label="ERP 订单号" required>
            <a-input
              v-model:value="form.erpOrderNo"
              allow-clear
              :placeholder="form.sourceType === 'CONSIGNED' ? '金蝶受托加工订单号' : '金蝶采购订单号'"
            />
          </a-form-item>
        </a-col>
      </a-row>

      <a-row :gutter="16">
        <a-col :span="8">
          <a-form-item :label="supplierLabel" :required="supplierRequired">
            <a-select
              v-model:value="form.supplierId"
              show-search
              allow-clear
              option-filter-prop="label"
              :options="supplierOptions"
              :loading="supplierLoading"
              :placeholder="`选择${supplierLabel}（复用供应商档案）`"
            />
            <div v-if="showSupplierWarning" class="raw-inbound__required-hint">
              金蝶采购入库单必须有供应商
            </div>
          </a-form-item>
        </a-col>
        <a-col :span="16">
          <a-form-item label="口径说明">
            <div class="raw-inbound__mode-tip">
              <template v-if="form.handlingType === 'CONVERT'">
                大改小原材料不入 WMS 库存，到货仅登记、物料直送车间；产出请走改制单。
              </template>
              <template v-else>
                段差棒直入：登记后为「待分配」，需在列表点「上架」选择库位后才入库存并回写金蝶。
              </template>
            </div>
          </a-form-item>
        </a-col>
      </a-row>
    </a-form>

    <div class="raw-inbound__details-title">
      <span>到货明细（物料须为原材料分类）</span>
      <a-button type="link" size="small" @click="addRow">+ 添加明细</a-button>
    </div>
    <a-table :data-source="rows" :columns="detailColumns" :pagination="false" size="small" row-key="_key" bordered>
      <template #bodyCell="{ column, record, index }">
        <template v-if="column.key === 'materialId'">
          <a-select
            v-model:value="record.materialId"
            show-search
            allow-clear
            :filter-option="false"
            :options="materialOptions"
            :loading="materialSearching"
            placeholder="搜索物料编码/名称"
            style="width: 100%"
            @search="handleMaterialSearch"
            @change="(id) => handleMaterialChange(index, id)"
          />
        </template>
        <template v-else-if="column.key === 'batchNo'">
          <a-input v-model:value="record.batchNo" placeholder="批次号（必填）" />
        </template>
        <template v-else-if="column.key === 'quantity'">
          <a-input-number v-model:value="record.quantity" :min="0.001" :precision="3" style="width: 100%" />
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
  import { receiveRawInbound, searchMaterialOptions, listSupplierOptions } from '../raw.api';
  import { HANDLING_TYPE_OPTIONS, SOURCE_TYPE_OPTIONS } from '../raw.data';

  const emit = defineEmits(['success']);

  const open = defineModel<boolean>('open', { default: false });
  const { createMessage } = useMessage();

  const submitting = ref(false);
  const supplierLoading = ref(false);
  const supplierOptions = ref<{ label: string; value: string }[]>([]);

  const materialOptions = ref<{ label: string; value: string }[]>([]);
  const materialSearching = ref(false);
  const materialLabelMap: Record<string, string> = {};

  const form = reactive({
    sourceType: 'PURCHASE',
    handlingType: 'DIRECT',
    erpOrderNo: '',
    supplierId: undefined as string | undefined,
  });

  type Row = {
    _key: number;
    materialId?: string;
    batchNo: string;
    quantity: number;
  };
  const rows = ref<Row[]>([]);
  let rowSeq = 0;

  const detailColumns = [
    { title: '物料', key: 'materialId', width: 340 },
    { title: '批次号', key: 'batchNo', width: 200 },
    { title: '到货数量', key: 'quantity', width: 160 },
    { title: '操作', key: 'action', width: 80 },
  ];

  const supplierLabel = computed(() => (form.sourceType === 'CONSIGNED' ? '受托方' : '供应商'));
  /** 外购直入必须带供应商（金蝶采购入库单的 FSupplierId 必填）；受托一律必填受托方 */
  const supplierRequired = computed(
    () => form.sourceType === 'CONSIGNED' || (form.sourceType === 'PURCHASE' && form.handlingType === 'DIRECT')
  );
  const showSupplierWarning = computed(() => form.sourceType === 'PURCHASE' && form.handlingType === 'DIRECT');

  watch(open, (visible) => {
    if (visible) {
      resetAll();
      void loadSuppliers();
      void loadMaterialOptions();
    }
  });

  function resetAll() {
    form.sourceType = 'PURCHASE';
    form.handlingType = 'DIRECT';
    form.erpOrderNo = '';
    form.supplierId = undefined;
    rows.value = [{ _key: ++rowSeq, batchNo: '', quantity: 1 }];
  }

  /** 切换来源类型时清掉已选供应商/受托方——两种档案语义不同，不能沿用 */
  function handleSourceTypeChange() {
    form.supplierId = undefined;
  }

  function addRow() {
    rows.value.push({ _key: ++rowSeq, batchNo: '', quantity: 1 });
  }

  function removeRow(index: number) {
    rows.value.splice(index, 1);
  }

  async function loadSuppliers() {
    supplierLoading.value = true;
    try {
      const list: any = await listSupplierOptions();
      supplierOptions.value = (list || []).map((s: any) => ({
        label: `${s.supplierCode} ${s.supplierName}`,
        value: s.id,
      }));
    } catch (e) {
      // 供应商加载失败不阻塞登记，提交时后端会给出明确文案
    } finally {
      supplierLoading.value = false;
    }
  }

  async function loadMaterialOptions(keyword?: string) {
    materialSearching.value = true;
    try {
      const res: any = await searchMaterialOptions(keyword);
      const options = (res?.records || []).map((m: any) => ({
        label: `${m.materialCode} ${m.materialName}`,
        value: m.id,
      }));
      // 合并已选项，避免搜索后已选物料在下拉里显示成 id
      const selected = rows.value
        .map((r) => r.materialId)
        .filter(Boolean)
        .map((id) => ({ value: id as string, label: materialLabelMap[id as string] || (id as string) }));
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

  let materialSearchTimer: any = null;
  function handleMaterialSearch(keyword: string) {
    if (materialSearchTimer) {
      clearTimeout(materialSearchTimer);
    }
    materialSearchTimer = setTimeout(() => loadMaterialOptions(keyword), 300);
  }

  function handleMaterialChange(index: number, id: string) {
    const row = rows.value[index];
    if (!row) {
      return;
    }
    if (!id) {
      return;
    }
    // 只保留选择结果，物料编码由后端按主数据回填，前端不臆造
    row.materialId = id;
  }

  function validate(): boolean {
    if (!form.sourceType || !form.handlingType) {
      createMessage.warning('请选择来源类型与处理方式');
      return false;
    }
    if (!form.erpOrderNo || !form.erpOrderNo.trim()) {
      createMessage.warning('ERP 订单号不能为空');
      return false;
    }
    if (supplierRequired.value && !form.supplierId) {
      createMessage.warning(`请选择${supplierLabel.value}`);
      return false;
    }
    if (!rows.value.length) {
      createMessage.warning('至少需要一条到货明细');
      return false;
    }
    for (const row of rows.value) {
      if (!row.materialId) {
        createMessage.warning('请为每一行选择物料');
        return false;
      }
      if (!row.batchNo || !row.batchNo.trim()) {
        createMessage.warning('请为每一行填写批次号');
        return false;
      }
      if (!row.quantity || Number(row.quantity) <= 0) {
        createMessage.warning('到货数量必须大于 0');
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
      const consigned = form.sourceType === 'CONSIGNED';
      const payload: any = {
        sourceType: form.sourceType,
        handlingType: form.handlingType,
        erpOrderNo: form.erpOrderNo.trim(),
        supplierId: consigned ? undefined : form.supplierId,
        consignorId: consigned ? form.supplierId : undefined,
        details: rows.value.map((row) => ({
          materialId: row.materialId,
          quantity: Number(row.quantity),
          batchNo: row.batchNo.trim(),
        })),
      };
      const res: any = await receiveRawInbound(payload);
      const suffix =
        form.handlingType === 'CONVERT'
          ? '（大改小：仅登记，不入库存，产出请走改制单）'
          : '（段差棒直入：请在列表点「上架」选择库位后入库）';
      createMessage.success(`登记成功：${res?.orderNo || ''}${suffix}`);
      open.value = false;
      emit('success');
    } finally {
      submitting.value = false;
    }
  }
</script>

<style lang="less" scoped>
  .raw-inbound__details-title {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin: 8px 0;
    font-weight: 600;
  }

  .raw-inbound__required-hint {
    margin-top: 4px;
    color: #ff4d4f;
    font-size: 12px;
    line-height: 1.4;
  }

  .raw-inbound__mode-tip {
    color: rgba(0, 0, 0, 0.65);
    line-height: 22px;
  }
</style>
