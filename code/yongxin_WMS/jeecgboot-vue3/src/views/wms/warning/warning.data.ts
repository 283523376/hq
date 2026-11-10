import { BasicColumn, FormSchema } from '/@/components/Table';
import { h } from 'vue';
import { Tag } from 'ant-design-vue';

/** 规则类型 */
export const RULE_TYPE_OPTIONS = [
  { label: '库存下限', value: 'LOW_STOCK' },
  { label: '存放超期', value: 'OVERDUE' },
];

/** 作用域 */
export const SCOPE_TYPE_OPTIONS = [
  { label: '物料', value: 'MATERIAL' },
  { label: '物料分类', value: 'CATEGORY' },
  { label: '全部', value: 'ALL' },
];

/** 处理方式 */
export const HANDLE_TYPE_OPTIONS = [
  { label: '补料', value: 'REPLENISH' },
  { label: '清理', value: 'CLEAR' },
  { label: '其他', value: 'OTHER' },
];

export const STATUS_OPTIONS = [
  { label: '待处理', value: 'PENDING' },
  { label: '已解除', value: 'RESOLVED' },
];

export const RULE_TYPE_LABEL: Record<string, string> = {
  LOW_STOCK: '库存下限',
  OVERDUE: '存放超期',
};

export const STATUS_LABEL: Record<string, string> = {
  PENDING: '待处理',
  RESOLVED: '已解除',
};

export const STATUS_COLOR: Record<string, string> = {
  PENDING: 'error',
  RESOLVED: 'success',
};

export const SCOPE_LABEL: Record<string, string> = {
  MATERIAL: '物料',
  CATEGORY: '分类',
  ALL: '全部',
};

export const HANDLE_LABEL: Record<string, string> = {
  REPLENISH: '补料',
  CLEAR: '清理',
  OTHER: '其他',
};

export const warningColumns: BasicColumn[] = [
  { title: '预警单号', align: 'center', dataIndex: 'warningNo', width: 200 },
  {
    title: '类型',
    align: 'center',
    dataIndex: 'ruleType',
    width: 100,
    customRender: ({ text }) => RULE_TYPE_LABEL[text] || text,
  },
  {
    title: '状态',
    align: 'center',
    dataIndex: 'status',
    width: 90,
    customRender: ({ text }) =>
      h(Tag, { color: STATUS_COLOR[text] || 'default' }, () => STATUS_LABEL[text] || text),
  },
  { title: '物料编码', align: 'center', dataIndex: 'materialCode', width: 130 },
  { title: '物料名称', align: 'center', dataIndex: 'materialName', width: 160 },
  { title: '库位', align: 'center', dataIndex: 'locationCode', width: 130 },
  { title: '批次', align: 'center', dataIndex: 'batchNo', width: 120 },
  { title: '容器', align: 'center', dataIndex: 'containerCode', width: 110 },
  { title: '当前数量', align: 'center', dataIndex: 'currentQty', width: 100 },
  { title: '阈值', align: 'center', dataIndex: 'thresholdQty', width: 90 },
  { title: '超期天数', align: 'center', dataIndex: 'overdueDays', width: 90 },
  { title: '命中次数', align: 'center', dataIndex: 'hitCount', width: 90 },
  { title: '最近命中', align: 'center', dataIndex: 'lastHitTime', width: 160 },
  { title: '处理方式', align: 'center', dataIndex: 'handleType', width: 90, customRender: ({ text }) => (text ? HANDLE_LABEL[text] || text : '') },
  { title: '处理说明', align: 'center', dataIndex: 'handleRemark', width: 180 },
  { title: '处理人', align: 'center', dataIndex: 'handledBy', width: 90 },
  { title: '处理时间', align: 'center', dataIndex: 'handleTime', width: 160 },
];

export const warningSearchSchema: FormSchema[] = [
  {
    label: '状态',
    field: 'status',
    component: 'Select',
    componentProps: { options: STATUS_OPTIONS, allowClear: true },
    colProps: { span: 5 },
  },
  {
    label: '类型',
    field: 'ruleType',
    component: 'Select',
    componentProps: { options: RULE_TYPE_OPTIONS, allowClear: true },
    colProps: { span: 5 },
  },
  { label: '物料编码', field: 'materialCode', component: 'Input', colProps: { span: 5 } },
  { label: '批次号', field: 'batchNo', component: 'Input', colProps: { span: 5 } },
];

export const ruleColumns: BasicColumn[] = [
  { title: '规则名称', align: 'center', dataIndex: 'ruleName', width: 180 },
  {
    title: '类型',
    align: 'center',
    dataIndex: 'ruleType',
    width: 100,
    customRender: ({ text }) => RULE_TYPE_LABEL[text] || text,
  },
  {
    title: '作用域',
    align: 'center',
    dataIndex: 'scopeType',
    width: 100,
    customRender: ({ text }) => SCOPE_LABEL[text] || text,
  },
  { title: '物料/分类', align: 'center', dataIndex: 'materialName', width: 180 },
  { title: '限定库区', align: 'center', dataIndex: 'warehouseName', width: 140 },
  { title: '下限数量', align: 'center', dataIndex: 'thresholdQty', width: 100 },
  { title: '超期天数', align: 'center', dataIndex: 'overdueDays', width: 100 },
  {
    title: '启用',
    align: 'center',
    dataIndex: 'enabled',
    width: 80,
    customRender: ({ text }) => (Number(text) === 1 ? '是' : '否'),
  },
  { title: '备注', align: 'center', dataIndex: 'remark', width: 180 },
  { title: '更新时间', align: 'center', dataIndex: 'updateTime', width: 160 },
];

export const ruleSearchSchema: FormSchema[] = [
  {
    label: '类型',
    field: 'ruleType',
    component: 'Select',
    componentProps: { options: RULE_TYPE_OPTIONS, allowClear: true },
    colProps: { span: 6 },
  },
  { label: '规则名称', field: 'keyword', component: 'Input', colProps: { span: 6 } },
];
