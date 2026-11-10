<template>
  <BasicModal v-bind="$attrs" @register="registerModal" title="库位属性" @ok="handleSubmit" width="520px" destroyOnClose>
    <a-alert
      v-if="isOccupied"
      type="warning"
      show-icon
      class="mb-3"
      message="该库位已占用（库位内有物料），不允许修改属性。"
      description="若确认该库位已清空，请使用列表中的「释放」操作。"
    />
    <BasicForm @register="registerForm" />
  </BasicModal>
</template>

<script lang="ts" name="wms-location-attr-modal" setup>
  import { ref } from 'vue';
  import { BasicModal, useModalInner } from '/@/components/Modal';
  import { BasicForm, useForm } from '/@/components/Form';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { attrFormSchema } from '../location.data';
  import { updateLocationAttr } from '../location.api';

  const emit = defineEmits(['success', 'register']);
  const { createMessage } = useMessage();
  /** 占用时后端会拒绝，这里先禁用提交，避免让用户白填一遍 */
  const isOccupied = ref(false);

  const [registerForm, { resetFields, setFieldsValue, validate }] = useForm({
    labelWidth: 100,
    schemas: attrFormSchema,
    showActionButtonGroup: false,
    baseColProps: { span: 24 },
  });

  const [registerModal, { setModalProps, closeModal }] = useModalInner(async (data) => {
    await resetFields();
    setModalProps({ confirmLoading: false });
    isOccupied.value = data?.record?.status === '占用';
    await setFieldsValue({ ...data?.record });
  });

  async function handleSubmit() {
    // 后端同样强制（约束②），这里只是提前反馈，不让用户白填
    if (isOccupied.value) {
      createMessage.error('库位内有物料，不允许修改属性');
      return;
    }
    let values;
    try {
      values = await validate();
    } catch {
      return;
    }
    setModalProps({ confirmLoading: true });
    try {
      await updateLocationAttr(values);
      closeModal();
      emit('success');
    } finally {
      setModalProps({ confirmLoading: false });
    }
  }
</script>