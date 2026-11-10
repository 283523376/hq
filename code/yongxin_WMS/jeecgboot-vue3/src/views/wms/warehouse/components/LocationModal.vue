<template>
  <BasicModal v-bind="$attrs" @register="registerModal" :title="title" @ok="handleSubmit" width="520px" destroyOnClose>
    <a-alert type="info" show-icon class="mb-3" message="库位编码由系统按坐标自动生成，无需填写。" />
    <BasicForm @register="registerForm" />
  </BasicModal>
</template>

<script lang="ts" name="wms-location-modal" setup>
  import { computed, ref, unref } from 'vue';
  import { BasicModal, useModalInner } from '/@/components/Modal';
  import { BasicForm, useForm } from '/@/components/Form';
  import { formSchema } from '../location.data';
  import { saveOrUpdateLocation } from '../location.api';

  const emit = defineEmits(['success', 'register']);
  const isUpdate = ref(false);
  const title = computed(() => (unref(isUpdate) ? '编辑库位' : '新增库位'));

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
    } else if (data?.warehouseId) {
      await setFieldsValue({ warehouseId: data.warehouseId });
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
      await saveOrUpdateLocation(values, unref(isUpdate));
      closeModal();
      emit('success');
    } finally {
      setModalProps({ confirmLoading: false });
    }
  }
</script>