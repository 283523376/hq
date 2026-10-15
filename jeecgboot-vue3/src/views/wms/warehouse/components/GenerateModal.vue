<template>
  <BasicModal
    v-bind="$attrs"
    @register="registerModal"
    title="批量生成库位"
    @ok="handleSubmit"
    :okButtonProps="{ disabled: overLimit }"
    :confirmLoading="submitting"
    width="640px"
    destroyOnClose
  >
    <BasicForm @register="registerForm" />
    <a-alert v-if="overLimit" type="error" show-icon class="mt-2" :message="overLimitMessage" />
    <a-alert v-else-if="total > 0" type="info" show-icon class="mt-2" :message="`本次预计生成 ${total} 个库位`" />
  </BasicModal>
</template>

<script lang="ts" name="wms-location-generate" setup>
  import { computed, ref } from 'vue';
  import { BasicModal, useModalInner } from '/@/components/Modal';
  import { BasicForm, useForm } from '/@/components/Form';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { generateLocations } from '../location.api';

  /** 与后端 WmsConstants.LOCATION_GEN_MAX_PER_REQUEST 一致 */
  const MAX_PER_REQUEST = 50000;

  const emit = defineEmits(['success', 'register']);
  const { createMessage } = useMessage();
  const submitting = ref(false);

  /** 当前选中库区的容量包络（上限），用于前端即时校验 */
  const envelope = ref<{ rowCount?: number; colCount?: number; layerCount?: number; name?: string }>({});

  const [registerForm, { setFieldsValue, validate, getFieldsValue, resetFields, updateSchema }] = useForm({
    labelWidth: 110,
    schemas: [
      { label: '库区', field: 'warehouseId', component: 'Select', required: true, componentProps: { options: [] } },
      { label: '起始排', field: 'rowFrom', component: 'InputNumber', required: true, defaultValue: 1 },
      { label: '结束排', field: 'rowTo', component: 'InputNumber', required: true },
      { label: '起始列', field: 'colFrom', component: 'InputNumber', required: true, defaultValue: 1 },
      { label: '结束列', field: 'colTo', component: 'InputNumber', required: true },
      { label: '起始层', field: 'layerFrom', component: 'InputNumber', required: true, defaultValue: 1 },
      { label: '结束层', field: 'layerTo', component: 'InputNumber', required: true },
    ],
    showActionButtonGroup: false,
    baseColProps: { span: 24 },
  });

  const [registerModal, { closeModal, setModalProps }] = useModalInner(async (data) => {
    await resetFields();
    envelope.value = {};
    submitting.value = false;
    setModalProps({ confirmLoading: false });
    const w = data?.warehouse;
    if (!w) {
      return;
    }
    envelope.value = {
      rowCount: w.rowCount,
      colCount: w.colCount,
      layerCount: w.layerCount,
      name: w.warehouseName,
    };
    // 库区在左侧树里已经选定了，这里只作展示，避免「树选 A、弹窗选 B」的歧义
    updateSchema({
      field: 'warehouseId',
      componentProps: {
        options: [{ label: w.warehouseName, value: w.id }],
        disabled: true,
      },
    });
    await setFieldsValue({
      warehouseId: w.id,
      rowFrom: 1,
      rowTo: w.rowCount ?? undefined,
      colFrom: 1,
      colTo: w.colCount ?? undefined,
      layerFrom: 1,
      layerTo: w.layerCount ?? undefined,
    });
  });

  /**
   * 实时预计生成数 = 各段长度的乘积。
   *
   * 刻意叫「本次预计生成数」而非「仓库总容量」—— 库区维度只是**上限**，
   * 料箱不一定堆满仓库，范围由运维自己选。
   */
  const total = computed(() => {
    const v: any = getFieldsValue();
    const span = (from?: number, to?: number) => (from >= 1 && to >= from ? to - from + 1 : 0);
    return span(v.rowFrom, v.rowTo) * span(v.colFrom, v.colTo) * span(v.layerFrom, v.layerTo);
  });

  /** 超出声明维度的第一项，返回给用户看的提示（未超出则空） */
  const envelopeError = computed(() => {
    const v: any = getFieldsValue();
    const check = (label: string, to?: number, cap?: number) => (to && cap && to > cap ? `${label} ${to} 超出库区上限 ${cap}` : '');
    return (
      check('结束排', v.rowTo, envelope.value.rowCount) ||
      check('结束列', v.colTo, envelope.value.colCount) ||
      check('结束层', v.layerTo, envelope.value.layerCount)
    );
  });

  const overLimit = computed(() => total.value > MAX_PER_REQUEST || !!envelopeError.value);

  const overLimitMessage = computed(() =>
    envelopeError.value
      ? envelopeError.value
      : `单次最多生成 ${MAX_PER_REQUEST} 个库位，本次 ${total.value} 个，请按层 / 列分批`,
  );

  async function handleSubmit() {
    if (overLimit.value) {
      createMessage.error(overLimitMessage.value);
      return;
    }
    let values;
    try {
      values = await validate();
    } catch {
      return;
    }
    submitting.value = true;
    setModalProps({ confirmLoading: true });
    try {
      const res: any = await generateLocations(values);
      // skipped 是「已存在被跳过」而不是失败 —— 必须显式说出来，
      // 否则重跑后看到「生成了 0 个」会被误读为失败。
      createMessage.success(`生成 ${res?.generated ?? 0} 个，跳过 ${res?.skipped ?? 0} 个`);
      closeModal();
      emit('success');
    } finally {
      submitting.value = false;
      setModalProps({ confirmLoading: false });
    }
  }
</script>