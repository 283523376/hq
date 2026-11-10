<template>
  <BasicModal v-bind="$attrs" @register="registerModal" :title="title" @ok="handleSubmit" width="600px" destroyOnClose>
    <BasicForm @register="registerForm" />
  </BasicModal>
</template>

<script lang="ts" name="wms-device-modal" setup>
  import { computed, ref, unref } from 'vue';
  import { BasicModal, useModalInner } from '/@/components/Modal';
  import { BasicForm, useForm } from '/@/components/Form';
  import { formSchema } from '../device.data';
  import { saveOrUpdateDevice } from '../device.api';

  const emit = defineEmits(['success', 'register']);
  const isUpdate = ref(false);
  const title = computed(() => (unref(isUpdate) ? '编辑设备' : '新增设备'));

  const [registerForm, { resetFields, setFieldsValue, validate }] = useForm({
    labelWidth: 100,
    schemas: formSchema,
    showActionButtonGroup: false,
    baseColProps: { span: 24 },
  });

  const [registerModal, { setModalProps, closeModal }] = useModalInner(async (data) => {
    await resetFields();
    setModalProps({ confirmLoading: false });
    isUpdate.value = !!data?.isUpdate;
    if (unref(isUpdate) && data?.record) {
      await setFieldsValue({ ...data.record });
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
      await saveOrUpdateDevice(values, unref(isUpdate));
      closeModal();
      emit('success');
    } finally {
      setModalProps({ confirmLoading: false });
    }
  }
</script>