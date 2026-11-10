<template>
  <BasicModal v-bind="$attrs" @register="registerModal" :title="title" @ok="handleSubmit" width="650px" destroyOnClose>
    <BasicForm @register="registerForm" />
  </BasicModal>
</template>

<script lang="ts" name="wms-material-modal" setup>
  import { computed, ref, unref } from 'vue';
  import { BasicModal, useModalInner } from '/@/components/Modal';
  import { BasicForm, useForm } from '/@/components/Form';
  import { formSchema } from '../material.data';
  import { queryTreeList } from '../category.api';
  import { saveOrUpdateMaterial } from '../material.api';

  const emit = defineEmits(['success', 'register']);

  const isUpdate = ref(false);
  const title = computed(() => (unref(isUpdate) ? '编辑物料' : '新增物料'));

  const [registerForm, { setFieldsValue, resetFields, updateSchema, validate }] = useForm({
    labelWidth: 110,
    schemas: formSchema,
    showActionButtonGroup: false,
    baseColProps: { span: 24 },
  });

  const [registerModal, { setModalProps, closeModal }] = useModalInner(async (data) => {
    await resetFields();
    setModalProps({ confirmLoading: false });
    isUpdate.value = !!data?.isUpdate;
    await loadCategoryTree();
    if (unref(isUpdate)) {
      await setFieldsValue({ ...data.record });
    } else if (data?.record?.categoryId) {
      // 从左侧树带过来的分类
      await setFieldsValue({ categoryId: data.record.categoryId });
    }
  });

  /** 把分类树灌进「所属分类」下拉 */
  async function loadCategoryTree() {
    const res: any = await queryTreeList();
    const list = Array.isArray(res) ? res : res?.result ?? [];
    await updateSchema({ field: 'categoryId', componentProps: { treeData: mapTree(list) } });
  }

  function mapTree(list: any[]): any[] {
    return (list || []).map((n) => {
      const node: any = { id: n.key, title: n.title };
      if (n.children && n.children.length) {
        node.children = mapTree(n.children);
      }
      return node;
    });
  }

  async function handleSubmit() {
    let values;
    try {
      values = await validate();
    } catch {
      return;
    }
    setModalProps({ confirmLoading: true });
    try {
      await saveOrUpdateMaterial(values, unref(isUpdate));
      closeModal();
      emit('success');
    } finally {
      setModalProps({ confirmLoading: false });
    }
  }
</script>
