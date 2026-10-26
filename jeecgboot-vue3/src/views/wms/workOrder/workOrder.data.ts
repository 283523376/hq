import { h } from 'vue';
import { Tag } from 'ant-design-vue';
import { BasicColumn, FormSchema } from '/@/components/Table';

export const PRODUCT_TYPE_OPTIONS = [
  { label: '半成品（立库 / 堆垛机 / 周转盘 RFID）', value: '半成品' },
  { label: '成品（敏捷仓 / SCR / 料箱二维码）', value: '成品' },
];

export const WORK_ORDER_STATUS_OPTIONS = ['待生产', '已下达', '生产中', '已完成', '已关闭'].map((v) => ({
  label: v,
  value: v,
}));

export const WORK_ORDER_SOURCE_OPTIONS = ['ERP', 'MES', '手工'].map((v) => ({ label: v, value: v }));

export const SYNC_STATUS_OPTIONS = ['待同步', '同步中', '同步成功', '同步失败', '无需同步'].map((v) => ({
  label: v,
  value: v,
}));

const statusColor: Record<string, string> = {
  待生产: 'default',
  已下达: 'blue',
  生产中: 'processing',
  已完成: 'success',
  已关闭: 'default',
};

const syncStatusColor: Record<string, string> = {
  同步成功: 'success',
  同步失败: 'error',
  同步中: 'processing',
};

export const columns: BasicColumn[] = [
  { title: '工单号', align: 'center', dataIndex: 'workOrderNo', width: 150 },
  { title: '物料编码', align: 'center', dataIndex: 'materialCode', width: 130 },
  { title: '物料名称', align: 'center', dataIndex: 'materialName', width: 150 },
  { title: '产品类型', align: 'center', dataIndex: 'productType', width: 90 },
  {
    title: '工单类型',
    align: 'center',
    dataIndex: 'orderType',
    width: 100,
    // 金蝶侧类型字段未启用（外部依赖 E1）时 order_type 为空，展示层兜底为「客户订单」
    customRender: ({ text }) => text || '客户订单',
  },
  { title: '计划数量', align: 'center', dataIndex: 'planQty', width: 90 },
  { title: '批次号', align: 'center', dataIndex: 'batchNo', width: 120 },
  {
    title: '工单状态',
    align: 'center',
    dataIndex: 'workOrderStatus',
    width: 100,
    customRender: ({ text }) => h(Tag, { color: statusColor[text] || 'default' }, () => text),
  },
  { title: '来源', align: 'center', dataIndex: 'source', width: 70 },
  {
    title: '同步状态',
    align: 'center',
    dataIndex: 'syncStatus',
    width: 100,
    customRender: ({ text }) => h(Tag, { color: syncStatusColor[text] || 'default' }, () => text),
  },
  { title: '最近同步时间', align: 'center', dataIndex: 'lastSyncTime', width: 160 },
  { title: '创建时间', align: 'center', dataIndex: 'createTime', width: 160 },
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
  {
    label: '来源',
    field: 'source',
    component: 'Select',
    componentProps: { options: WORK_ORDER_SOURCE_OPTIONS, allowClear: true },
    colProps: { span: 8 },
  },
  {
    label: '同步状态',
    field: 'syncStatus',
    component: 'Select',
    componentProps: { options: SYNC_STATUS_OPTIONS, allowClear: true },
    colProps: { span: 8 },
  },
];
