<template>
  <BasicModal v-bind="$attrs" @register="registerModal" :title="title" @ok="handleSubmit" width="620px" destroyOnClose>
    <BasicForm @register="registerForm" />
  </BasicModal>
</template>

<script lang="ts" name="wms-warehouse-modal" setup>
  import { computed, ref, unref } from 'vue';
  import { BasicModal, useModalInner } from '/@/components/Modal';
  import { BasicForm, useForm } from '/@/components/Form';
  import { formSchema } from '../warehouse.data';
  import { saveOrUpdateWarehouse, queryById } from '../warehouse.api';

  const emit = defineEmits(['success', 'register']);

  const isUpdate = ref(false);
  const title = computed(() => (unref(isUpdate) ? '编辑库区' : '新增库区'));

  const [registerForm, { resetFields, setFieldsValue, validate }] = useForm({
    labelWidth: 110,
    schemas: formSchema,
    showActionButtonGroup: false,
    baseColProps: { span: 24 },
  });

  const [registerModal, { setModalProps, closeModal }] = useModalInner(async (data) => {
    await resetFields();
    setModalProps({ confirmLoading: false });
    isUpdate.value = !!data?.isUpdate;
    if (unref(isUpdate) && data?.record?.id) {
      // 树节点只带了 id/title，编辑需要按 id 回查完整记录
      const res: any = await queryById({ id: data.record.id });
      const record = res?.result ?? res;
      if (record && record.id) {
        await setFieldsValue({ ...record });
      }
    }
  });

  async function handleSubmit() {
    let values;
    try {
      values = await validate();
    } catch {
      return;
    }
    setModalProps({ confirmLoading: true });
    try {
      await saveOrUpdateWarehouse(values, unref(isUpdate));
      closeModal();
      emit('success');
    } finally {
      setModalProps({ confirmLoading: false });
    }
  }
</script>