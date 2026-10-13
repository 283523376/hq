<template>
  <BasicModal v-bind="$attrs" @register="registerModal" :title="title" @ok="handleSubmit" width="600px" destroyOnClose>
    <BasicForm @register="registerForm" />
  </BasicModal>
</template>

<script lang="ts" name="wms-category-modal" setup>
  import { computed, ref, unref } from 'vue';
  import { BasicModal, useModalInner } from '/@/components/Modal';
  import { BasicForm, useForm } from '/@/components/Form';
  import { categoryFormSchema } from '../category.data';
  import { queryTreeList, queryCategoryById, saveOrUpdateCategory } from '../category.api';

  const emit = defineEmits(['success', 'register']);

  const isUpdate = ref(false);
  const title = computed(() => (unref(isUpdate) ? '编辑分类' : '新增分类'));

  const [registerForm, { setFieldsValue, resetFields, updateSchema, validate }] = useForm({
    labelWidth: 100,
    schemas: categoryFormSchema,
    showActionButtonGroup: false,
    baseColProps: { span: 24 },
  });

  const [registerModal, { setModalProps, closeModal }] = useModalInner(async (data) => {
    await resetFields();
    setModalProps({ confirmLoading: false });
    isUpdate.value = !!data?.isUpdate;
    await loadCategoryTree();
    if (unref(isUpdate) && data?.record?.id) {
      // 树节点只带了 id/title，编辑需要按 id 回查完整记录
      const res: any = await queryCategoryById({ id: data.record.id });
      const record = res?.result ?? res;
      if (record && record.id) {
        await setFieldsValue({ ...record });
      }
    } else if (data?.record?.parentId) {
      // 新增下级：预置父级
      await setFieldsValue({ parentId: data.record.parentId });
    }
  });

  /** 把分类树灌进「父级分类」下拉 */
  async function loadCategoryTree() {
    const res: any = await queryTreeList();
    const list = Array.isArray(res) ? res : res?.result ?? [];
    const treeData = [{ id: '0', title: '顶级分类', children: mapTree(list) }];
    await updateSchema({ field: 'parentId', componentProps: { treeData } });
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
      await saveOrUpdateCategory(values, unref(isUpdate));
      closeModal();
      emit('success');
    } finally {
      setModalProps({ confirmLoading: false });
    }
  }
</script>
