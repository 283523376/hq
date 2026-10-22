import { Tag } from 'ant-design-vue';
import { h } from 'vue';
import { BasicColumn, FormSchema } from '/@/components/Table';

/** 出库类型选项 */
export const OUTBOUND_TYPE_OPTIONS = ['生产领料', '销售出库'].map((v) => ({ label: v, value: v }));

/** 出库单状态选项 */
export const OUTBOUND_STATUS_OPTIONS = [
  '待分配',
  '已分配',
  '执行中',
  '待复核',
  '复核异常',
  '异常待人工',
  '已完成',
  '已关闭',
].map((v) => ({ label: v, value: v }));

/** 同步状态选项 */
export const OUTBOUND_SYNC_STATUS_OPTIONS = ['无需同步', '待同步', '同步中', '同步成功', '同步失败'].map((v) => ({
  label: v,
  value: v,
}));

/** 复核状态选项 */
export const REVIEW_STATUS_OPTIONS = ['待复核', '一致', '不一致', '已放行'].map((v) => ({ label: v, value: v }));

const outboundStatusColor: Record<string, string> = {
  待分配: 'default',
  已分配: 'blue',
  执行中: 'processing',
  待复核: 'orange',
  复核异常: 'error',
  异常待人工: 'error',
  已完成: 'success',
  已关闭: 'default',
};

const syncStatusColor: Record<string, string> = {
  无需同步: 'default',
  待同步: 'orange',
  同步中: 'processing',
  同步成功: 'success',
  同步失败: 'error',
};

export const columns: BasicColumn[] = [
  { title: '出库单号', align: 'center', dataIndex: 'outboundNo', width: 200 },
  { title: '出库类型', align: 'center', dataIndex: 'outboundType', width: 100 },
  { title: '工单号', align: 'center', dataIndex: 'workOrderNo', width: 140 },
  { title: '销售订单号', align: 'center', dataIndex: 'salesOrderNo', width: 150 },
  { title: '客户', align: 'center', dataIndex: 'customerName', width: 150 },
  {
    title: '状态',
    align: 'center',
    dataIndex: 'status',
    width: 110,
    customRender: ({ text }) => h(Tag, { color: outboundStatusColor[text] || 'default' }, () => text),
  },
  {
    title: '同步状态',
    align: 'center',
    dataIndex: 'syncStatus',
    width: 110,
    customRender: ({ text }) => h(Tag, { color: syncStatusColor[text] || 'default' }, () => text),
  },
  { title: '创建时间', align: 'center', dataIndex: 'createTime', width: 170 },
];

export const searchFormSchema: FormSchema[] = [
  { field: 'keyword', label: '单号', component: 'Input', colProps: { span: 6 } },
  {
    field: 'outboundType',
    label: '出库类型',
    component: 'Select',
    componentProps: { options: OUTBOUND_TYPE_OPTIONS },
    colProps: { span: 6 },
  },
  {
    field: 'status',
    label: '状态',
    component: 'Select',
    componentProps: { options: OUTBOUND_STATUS_OPTIONS },
    colProps: { span: 6 },
  },
];
