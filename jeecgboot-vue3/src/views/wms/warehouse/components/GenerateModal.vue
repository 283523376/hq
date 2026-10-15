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
  import { computed, reactive, ref } from 'vue';
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

  /**
   * 范围值的**本地响应式副本**。
   *
   * <p>为什么不用 `getFieldsValue()`：
   * 1. 它在表单注册前会返回 `undefined`（实现是 `unref(formRef)?.getFieldsValue()`，
   *    formRef 未挂载时为 null），在 computed 里直接解引用会**在渲染期抛错**，
   *    而渲染期抛错会打断 Vue 的 patch，导致整片内容区（连其它页面一起）空白；
   * 2. 它**不是响应式的**（内部只是一次函数调用），所以拿它做 computed 的依赖，
   *    预计生成数永远不会随输入更新。
   *
   * <p>本副本由各字段的 `onUpdate:value` 事件驱动。选这个事件而不是 `onChange`，
   * 是因为 Vben 的 FormItem 会把 `onChange` 绑给它自己的 `handleFormValuesChange`
   * 并且**排在 componentProps 之后**，同名的自定义监听会被覆盖。
   */
  const range = reactive<Record<string, number | undefined>>({
    rowFrom: undefined,
    rowTo: undefined,
    colFrom: undefined,
    colTo: undefined,
    layerFrom: undefined,
    layerTo: undefined,
  });

  /** 每个字段的 componentProps：把值同步进 range（不动 Vben 自己的 value 绑定） */
  function track(field: string) {
    return {
      'onUpdate:value': (v: number) => {
        range[field] = v;
      },
    };
  }

  const [registerForm, { setFieldsValue, validate, resetFields, updateSchema }] = useForm({
    labelWidth: 110,
    schemas: [
      { label: '库区', field: 'warehouseId', component: 'Select', required: true, componentProps: { options: [] } },
      { label: '起始排', field: 'rowFrom', component: 'InputNumber', required: true, defaultValue: 1, componentProps: track('rowFrom') },
      { label: '结束排', field: 'rowTo', component: 'InputNumber', required: true, componentProps: track('rowTo') },
      { label: '起始列', field: 'colFrom', component: 'InputNumber', required: true, defaultValue: 1, componentProps: track('colFrom') },
      { label: '结束列', field: 'colTo', component: 'InputNumber', required: true, componentProps: track('colTo') },
      { label: '起始层', field: 'layerFrom', component: 'InputNumber', required: true, defaultValue: 1, componentProps: track('layerFrom') },
      { label: '结束层', field: 'layerTo', component: 'InputNumber', required: true, componentProps: track('layerTo') },
    ],
    showActionButtonGroup: false,
    baseColProps: { span: 24 },
  });

  /** 各段长度；from/to 任一缺失或倒挂都按 0 计（= 未填完整） */
  function spanOf(from?: number, to?: number) {
    return from != null && to != null && from >= 1 && to >= from ? to - from + 1 : 0;
  }

  /**
   * 本次预计生成数 = 各段长度的乘积。
   *
   * 刻意叫「本次预计生成数」而非「仓库总容量」—— 库区维度只是**上限**，
   * 料箱不一定堆满仓库，范围由运维自己选。
   */
  const total = computed(
    () =>
      spanOf(range.rowFrom, range.rowTo) *
      spanOf(range.colFrom, range.colTo) *
      spanOf(range.layerFrom, range.layerTo),
  );

  /** 超出声明维度的第一项，返回给用户看的提示（未超出则空） */
  const envelopeError = computed(() => {
    const check = (label: string, to?: number, cap?: number) =>
      to != null && cap != null && to > cap ? `${label} ${to} 超出库区上限 ${cap}` : '';
    return (
      check('结束排', range.rowTo, envelope.value.rowCount) ||
      check('结束列', range.colTo, envelope.value.colCount) ||
      check('结束层', range.layerTo, envelope.value.layerCount)
    );
  });

  const overLimit = computed(() => total.value > MAX_PER_REQUEST || !!envelopeError.value);

  const overLimitMessage = computed(() =>
    envelopeError.value
      ? envelopeError.value
      : `单次最多生成 ${MAX_PER_REQUEST} 个库位，本次 ${total.value} 个，请按层 / 列分批`,
  );

  /** 范围是否填完整（用于提交前的兜底判断） */
  function rangeFilled(v: Record<string, any>) {
    return [v.rowFrom, v.rowTo, v.colFrom, v.colTo, v.layerFrom, v.layerTo].every((x) => x != null);
  }

  const [registerModal, { closeModal, setModalProps }] = useModalInner(async (data) => {
    await resetFields();
    envelope.value = {};
    submitting.value = false;
    Object.keys(range).forEach((k) => (range[k] = undefined));
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
    const initial = {
      warehouseId: w.id,
      rowFrom: 1,
      rowTo: w.rowCount ?? undefined,
      colFrom: 1,
      colTo: w.colCount ?? undefined,
      layerFrom: 1,
      layerTo: w.layerCount ?? undefined,
    };
    Object.assign(range, initial);
    await setFieldsValue(initial);
  });

  async function handleSubmit() {
    let values: Record<string, any>;
    try {
      values = await validate();
    } catch {
      return;
    }
    // 提交前用**校验后的真值**再判一次：上面的实时 computed 只是便利，
    // 不能让它成为唯一防线（若将来某个字段的 onUpdate 事件不再触发，
    // 实时值会过期，而这里不会）。
    if (rangeFilled(values)) {
      const n =
        spanOf(values.rowFrom, values.rowTo) *
        spanOf(values.colFrom, values.colTo) *
        spanOf(values.layerFrom, values.layerTo);
      const cap = envelope.value;
      const envErr =
        (values.rowTo != null && cap.rowCount != null && values.rowTo > cap.rowCount
          ? `结束排 ${values.rowTo} 超出库区上限 ${cap.rowCount}`
          : '') ||
        (values.colTo != null && cap.colCount != null && values.colTo > cap.colCount
          ? `结束列 ${values.colTo} 超出库区上限 ${cap.colCount}`
          : '') ||
        (values.layerTo != null && cap.layerCount != null && values.layerTo > cap.layerCount
          ? `结束层 ${values.layerTo} 超出库区上限 ${cap.layerCount}`
          : '');
      if (envErr) {
        createMessage.error(envErr);
        return;
      }
      if (n > MAX_PER_REQUEST) {
        createMessage.error(`单次最多生成 ${MAX_PER_REQUEST} 个库位，本次 ${n} 个，请按层 / 列分批`);
        return;
      }
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