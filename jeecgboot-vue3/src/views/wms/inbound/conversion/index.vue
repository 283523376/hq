<!-- eslint-disable vue/multi-word-component-names -->
<template>
  <div class="wms-conversion">
    <a-alert
      type="info"
      show-icon
      class="wms-conversion__alert"
      message="改制单（大改小）：投入原材料只登记不入库存；「完成」时按产出生成生产入库单，产出走生产入库通道入账"
    >
      <template #description>
        <div>建单前生产订单必须已从 ERP 同步到 WMS 且已下达；产出物料须与生产订单成品一致，数量不得超过计划量。</div>
      </template>
    </a-alert>

    <BasicTable @register="registerTable">
      <template #tableTitle>
        <a-button type="primary" preIcon="ant-design:plus-outlined" v-auth="'wms:conversion:create'" @click="openCreate">
          建单
        </a-button>
      </template>
      <template #action="{ record }">
        <TableAction v-if="getActions(record).length" :actions="getActions(record)" />
        <span v-else>—</span>
      </template>
    </BasicTable>

    <ConversionCreateModal v-model:open="createOpen" @success="reload" />
  </div>
</template>

<script lang="ts" name="wms-conversion" setup>
  import { ref } from 'vue';
  import { Modal } from 'ant-design-vue';
  import { BasicTable, TableAction } from '/@/components/Table';
  import { useListPage } from '/@/hooks/system/useListPage';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { columns, searchFormSchema } from './conversion.data';
  import { cancelConversion, completeConversion, pageConversion } from './conversion.api';
  import ConversionCreateModal from './components/ConversionCreateModal.vue';

  const { createMessage } = useMessage();

  const { tableContext } = useListPage({
    tableProps: {
      api: pageConversion,
      columns,
      rowKey: 'id',
      formConfig: { schemas: searchFormSchema },
      actionColumn: { width: 160 },
    },
  });
  const [registerTable, { reload }] = tableContext;

  const createOpen = ref(false);

  function openCreate() {
    createOpen.value = true;
  }

  function getActions(record: any) {
    if (record.status !== 'DRAFT') {
      return [];
    }
    return [
      {
        label: '完成',
        auth: 'wms:conversion:execute',
        onClick: () => handleComplete(record),
      },
      {
        label: '取消',
        auth: 'wms:conversion:cancel',
        color: 'error',
        onClick: () => handleCancel(record),
      },
    ];
  }

  function handleComplete(record: any) {
    Modal.confirm({
      title: `完成改制 ${record.conversionNo}？`,
      content: `确认后按产出 ${record.outputMaterialCode} × ${record.outputQty} 生成生产入库单，单据进入「已完成」。`,
      okText: '确认完成',
      cancelText: '取消',
      async onOk() {
        // 不 try/catch：后端拒绝文案（如重复完成）由 defHttp 原样弹出，弹窗保持打开
        await completeConversion(record.id);
        createMessage.success('改制完成，已生成生产入库单');
        reload();
      },
    });
  }

  function handleCancel(record: any) {
    Modal.confirm({
      title: `取消改制 ${record.conversionNo}？`,
      content: '取消后单据进入「已取消」终态，不可恢复。',
      okText: '确认取消',
      okType: 'danger',
      cancelText: '返回',
      async onOk() {
        await cancelConversion(record.id);
        createMessage.success('已取消');
        reload();
      },
    });
  }
</script>

<style lang="less" scoped>
  .wms-conversion {
    padding: 16px;
  }

  .wms-conversion__alert {
    margin-bottom: 12px;
  }
</style>
