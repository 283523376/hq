import { BasicColumn } from '/@/components/Table';
import { h } from 'vue';
import { Tag } from 'ant-design-vue';

/** 流转事件类型 */
export const EVENT_TYPE_LABEL: Record<string, string> = {
  INBOUND: '入库',
  PROD_INBOUND: '生产入库',
  OUTBOUND: '出库',
  TRANSFER: '调拨',
  STOCKTAKE: '盘点',
};

export const EVENT_TYPE_COLOR: Record<string, string> = {
  INBOUND: 'blue',
  PROD_INBOUND: 'cyan',
  OUTBOUND: 'orange',
  TRANSFER: 'purple',
  STOCKTAKE: 'gold',
};

export const stockColumns: BasicColumn[] = [
  { title: '物料编码', align: 'center', dataIndex: 'materialCode', width: 130 },
  { title: '物料名称', align: 'center', dataIndex: 'materialName', width: 160 },
  { title: '库区', align: 'center', dataIndex: 'warehouseName', width: 140 },
  { title: '库位', align: 'center', dataIndex: 'locationCode', width: 140 },
  { title: '容器', align: 'center', dataIndex: 'containerCode', width: 120 },
  { title: '库存数量', align: 'center', dataIndex: 'quantity', width: 100 },
  { title: '锁定', align: 'center', dataIndex: 'lockedQty', width: 90 },
  { title: '可用', align: 'center', dataIndex: 'availableQty', width: 90 },
  { title: '首次入库', align: 'center', dataIndex: 'firstInboundTime', width: 160 },
];

export const flowColumns: BasicColumn[] = [
  {
    title: '时间',
    align: 'center',
    dataIndex: 'eventTime',
    width: 160,
  },
  {
    title: '事件',
    align: 'center',
    dataIndex: 'eventType',
    width: 100,
    customRender: ({ text }) =>
      h(Tag, { color: EVENT_TYPE_COLOR[text] || 'default' }, () => EVENT_TYPE_LABEL[text] || text),
  },
  { title: '单据号', align: 'center', dataIndex: 'billNo', width: 180 },
  { title: '物料编码', align: 'center', dataIndex: 'materialCode', width: 130 },
  { title: '物料名称', align: 'center', dataIndex: 'materialName', width: 160 },
  { title: '库位', align: 'center', dataIndex: 'locationCode', width: 130 },
  { title: '容器', align: 'center', dataIndex: 'containerCode', width: 110 },
  {
    title: '数量',
    align: 'center',
    dataIndex: 'qty',
    width: 100,
    customRender: ({ text }) =>
      h('span', { style: { color: Number(text) < 0 ? '#cf1322' : '#389e0d' } }, text ?? ''),
  },
  { title: '往来方', align: 'center', dataIndex: 'counterparty', width: 200 },
  { title: '操作人', align: 'center', dataIndex: 'operator', width: 100 },
  { title: '备注', align: 'center', dataIndex: 'remark', width: 180 },
];
