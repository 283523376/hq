import { h } from 'vue';
import { Tag } from 'ant-design-vue';
import { BasicColumn, FormSchema } from '/@/components/Table';

export const PRODUCT_TYPE_OPTIONS = [
  { label: '半成品（立库 / 堆垛机 / 周转盘 RFID）', value: '半成品' },
  { label: '成品（敏捷仓 / SCR / 料箱二维码）', value: '成品' },
];

export const WORK_ORDER_STATUS_OPTIONS = ['待生产', '生产中', '已完成', '已关闭'].map((v) => ({
  label: v,
  value: v,
}));

const statusColor: Record<string, string> = {
  待生产: 'default',
  生产中: 'processing',
  已完成: 'success',
  已关闭: 'default',
};

export const columns: BasicColumn[] = [
  { title: '工单号', align: 'center', dataIndex: 'workOrderNo', width: 160 },
  { title: '物料编码', align: 'center', dataIndex: 'materialCode', width: 130 },
  { title: '物料名称', align: 'center', dataIndex: 'materialName', width: 160 },
  { title: '产品类型', align: 'center', dataIndex: 'productType', width: 90 },
  { title: '计划数量', align: 'center', dataIndex: 'planQty', width: 100 },
  { title: '批次号', align: 'center', dataIndex: 'batchNo', width: 130 },
  {
    title: '工单状态',
    align: 'center',
    dataIndex: 'workOrderStatus',
    width: 110,
    customRender: ({ text }) => h(Tag, { color: statusColor[text] || 'default' }, () => text),
  },
  { title: '来源', align: 'center', dataIndex: 'source', width: 90 },
  { title: '创建时间', align: 'center', dataIndex: 'createTime', width: 170 },
];

export const searchFormSchema: FormSchema[] = [
  { label: '工单号', field: 'workOrderNo', component: 'Input', colProps: { span: 8 } },
  {
    label: '产品类型',
    field: 'productType',
    component: 'Select',
    componentProps: { options: PRODUCT_TYPE_OPTIONS, allowClear: true },
    colProps: { span: 8 },
  },
  {
    label: '工单状态',
    field: 'workOrderStatus',
    component: 'Select',
    componentProps: { options: WORK_ORDER_STATUS_OPTIONS, allowClear: true },
    colProps: { span: 8 },
  },
];

export const formSchema: FormSchema[] = [
  { label: '', field: 'id', component: 'Input', show: false },
  {
    label: '工单号',
    field: 'workOrderNo',
    component: 'Input',
    required: true,
    dynamicDisabled: ({ values }) => !!values.id,
  },
  { label: '物料编码', field: 'materialCode', component: 'Input', required: true },
  {
    label: '产品类型',
    field: 'productType',
    component: 'Select',
    required: true,
    componentProps: { options: PRODUCT_TYPE_OPTIONS, placeholder: '请选择产品类型' },
  },
  {
    label: '计划数量',
    field: 'planQty',
    component: 'InputNumber',
    required: true,
    componentProps: { min: 0, precision: 3, style: { width: '100%' } },
  },
  { label: '批次号', field: 'batchNo', component: 'Input', required: true },
  {
    label: '工单状态',
    field: 'workOrderStatus',
    component: 'Select',
    componentProps: { options: WORK_ORDER_STATUS_OPTIONS, placeholder: '请选择工单状态' },
  },
  { label: '备注', field: 'remark', component: 'InputTextArea' },
];
