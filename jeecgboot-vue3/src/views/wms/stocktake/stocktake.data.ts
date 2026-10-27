import { BasicColumn, FormSchema } from '/@/components/Table';
import { h } from 'vue';
import { Tag } from 'ant-design-vue';

/** 盘点状态配色 */
export const STATUS_COLOR: Record<string, string> = {
  草稿: 'default',
  盘点中: 'processing',
  差异确认: 'warning',
  复核中: 'error',
  已调整: 'success',
  已归档: 'default',
};

export const STOCKTAKE_TYPE_OPTIONS = ['定期', '周期'].map((v) => ({ label: v, value: v }));

export const columns: BasicColumn[] = [
  { title: '盘点单号', align: 'center', dataIndex: 'stocktakeNo', width: 170 },
  { title: '类型', align: 'center', dataIndex: 'stocktakeType', width: 80 },
  { title: '状态', align: 'center', dataIndex: 'status', width: 100 },
  { title: '账面合计', align: 'center', dataIndex: 'bookTotal', width: 100 },
  { title: '盘盈', align: 'center', dataIndex: 'gainTotal', width: 90 },
  { title: '盘亏', align: 'center', dataIndex: 'lossTotal', width: 90 },
  { title: '阈值%', align: 'center', dataIndex: 'diffReviewThreshold', width: 80 },
  { title: '发起人', align: 'center', dataIndex: 'initiator', width: 90 },
  { title: '复核人', align: 'center', dataIndex: 'reviewer', width: 90 },
  { title: 'ERP回写', align: 'center', dataIndex: 'erpSyncStatus', width: 100 },
  { title: '创建时间', align: 'center', dataIndex: 'createTime', width: 160 },
];

export const searchFormSchema: FormSchema[] = [
  { label: '盘点单号', field: 'stocktakeNo', component: 'Input', colProps: { span: 6 } },
  {
    label: '类型',
    field: 'stocktakeType',
    component: 'Select',
    componentProps: { options: STOCKTAKE_TYPE_OPTIONS },
    colProps: { span: 6 },
  },
];

export const detailColumns: BasicColumn[] = [
  { title: '库位', align: 'center', dataIndex: 'locationCode', width: 120 },
  { title: '物料编码', align: 'center', dataIndex: 'materialCode', width: 130 },
  { title: '批次', align: 'center', dataIndex: 'batchNo', width: 110 },
  { title: '容器', align: 'center', dataIndex: 'containerCode', width: 110 },
  { title: '账面', align: 'center', dataIndex: 'bookQty', width: 90 },
  { title: '实盘', align: 'center', dataIndex: 'actualQty', width: 90 },
  { title: '差异', align: 'center', dataIndex: 'diffQty', width: 90 },
  { title: '差异原因', align: 'center', dataIndex: 'diffReason', width: 140 },
  {
    title: '需重盘',
    align: 'center',
    dataIndex: 'recheckFlag',
    width: 80,
    customRender: ({ text }) =>
      h(Tag, { color: text === '1' ? 'error' : 'default' }, () => (text === '1' ? '是' : '否')),
  },
];

export function statusTag(text: string) {
  return h(Tag, { color: STATUS_COLOR[text] || 'default' }, () => text);
}
