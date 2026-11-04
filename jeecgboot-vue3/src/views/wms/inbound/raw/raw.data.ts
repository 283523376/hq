import { h } from 'vue';
import { Tag } from 'ant-design-vue';
import { BasicColumn, FormSchema } from '/@/components/Table';

/** 来源类型选项（后端 `WmsConstants.INBOUND_SOURCE_*`） */
export const SOURCE_TYPE_OPTIONS = [
  { label: '外购（PURCHASE）', value: 'PURCHASE' },
  { label: '受托（CONSIGNED）', value: 'CONSIGNED' },
];

/** 处理方式选项（后端 `WmsConstants.INBOUND_HANDLING_*`） */
export const HANDLING_TYPE_OPTIONS = [
  { label: '大改小（CONVERT）', value: 'CONVERT' },
  { label: '段差棒直入（DIRECT）', value: 'DIRECT' },
];

/** 入库单状态选项（与后端中文常量一致） */
export const INBOUND_STATUS_OPTIONS = ['待检验', '待分配', '待上架', '已入库', '已审核', '已拒收', '检验不合格', '作废'].map((v) => ({
  label: v,
  value: v,
}));

const statusColor: Record<string, string> = {
  待检验: 'orange',
  待分配: 'orange',
  待上架: 'blue',
  已入库: 'processing',
  已审核: 'success',
  已拒收: 'error',
  检验不合格: 'error',
  作废: 'default',
};

/** 来源类型：优先取 sourceType，历史采购入库单没有该列时按 inboundType 兜底 */
export function resolveSourceType(record: any): string {
  const sourceType = record?.sourceType;
  if (sourceType === 'CONSIGNED') {
    return 'CONSIGNED';
  }
  if (sourceType === 'PURCHASE') {
    return 'PURCHASE';
  }
  if (record?.inboundType === '受托原材料入库') {
    return 'CONSIGNED';
  }
  if (record?.inboundType === '外购原材料入库') {
    return 'PURCHASE';
  }
  return '';
}

export function sourceTypeLabel(record: any): string {
  const type = resolveSourceType(record);
  if (type === 'CONSIGNED') {
    return '受托';
  }
  if (type === 'PURCHASE') {
    return '外购';
  }
  return '—';
}

/**
 * 处理方式（后端不落库，用状态区分，见 `WmsRawInboundServiceImpl` 类注释）。
 *
 * CONVERT 到货登记即终态：状态「已审核」且同步状态「无需同步」，没有上架动作；
 * 其余（待分配 / 上架后的已审核+待同步等）都是 DIRECT。
 */
export function isConvertOrder(record: any): boolean {
  return record?.inboundStatus === '已审核' && record?.syncStatus === '无需同步';
}

export function handlingTypeLabel(record: any): string {
  return isConvertOrder(record) ? '大改小' : '段差棒直入';
}

export const columns: BasicColumn[] = [
  { title: '入库单号', align: 'center', dataIndex: 'orderNo', width: 200 },
  {
    title: '来源类型',
    align: 'center',
    dataIndex: 'sourceType',
    width: 100,
    customRender: ({ record }) => {
      const label = sourceTypeLabel(record);
      const color = resolveSourceType(record) === 'CONSIGNED' ? 'purple' : 'blue';
      return label === '—' ? h('span', label) : h(Tag, { color }, () => label);
    },
  },
  {
    title: '处理方式',
    align: 'center',
    dataIndex: 'handlingType',
    width: 130,
    customRender: ({ record }) =>
      h('span', { style: isConvertOrder(record) ? { color: '#d46b08', fontWeight: 600 } : {} }, handlingTypeLabel(record)),
  },
  {
    title: '状态',
    align: 'center',
    dataIndex: 'inboundStatus',
    width: 110,
    customRender: ({ text }) => h(Tag, { color: statusColor[text] || 'default' }, () => text),
  },
  { title: 'ERP 单号', align: 'center', dataIndex: 'purchaseOrderNo', width: 180 },
  { title: '供应商 / 受托方', align: 'center', dataIndex: 'supplierName', width: 180 },
  { title: '创建时间', align: 'center', dataIndex: 'createTime', width: 170 },
];

export const searchFormSchema: FormSchema[] = [
  {
    label: '入库单号',
    field: 'orderNo',
    component: 'JInput',
    colProps: { span: 6 },
    componentProps: { placeholder: '支持任意连续子串' },
  },
  {
    label: '供应商/受托方',
    field: 'supplierName',
    component: 'JInput',
    colProps: { span: 6 },
    componentProps: { placeholder: '按名称模糊匹配' },
  },
  { label: '状态', field: 'inboundStatus', component: 'Select', colProps: { span: 6 }, componentProps: { options: INBOUND_STATUS_OPTIONS } },
];
