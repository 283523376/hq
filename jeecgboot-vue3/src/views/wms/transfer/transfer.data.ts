import { Tag } from 'ant-design-vue';
import { h } from 'vue';
import { BasicColumn, FormSchema } from '/@/components/Table';

/** 单据总状态（后端派生，前端只读） */
export const TRANSFER_STATUS_OPTIONS = ['待执行', '执行中', '待ERP审核', '已完成', '异常', '已关闭'].map((v) => ({
  label: v,
  value: v,
}));

/** 调拨模式 */
export const TRANSFER_MODE_OPTIONS = ['跨仓', '同仓区域'].map((v) => ({ label: v, value: v }));

/** ERP 时钟状态（含 Phase A 只到「待同步」） */
export const TRANSFER_SYNC_STATUS_OPTIONS = ['待同步', '同步中', '已提交待审核', '已审核', 'ERP驳回', '同步失败', '无需同步'].map((v) => ({
  label: v,
  value: v,
}));

const statusColor: Record<string, string> = {
  待执行: 'default',
  执行中: 'processing',
  待ERP审核: 'orange',
  已完成: 'success',
  异常: 'error',
  已关闭: 'default',
};

const syncStatusColor: Record<string, string> = {
  待同步: 'default',
  同步中: 'processing',
  已提交待审核: 'orange',
  已审核: 'success',
  ERP驳回: 'error',
  同步失败: 'error',
  无需同步: 'default',
};

const erpStatusColor: Record<string, string> = {
  A: 'default',
  B: 'orange',
  C: 'success',
  D: 'error',
};

export const columns: BasicColumn[] = [
  { title: '调拨单号', align: 'center', dataIndex: 'transferNo', width: 200 },
  { title: '模式', align: 'center', dataIndex: 'transferMode', width: 100 },
  { title: '调出库区', align: 'center', dataIndex: 'outWarehouseCode', width: 110 },
  { title: '调入库区', align: 'center', dataIndex: 'inWarehouseCode', width: 110 },
  {
    title: '状态',
    align: 'center',
    dataIndex: 'status',
    width: 120,
    customRender: ({ text }) => h(Tag, { color: statusColor[text] || 'default' }, () => text),
  },
  {
    title: 'ERP 状态',
    align: 'center',
    dataIndex: 'syncStatus',
    width: 130,
    customRender: ({ text, record }) => {
      const erp = record?.erpDocumentStatus ? ` (${record.erpDocumentStatus})` : '';
      return h(Tag, { color: syncStatusColor[text] || 'default' }, () => `${text || '-'}${erp}`);
    },
  },
  {
    title: '金蝶原始状态',
    align: 'center',
    dataIndex: 'erpDocumentStatus',
    width: 120,
    customRender: ({ text }) =>
      text ? h(Tag, { color: erpStatusColor[text] || 'default' }, () => text) : h('span', '-'),
  },
  { title: '实物完成时间', align: 'center', dataIndex: 'execFinishTime', width: 170 },
  { title: '审核通过时间', align: 'center', dataIndex: 'erpApproveTime', width: 170 },
  { title: '创建时间', align: 'center', dataIndex: 'createTime', width: 170 },
];

export const searchFormSchema: FormSchema[] = [
  {
    label: '关键字',
    field: 'keyword',
    component: 'Input',
    colProps: { span: 6 },
    componentProps: { placeholder: '单号 / 金蝶单号 / 库区编码' },
  },
  {
    label: '状态',
    field: 'status',
    component: 'Select',
    colProps: { span: 5 },
    componentProps: { options: TRANSFER_STATUS_OPTIONS, allowClear: true },
  },
  {
    label: '模式',
    field: 'transferMode',
    component: 'Select',
    colProps: { span: 5 },
    componentProps: { options: TRANSFER_MODE_OPTIONS, allowClear: true },
  },
];
