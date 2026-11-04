<!-- eslint-disable vue/multi-word-component-names -->
<template>
  <div class="wms-raw-inbound">
    <a-alert
      type="info"
      show-icon
      class="wms-raw-inbound__alert"
      message="大改小原材料不入 WMS 库存，到货仅登记、物料直送车间；产出请走改制单"
    >
      <template #description>
        <div>
          「大改小（CONVERT）」单据登记后停在「已审核」是设计使然——登记即终态，不需要上架，也不会出现在库存里；
          需加工产出时请到「改制单」建单。
        </div>
        <div>「段差棒直入（DIRECT）」登记后为「待分配」，在行内点「上架」选择库位后才入库存并回写金蝶。</div>
      </template>
    </a-alert>

    <BasicTable @register="registerTable">
      <template #tableTitle>
        <a-button type="primary" preIcon="ant-design:import-outlined" v-auth="'wms:inbound:consigned'" @click="openReceive">
          到货登记
        </a-button>
        <span class="wms-raw-inbound__hint">外购需金蝶采购订单、受托需受托加工订单，登记前会先到金蝶校验订单</span>
      </template>
      <template #action="{ record }">
        <TableAction :actions="getActions(record)" />
      </template>
    </BasicTable>

    <RawReceiveModal v-model:open="receiveOpen" @success="reload" />
    <RawPutawayModal v-model:open="putawayOpen" :record="currentRecord" @success="reload" />
  </div>
</template>

<script lang="ts" name="wms-raw-inbound" setup>
  import { ref } from 'vue';
  import { Modal } from 'ant-design-vue';
  import { BasicTable, TableAction } from '/@/components/Table';
  import { useListPage } from '/@/hooks/system/useListPage';
  import { columns, isConvertOrder, searchFormSchema } from './raw.data';
  import { listRawInbound } from './raw.api';
  import RawReceiveModal from './components/RawReceiveModal.vue';
  import RawPutawayModal from './components/RawPutawayModal.vue';

  const { tableContext } = useListPage({
    tableProps: {
      api: listRawInbound,
      columns,
      rowKey: 'id',
      formConfig: { schemas: searchFormSchema },
      actionColumn: { width: 100 },
    },
  });
  const [registerTable, { reload }] = tableContext;

  const receiveOpen = ref(false);
  const putawayOpen = ref(false);
  const currentRecord = ref<any>(null);

  function openReceive() {
    receiveOpen.value = true;
  }

  function getActions(record: any) {
    const actions: any[] = [];
    // 只有 DIRECT 且待分配的才需要上架；CONVERT 是登记即终态，绝不显示上架按钮
    if (!isConvertOrder(record) && record.inboundStatus === '待分配') {
      actions.push({
        label: '上架',
        auth: 'wms:inbound:consigned',
        onClick: () => openPutaway(record),
      });
    }
    if (isConvertOrder(record)) {
      // 大改小没有上架动作，给一个显式口径入口，避免用户以为单据卡住了
      actions.push({ label: '口径说明', onClick: () => showConvertTip(record) });
    }
    return actions;
  }

  function showConvertTip(record: any) {
    Modal.info({
      title: `大改小登记 ${record.orderNo}`,
      content:
        '大改小原材料不入 WMS 库存，到货仅登记、物料直送车间；产出请走改制单。' +
        '该单停在「已审核」是设计使然（登记即终态），没有上架动作，也不会产生库存。',
    });
  }

  function openPutaway(record: any) {
    currentRecord.value = record;
    putawayOpen.value = true;
  }
</script>

<style lang="less" scoped>
  .wms-raw-inbound {
    padding: 16px;
  }

  .wms-raw-inbound__alert {
    margin-bottom: 12px;
  }

  .wms-raw-inbound__hint {
    margin-left: 12px;
    color: #999;
  }
</style>
