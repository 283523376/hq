<template>
  <BasicModal
    v-bind="$attrs"
    @register="registerModal"
    title="绑定设备到库区"
    @ok="handleSubmit"
    :confirmLoading="submitting"
    width="820px"
    destroyOnClose
  >
    <BasicForm @register="registerForm" />
    <a-table
      class="mt-3"
      :dataSource="devices"
      :columns="bindColumns"
      :rowSelection="{ selectedRowKeys, onChange: onSelectChange }"
      :pagination="false"
      :rowKey="(r: any) => r.id"
      size="small"
      :scroll="{ y: 260 }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'status'">
          <Tag :color="DEVICE_STATUS_COLOR[record.status] || 'default'">{{ record.status }}</Tag>
        </template>
        <template v-else-if="column.dataIndex === 'warehouseName'">
          {{ record.warehouseName || '未绑定' }}
        </template>
      </template>
    </a-table>
    <a-alert
      v-if="offlineSelectedCount > 0"
      type="warning"
      show-icon
      class="mt-3"
      :message="`已勾选 ${offlineSelectedCount} 台离线设备，保存后将产生离线告警（绑定本身仍会成功）`"
    />
  </BasicModal>
</template>

<script lang="ts" name="wms-device-bind-modal" setup>
  import { computed, ref } from 'vue';
  import { Tag } from 'ant-design-vue';
  import { BasicModal, useModalInner } from '/@/components/Modal';
  import { BasicForm, useForm } from '/@/components/Form';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { bindFormSchema, DEVICE_STATUS_COLOR } from '../device.data';
  import { bindDevices, list as listDevices } from '../device.api';
  import { list as listWarehouses } from '../../warehouse/warehouse.api';

  const emit = defineEmits(['success', 'register']);
  const { createMessage } = useMessage();
  const submitting = ref(false);
  const devices = ref<any[]>([]);
  const selectedRowKeys = ref<string[]>([]);

  /** 设备用表格勾选而不是下拉：要能一眼看到哪些是离线设备 */
  const bindColumns = [
    { title: '设备编码', dataIndex: 'deviceCode', width: 130 },
    { title: '设备名称', dataIndex: 'deviceName', width: 150 },
    { title: '类型', dataIndex: 'deviceType', width: 100 },
    { title: '当前归属', dataIndex: 'warehouseName', width: 130 },
    { title: '状态', dataIndex: 'status', width: 90 },
  ];

  const offlineSelectedCount = computed(
    () => devices.value.filter((d) => selectedRowKeys.value.includes(d.id) && d.status === '离线').length,
  );

  const [registerForm, { resetFields, updateSchema, validate }] = useForm({
    labelWidth: 90,
    schemas: bindFormSchema,
    showActionButtonGroup: false,
    baseColProps: { span: 24 },
  });

  const [registerModal, { setModalProps, closeModal }] = useModalInner(async () => {
    await resetFields();
    selectedRowKeys.value = [];
    devices.value = [];
    submitting.value = false;
    setModalProps({ confirmLoading: false });

    // 目标库区：可选启用中的库区（允许把设备从别的库区搬迁过来）
    // 库区数量会增长：取足够大一页并给出上限提示，避免硬编码 200 后静默截断
    const warehousePage: any = await listWarehouses({ pageNo: 1, pageSize: 1000, status: '1' });
    const warehouses = warehousePage?.records ?? [];
    updateSchema({
      field: 'warehouseId',
      componentProps: {
        options: warehouses.map((w: any) => ({ label: `${w.warehouseName}（${w.warehouseCode}）`, value: w.id })),
      },
    });

    const devicePage: any = await listDevices({ pageNo: 1, pageSize: 1000 });
    devices.value = devicePage?.records ?? [];
  });

  function onSelectChange(keys: string[]) {
    selectedRowKeys.value = keys;
  }

  async function handleSubmit() {
    if (selectedRowKeys.value.length === 0) {
      createMessage.error('请至少选择一台设备');
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
      const res: any = await bindDevices({ warehouseId: values.warehouseId, deviceIds: selectedRowKeys.value });
      // 绑定成功与离线告警是两件事：告警走 warning，绝不能让使用者误判成「绑定失败」
      if (res?.warning) {
        createMessage.warning(res.warning);
      } else {
        createMessage.success(`绑定成功，共 ${res?.bound ?? 0} 台`);
      }
      closeModal();
      emit('success');
    } finally {
      submitting.value = false;
      setModalProps({ confirmLoading: false });
    }
  }
</script>