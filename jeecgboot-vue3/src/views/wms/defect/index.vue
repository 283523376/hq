<!-- eslint-disable vue/multi-word-component-names -->
<template>
  <div :class="`${prefixCls}`">
    <a-tabs v-model:activeKey="activeTab">
      <!-- ---------------------------------------------------- RK-005 不良品记录 -->
      <a-tab-pane key="records" tab="不良品记录">
        <BasicTable @register="registerRecordTable">
          <template #tableTitle>
            <a-button type="primary" preIcon="ant-design:plus-outlined" v-auth="'wms:defect:inbound'" @click="openInbound">
              不良品入库登记
            </a-button>
            <span class="tip">受托不良来源单号必须等于受托入库单号，否则反查不到受托方，退货会失败</span>
          </template>
          <template #action="{ record }">
            <TableAction v-if="getRecordActions(record).length" :actions="getRecordActions(record)" />
            <span v-else>—</span>
          </template>
        </BasicTable>
      </a-tab-pane>

      <!-- ---------------------------------------------------- 废品仓台账（XT-001） -->
      <a-tab-pane key="ledger" tab="废品仓台账">
        <BasicTable @register="registerTable">
          <template #tableTitle>
            <span class="tip">废品仓当前不良品；报废后不可撤销，请核对数量与原因</span>
          </template>
          <template #action="{ record }">
            <TableAction :actions="getActions(record)" />
          </template>
        </BasicTable>
      </a-tab-pane>
    </a-tabs>

    <a-modal v-model:open="scrapOpen" title="报废出库" :confirmLoading="submitting" @ok="submitScrap">
      <a-form layout="vertical">
        <a-form-item label="物料">
          <span>{{ current?.materialCode }} {{ current?.materialName }}</span>
        </a-form-item>
        <a-form-item label="批次 / 库位">
          <span>{{ current?.batchNo || '—' }} / {{ current?.locationCode || '—' }}</span>
        </a-form-item>
        <a-form-item label="可报废数量">
          <span>{{ current?.availableQty }}</span>
        </a-form-item>
        <a-form-item label="本次报废数量" required>
          <a-input-number v-model:value="scrapQty" :min="0.001" :max="Number(current?.availableQty || 0)" style="width: 100%" />
        </a-form-item>
        <a-form-item label="报废原因" required>
          <a-textarea v-model:value="scrapReason" :rows="3" placeholder="如：来料报废 / 生产报废 / 超期待处理" />
        </a-form-item>
        <a-alert type="warning" show-icon message="报废将立即扣减废品仓库存并生成报废出库单，不可撤销" />
      </a-form>
    </a-modal>

    <DefectInboundModal v-model:open="inboundOpen" @success="handleInboundSuccess" />
  </div>
</template>

<script lang="ts" name="wms-defect" setup>
  import { h, ref } from 'vue';
  import dayjs from 'dayjs';
  import { Input, Modal } from 'ant-design-vue';
  import { BasicTable, TableAction, useTable } from '/@/components/Table';
  import { useListPage } from '/@/hooks/system/useListPage';
  import { useDesign } from '/@/hooks/web/useDesign';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { columns, recordColumns, recordSearchFormSchema, searchFormSchema } from './defect.data';
  import { list, listRecords, returnToConsignor, scrap } from './defect.api';
  import DefectInboundModal from './components/DefectInboundModal.vue';

  const { prefixCls } = useDesign('wms-defect');
  const { createMessage } = useMessage();

  const activeTab = ref<string>('records');

  // ---------- 废品仓台账（XT-001）
  const { tableContext } = useListPage({
    tableProps: {
      api: list,
      columns,
      rowKey: 'inventoryId',
      formConfig: { schemas: searchFormSchema },
      actionColumn: { width: 100 },
    },
  });
  const [registerTable, { reload }] = tableContext;

  // ---------- 不良品记录（RK-005）
  const [registerRecordTable, { reload: reloadRecords }] = useTable({
    api: listRecords,
    columns: recordColumns,
    rowKey: 'id',
    formConfig: { schemas: recordSearchFormSchema },
    // 搜索区的时间是 RangePicker（[起, 止]），后端要 createTimeBegin/createTimeEnd 两个参数（按天闭区间）
    beforeFetch: (params) => {
      const { timeRange, ...rest } = params;
      if (Array.isArray(timeRange) && timeRange.length === 2) {
        rest.createTimeBegin = dayjs(timeRange[0]).format('YYYY-MM-DD');
        rest.createTimeEnd = dayjs(timeRange[1]).format('YYYY-MM-DD');
      }
      return rest;
    },
    useSearchForm: true,
    actionColumn: { width: 100 },
    showIndexColumn: false,
  });

  // ---------- 不良品入库登记
  const inboundOpen = ref(false);

  function openInbound() {
    inboundOpen.value = true;
  }

  function handleInboundSuccess() {
    reloadRecords();
  }

  function getRecordActions(record: any) {
    // 只有「受托 + 退货处置 + 待处理」才可发起退货；其余由后端状态机兜底
    if (record.sourceType === 'CONSIGNED' && record.disposalType === 'RETURN' && record.status === '待处理') {
      return [
        {
          label: '退货',
          auth: 'wms:defect:return',
          color: 'error',
          onClick: () => handleReturn(record),
        },
      ];
    }
    return [];
  }

  function handleReturn(record: any) {
    let remark = '';
    Modal.confirm({
      title: `受托不良退货（来源单 ${record.sourceBillNo}）？`,
      content: h('div', [
        h('p', '将在金蝶生成受托加工材料退料单；同一来源单的待退记录会聚合到一张退料单。'),
        h(Input, {
          placeholder: '备注（可选，仅本地留存，不写金蝶）',
          maxlength: 200,
          'onUpdate:value': (v: string) => (remark = v),
        }),
      ]),
      okText: '确认退货',
      okType: 'danger',
      cancelText: '取消',
      async onOk() {
        const res: any = await returnToConsignor({
          recordId: record.id,
          remark: remark && remark.trim() ? remark.trim() : undefined,
        });
        const billNo = res?.returnBillNo;
        if (!billNo) {
          // 金蝶已有同来源单据（existsByReference）：后端按已退货处理但不回存单号，不是失败
          createMessage.warning('已退货但 ERP 已有同来源单据，单号待人工回填');
        } else {
          createMessage.success(`退货成功：${billNo}`);
        }
        reloadRecords();
      },
    });
  }

  // ---------- 报废（废品仓台账）
  const scrapOpen = ref(false);
  const submitting = ref(false);
  const current = ref<any>(null);
  const scrapQty = ref<number>(1);
  const scrapReason = ref('');

  function getActions(record: any) {
    return [
      {
        label: '报废',
        auth: 'wms:defect:scrap',
        disabled: Number(record.availableQty) <= 0,
        onClick: () => openScrap(record),
      },
    ];
  }

  function openScrap(record: any) {
    current.value = record;
    scrapQty.value = Number(record.availableQty) || 0;
    scrapReason.value = '';
    scrapOpen.value = true;
  }

  async function submitScrap() {
    if (!scrapReason.value.trim()) {
      createMessage.warning('请填写报废原因');
      return;
    }
    submitting.value = true;
    try {
      const result: any = await scrap({
        inventoryId: current.value.inventoryId,
        quantity: scrapQty.value,
        reason: scrapReason.value.trim(),
      });
      scrapOpen.value = false;
      createMessage.success(`报废成功：${result.outboundNo}，剩余 ${result.remainingQty}`);
      reload();
    } catch (e: any) {
      // defHttp 已统一弹错误提示
    } finally {
      submitting.value = false;
    }
  }
</script>

<style lang="less" scoped>
  .tip {
    color: @text-color-secondary;
  }
</style>
