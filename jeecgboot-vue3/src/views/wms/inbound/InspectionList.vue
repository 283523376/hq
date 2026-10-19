<template>
  <div class="wms-inspection">
    <a-alert
      type="warning"
      show-icon
      banner
      style="margin-bottom: 12px"
      message="该功能已因项目范围调整停用（原材料/焊接线边仓已移出范围），仅保留历史数据查询。"
    />
    <BasicTable @register="registerTable">
      <template #tableTitle>
        <span class="wms-inspection__title">来料检验任务</span>
      </template>
      <template #inspectStatus="{ record }">
        <a-tag :color="statusColor(record.task.inspectStatus)">{{ record.task.inspectStatus }}</a-tag>
      </template>
      <template #action="{ record }">
        <TableAction :actions="getTableAction(record)" />
      </template>
    </BasicTable>
  </div>
</template>

<script lang="ts" name="wms-inspection" setup>
  import { h } from 'vue';
  import { BasicTable, TableAction } from '/@/components/Table';
  import { useListPage } from '/@/hooks/system/useListPage';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { Modal } from 'ant-design-vue';
  import { completeInspection, listInspectionTasks } from './inbound.api';

  const { createMessage } = useMessage();

  const { tableContext } = useListPage({
    tableProps: {
      api: listInspectionTasks,
      columns: [
        { title: '检验任务号', dataIndex: ['task', 'taskNo'], width: 170 },
        { title: '入库单号', dataIndex: 'orderNo', width: 170 },
        { title: '采购订单号', dataIndex: 'purchaseOrderNo', width: 160 },
        { title: '供应商', dataIndex: 'supplierName', width: 160 },
        { title: '检验状态', dataIndex: ['task', 'inspectStatus'], slots: { customRender: 'inspectStatus' }, width: 110 },
        { title: '结果说明', dataIndex: ['task', 'resultRemark'], width: 180 },
        { title: '检验员', dataIndex: ['task', 'inspector'], width: 100 },
        { title: '创建时间', dataIndex: ['task', 'createTime'], width: 170 },
      ],
      rowKey: (record: any) => record.task.id,
      formConfig: {
        schemas: [
          {
            field: 'inspectStatus',
            label: '检验状态',
            component: 'Select',
            componentProps: {
              options: ['待检验', '合格', '不合格'].map((v) => ({ label: v, value: v })),
            },
          },
        ],
      },
      actionColumn: { width: 160 },
    },
  });
  const [registerTable, { reload }] = tableContext;

  function statusColor(status: string) {
    return { 待检验: 'orange', 合格: 'green', 不合格: 'red' }[status] || 'default';
  }

  function getTableAction(record: any) {
    if (record.task.inspectStatus !== '待检验') {
      return [];
    }
    return [
      {
        label: '合格',
        auth: 'wms:inspection:do',
        onClick: () => handleComplete(record, '合格'),
      },
      {
        label: '不合格',
        auth: 'wms:inspection:do',
        color: 'error',
        onClick: () => handleComplete(record, '不合格'),
      },
    ];
  }

  function handleComplete(record: any, result: '合格' | '不合格') {
    if (result === '合格') {
      Modal.confirm({
        title: '确认检验合格？',
        content: `检验合格后，入库单 ${record.orderNo} 将进入「待分配」，由仓管员分配库位。`,
        okText: '确认合格',
        cancelText: '取消',
        onOk: () => submit(record, result, null),
      });
      return;
    }
    let remark = '';
    Modal.confirm({
      title: '登记检验不合格',
      content: h('div', [
        h('p', '不合格将把入库单转入「检验不合格」终态，并按流程转不良品处理（YX-WMS-RK-005）。'),
        h('textarea', {
          class: 'ant-input',
          rows: 3,
          placeholder: '请填写不合格原因（必填）',
          onInput: (e: any) => (remark = e.target.value),
        }),
      ]),
      okText: '确认不合格',
      okType: 'danger',
      cancelText: '取消',
      async onOk() {
        if (!remark || !remark.trim()) {
          createMessage.warning('不合格必须填写原因');
          return Promise.reject();
        }
        await submit(record, result, remark.trim());
      },
    });
  }

  async function submit(record: any, result: string, remark: string | null) {
    await completeInspection(record.task.id, { result, remark });
    createMessage.success(`已登记检验${result}`);
    reload();
  }
</script>

<style lang="less" scoped>
  .wms-inspection {
    padding: 16px;
  }

  .wms-inspection__title {
    font-size: 16px;
    font-weight: 600;
  }
</style>
