<template>
  <BasicModal v-bind="$attrs" @register="registerModal" :title="title" @ok="handleSubmit" width="560px">
    <BasicForm @register="registerForm" />
    <a-alert type="info" show-icon style="margin-top: 8px" message="修改分类类型后，下次金蝶物料分组同步时自动刷新到对应分类" />
  </BasicModal>
</template>

<script lang="ts" setup>
  import { computed, ref, unref } from 'vue';
  import { BasicModal, useModalInner } from '/@/components/Modal';
  import { BasicForm, useForm } from '/@/components/Form/index';
  import { formSchema } from '../materialGroupMap.data';
  import { saveBatch } from '../materialGroupMap.api';

  const emit = defineEmits(['success', 'register']);
  const isUpdate = ref(true);

  const [registerForm, { resetFields, setFieldsValue, validate }] = useForm({
    labelWidth: 100,
    schemas: formSchema,
    showActionButtonGroup: false,
  });

  const [registerModal, { setModalProps, closeModal }] = useModalInner(async (data) => {
    resetFields();
    setModalProps({ confirmLoading: false });
    isUpdate.value = !!data?.isUpdate;
    if (unref(isUpdate)) {
      setFieldsValue({ ...data.record });
    }
  });

  const title = computed(() => (unref(isUpdate) ? '编辑物料分组映射' : '新增物料分组映射'));

  async function handleSubmit() {
    try {
      const values = await validate();
      setModalProps({ confirmLoading: true });
      // 保存统一走批量 upsert（单条 = 数组里一个元素），按 groupNumber 幂等
      await saveBatch([values]);
      closeModal();
      emit('success');
    } finally {
      setModalProps({ confirmLoading: false });
    }
  }
</script>
