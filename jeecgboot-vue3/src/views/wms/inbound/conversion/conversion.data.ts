import { h } from 'vue';
import { Tag } from 'ant-design-vue';
import { BasicColumn, FormSchema } from '/@/components/Table';

/** 改制单状态（后端 `WmsConstants.CONVERSION_STATUS_*`） */
export const CONVERSION_STATUS_OPTIONS = [
  { label: '草稿', value: 'DRAFT' },
  { label: '执行中', value: 'RUNNING' },
  { label: '已完成', value: 'DONE' },
  { label: '已取消', value: 'CANCELLED' },
];

const statusColor: Record<string, string> = {
  DRAFT: 'orange',
  RUNNING: 'processing',
  DONE: 'success',
  CANCELLED: 'default',
};

const statusLabel: Record<string, string> = {
  DRAFT: '草稿',
  RUNNING: '执行中',
  DONE: '已完成',
  CANCELLED: '已取消',
};

export function conversionStatusLabel(status: string): string {
  return statusLabel[status] || status;
}

export const columns: BasicColumn[] = [
  { title: '改制单号', align: 'center', dataIndex: 'conversionNo', width: 200 },
  { title: '生产订单号', align: 'center', dataIndex: 'productionOrderNo', width: 170 },
  { title: '投入物料', align: 'center', dataIndex: 'inputMaterialCode', width: 140 },
  { title: '投入批次', align: 'center', dataIndex: 'inputBatchNo', width: 130 },
  { title: '投入数量', align: 'center', dataIndex: 'inputQty', width: 100 },
  { title: '产出物料', align: 'center', dataIndex: 'outputMaterialCode', width: 140 },
  { title: '产出批次', align: 'center', dataIndex: 'outputBatchNo', width: 130 },
  { title: '产出数量', align: 'center', dataIndex: 'outputQty', width: 100 },
  {
    title: '状态',
    align: 'center',
    dataIndex: 'status',
    width: 100,
    customRender: ({ text }) => h(Tag, { color: statusColor[text] || 'default' }, () => conversionStatusLabel(text)),
  },
  { title: '操作人', align: 'center', dataIndex: 'operator', width: 110 },
  { title: 'ERP 单号', align: 'center', dataIndex: 'erpBillNo', width: 180 },
  { title: '创建时间', align: 'center', dataIndex: 'createTime', width: 170 },
];

export const searchFormSchema: FormSchema[] = [
  {
    label: '改制单号',
    field: 'conversionNo',
    component: 'JInput',
    colProps: { span: 6 },
    componentProps: { placeholder: '支持任意连续子串' },
  },
  {
    label: '生产订单号',
    field: 'productionOrderNo',
    component: 'JInput',
    colProps: { span: 6 },
    componentProps: { placeholder: 'ERP 生产订单号' },
  },
  { label: '状态', field: 'status', component: 'Select', colProps: { span: 6 }, componentProps: { options: CONVERSION_STATUS_OPTIONS } },
];
