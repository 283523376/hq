<!-- eslint-disable vue/multi-word-component-names -->
<template>
  <BasicModal v-model:open="visible" title="新建库存调拨" :width="860" @ok="handleSubmit" :confirmLoading="submitting">
    <a-form layout="vertical">
      <a-row :gutter="16">
        <a-col :span="12">
          <a-form-item label="调出库区" required>
            <a-select v-model:value="form.outWarehouseId" show-search option-filter-prop="label" placeholder="选择调出库区" @change="onWarehouseChange">
              <a-select-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="`${w.warehouseCode} ${w.warehouseName}`">
                {{ w.warehouseCode }} {{ w.warehouseName }}（{{ w.warehouseType }}）
              </a-select-option>
            </a-select>
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="调入库区" required>
            <a-select v-model:value="form.inWarehouseId" show-search option-filter-prop="label" placeholder="选择调入库区" @change="onWarehouseChange">
              <a-select-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="`${w.warehouseCode} ${w.warehouseName}`">
                {{ w.warehouseCode }} {{ w.warehouseName }}（{{ w.warehouseType }}）
              </a-select-option>
            </a-select>
          </a-form-item>
        </a-col>
      </a-row>

      <a-row :gutter="16">
        <a-col :span="12">
          <a-form-item label="调出区域（可空）">
            <a-input v-model:value="form.outZoneCode" placeholder="米塔 zoneCode，如 OUT_ZONE" />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item :label="sameWarehouse ? '调入区域（同仓必填）' : '调入区域（可空）'" :required="sameWarehouse">
            <a-input v-model:value="form.inZoneCode" placeholder="米塔 zoneCode，如 OUT_ZONE" />
          </a-form-item>
        </a-col>
      </a-row>

      <a-alert
        v-if="sameWarehouse"
        type="info"
        show-icon
        style="margin-bottom: 12px"
        message="同仓区域搬运：系统将向米塔 RMS 下发 task/transfer，只能指定目标区域，具体库位由位置回调确认。"
      />
      <a-alert
        v-else-if="bothSelected"
        type="warning"
        show-icon
        style="margin-bottom: 12px"
        message="跨仓调拨：米塔只做同仓搬运，跨仓段由人工/其它物流完成；出发后库存进入目标仓「在途暂存库位」，需到达/上架确认才实物完成。"
      />

      <a-divider orientation="left">调拨明细</a-divider>
      <a-table :data-source="form.details" :columns="detailColumns" :pagination="false" size="small" rowKey="_key">
        <template #bodyCell="{ column, record, index }">
          <template v-if="column.key === 'material'">
            <a-select
              v-model:value="record.materialId"
              show-search
              option-filter-prop="label"
              placeholder="选择物料"
              style="width: 100%"
              @change="(v) => onMaterialChange(v, record)"
            >
              <a-select-option v-for="m in materials" :key="m.id" :value="m.id" :label="`${m.materialCode} ${m.materialName}`">
                {{ m.materialCode }} {{ m.materialName }}
              </a-select-option>
            </a-select>
          </template>
          <template v-else-if="column.key === 'batchNo'">
            <a-input v-model:value="record.batchNo" placeholder="空=执行时 FIFO" />
          </template>
          <template v-else-if="column.key === 'containerCode'">
            <a-input v-model:value="record.containerCode" placeholder="敏捷仓必填" />
          </template>
          <template v-else-if="column.key === 'transferQty'">
            <a-input-number v-model:value="record.transferQty" :min="0.001" :precision="3" style="width: 100%" />
          </template>
          <template v-else-if="column.key === 'action'">
            <a-button type="link" danger size="small" @click="removeDetail(index)">删除</a-button>
          </template>
        </template>
      </a-table>
      <a-button type="dashed" block style="margin-top: 8px" @click="addDetail">+ 添加明细行</a-button>

      <a-form-item label="备注" style="margin-top: 12px">
        <a-textarea v-model:value="form.remark" :rows="2" placeholder="选填" />
      </a-form-item>
    </a-form>
  </BasicModal>
</template>

<script lang="ts" setup>
  import { computed, ref, watch } from 'vue';
  import { BasicModal } from '/@/components/Modal';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { createTransfer, listMaterial, listWarehouse } from '../transfer.api';

  const props = defineProps<{ open: boolean }>();
  const emit = defineEmits(['update:open', 'success']);

  const { createMessage } = useMessage();

  const visible = computed({
    get: () => props.open,
    set: (v) => emit('update:open', v),
  });

  const submitting = ref(false);
  const warehouses = ref<any[]>([]);
  const materials = ref<any[]>([]);

  const form = ref<any>({
    outWarehouseId: undefined,
    outZoneCode: '',
    inWarehouseId: undefined,
    inZoneCode: '',
    remark: '',
    details: [],
  });

  const detailColumns = [
    { title: '物料', key: 'material', width: 260 },
    { title: '批次', key: 'batchNo', width: 150 },
    { title: '料箱/托盘', key: 'containerCode', width: 150 },
    { title: '数量', key: 'transferQty', width: 120 },
    { title: '操作', key: 'action', width: 80 },
  ];

  const bothSelected = computed(() => !!form.value.outWarehouseId && !!form.value.inWarehouseId);
  const sameWarehouse = computed(() => bothSelected.value && form.value.outWarehouseId === form.value.inWarehouseId);

  watch(
    () => props.open,
    async (open) => {
      if (!open) return;
      resetForm();
      warehouses.value = await fetchAll(listWarehouse);
      materials.value = await fetchAll(listMaterial);
      addDetail();
    },
    { immediate: true }
  );

  async function fetchAll(api: (params?: any) => Promise<any>) {
    const res: any = await api({ pageNo: 1, pageSize: 500 });
    return res?.records || res || [];
  }

  function resetForm() {
    form.value = {
      outWarehouseId: undefined,
      outZoneCode: '',
      inWarehouseId: undefined,
      inZoneCode: '',
      remark: '',
      details: [],
    };
  }

  function addDetail() {
    form.value.details.push({
      _key: `${Date.now()}-${Math.random()}`,
      materialId: undefined,
      materialCode: '',
      batchNo: '',
      containerCode: '',
      unit: '',
      transferQty: 1,
    });
  }

  function removeDetail(index: number) {
    form.value.details.splice(index, 1);
  }

  function onMaterialChange(materialId: string, record: any) {
    const material = materials.value.find((m) => m.id === materialId);
    record.materialCode = material?.materialCode || '';
    record.unit = material?.unit || '';
  }

  function onWarehouseChange() {
    // 换仓后区域需要重新确认：不同仓的区域编码不通用
    form.value.inZoneCode = '';
  }

  async function handleSubmit() {
    if (!form.value.outWarehouseId || !form.value.inWarehouseId) {
      createMessage.warning('请选择调出与调入库区');
      return;
    }
    if (sameWarehouse.value && !form.value.inZoneCode) {
      createMessage.warning('同仓区域搬运必须填写调入区域');
      return;
    }
    const details = form.value.details.filter((d) => d.materialId);
    if (!details.length) {
      createMessage.warning('请至少添加一行明细');
      return;
    }
    submitting.value = true;
    try {
      await createTransfer({ ...form.value, details });
      createMessage.success('创建成功');
      visible.value = false;
      emit('success');
    } finally {
      submitting.value = false;
    }
  }
</script>
