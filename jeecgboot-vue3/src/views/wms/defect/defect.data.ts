import { BasicColumn, FormSchema } from '/@/components/Table';

export const columns: BasicColumn[] = [
  { title: '物料编码', align: 'center', dataIndex: 'materialCode', width: 200 },
  { title: '物料名称', align: 'center', dataIndex: 'materialName', width: 150 },
  { title: '规格', align: 'center', dataIndex: 'spec', width: 160, ellipsis: true },
  { title: '批次', align: 'center', dataIndex: 'batchNo', width: 130 },
  { title: '仓库', align: 'center', dataIndex: 'warehouseName', width: 120 },
  { title: '库位', align: 'center', dataIndex: 'locationCode', width: 130 },
  { title: '当前数量', align: 'center', dataIndex: 'quantity', width: 100 },
  { title: '锁定', align: 'center', dataIndex: 'lockedQty', width: 80 },
  { title: '可报废', align: 'center', dataIndex: 'availableQty', width: 100 },
  { title: '单位', align: 'center', dataIndex: 'unit', width: 70 },
  { title: '首次入库', align: 'center', dataIndex: 'firstInboundTime', width: 160 },
];

export const searchFormSchema: FormSchema[] = [
  {
    label: '全局关键词',
    field: 'materialKeyword',
    component: 'JInput',
    colProps: { span: 6 },
    componentProps: { placeholder: '物料编码/名称，如 10126' },
  },
  { label: '批次', field: 'batchNo', component: 'JInput', colProps: { span: 6 } },
  { label: '库位', field: 'locationCode', component: 'JInput', colProps: { span: 6 } },
];
