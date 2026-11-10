import { h } from 'vue';
import { Tag } from 'ant-design-vue';
import { BasicColumn, FormSchema } from '/@/components/Table';

/** 来源类型选项（后端 `WmsConstants.INBOUND_SOURCE_*`） */
export const SOURCE_TYPE_OPTIONS = [
  { label: '外购（PURCHASE）', value: 'PURCHASE' },
  { label: '受托（CONSIGNED）', value: 'CONSIGNED' },
];

/** 处理方式选项（后端 `WmsConstants.INBOUND_HANDLING_*`）
 *  V2.6（2026-11-10）：改刀料与段差棒同口径 —— 均直接入半成品立库，登记后需上架。 */
export const HANDLING_TYPE_OPTIONS = [
  { label: '改刀料（CONVERT）', value: 'CONVERT' },
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
 * 历史改制单据（V2.6 之前）：后端不落库处理方式，只能靠状态特征识别——
 * 「已审核」且「无需同步」，即登记即终态、原材料直送车间、不进 WMS 库存。
 *
 * V2.6（2026-11-10）起改刀料与段差棒同口径（登记→待分配→上架→库存→回写），
 * 新单据无法也不需要在列表里区分两者，列上显示「—」。
 */
export function isLegacyConvertOrder(record: any): boolean {
  return record?.inboundStatus === '已审核' && record?.syncStatus === '无需同步';
}

export function handlingTypeLabel(record: any): string {
  return isLegacyConvertOrder(record) ? '改刀料（历史）' : '—';
}

/** 同步状态颜色（后端 `WmsConstants.SYNC_STATUS_*`） */
const syncStatusColor: Record<string, string> = {
  同步成功: 'success',
  同步失败: 'error',
  待同步: 'orange',
  同步中: 'processing',
  无需同步: 'default',
};

/** 回写失败的单据需要补偿入口：「重试回写」按钮按此判断 */
export function isSyncFailed(record: any): boolean {
  return record?.syncStatus === '同步失败';
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
      h('span', { style: isLegacyConvertOrder(record) ? { color: '#d46b08', fontWeight: 600 } : {} }, handlingTypeLabel(record)),
  },
  {
    title: '状态',
    align: 'center',
    dataIndex: 'inboundStatus',
    width: 110,
    customRender: ({ text }) => h(Tag, { color: statusColor[text] || 'default' }, () => text),
  },
  {
    title: '同步状态',
    align: 'center',
    dataIndex: 'syncStatus',
    width: 110,
    customRender: ({ text }) => {
      if (!text) {
        return h('span', '-');
      }
      return h(Tag, { color: syncStatusColor[text] || 'default' }, () => text);
    },
  },
  // 「ERP 订单号」= 到货登记时校验的金蝶订单；「ERP 单号」= 本次回写生成的单据
  { title: 'ERP 订单号', align: 'center', dataIndex: 'purchaseOrderNo', width: 180 },
  { title: 'ERP 单号', align: 'center', dataIndex: 'erpBillNo', width: 190 },
  { title: '同步失败原因', align: 'left', dataIndex: 'lastSyncError', width: 220, ellipsis: true },
  {
    title: '供应商 / 受托方',
    align: 'center',
    dataIndex: 'supplierName',
    width: 180,
    // 外购看 supplier_name，受托看 consignor_name（V2.4：受托方是客户档案）
    customRender: ({ record }) => record?.consignorName || record?.supplierName || '—',
  },
  { title: '创建时间', align: 'center', dataIndex: 'createTime', width: 170 },
];

// 文本筛选框用 Input 发裸值：JInput 会包 `*xxx*`，而 WmsRawInboundServiceImpl 手写 like 不剥星号，
// 星号进 SQL 成字面量导致恒 0 行（详见 materialGroupMap.data.ts 说明）。
export const searchFormSchema: FormSchema[] = [
  {
    label: '入库单号',
    field: 'orderNo',
    component: 'Input',
    colProps: { span: 6 },
    componentProps: { placeholder: '入库单号/ERP 单号/ERP 订单号', allowClear: true },
  },
  {
    label: '供应商/受托方',
    field: 'supplierName',
    component: 'Input',
    colProps: { span: 6 },
    componentProps: { placeholder: '供应商或受托方名称，模糊匹配', allowClear: true },
  },
  { label: '状态', field: 'inboundStatus', component: 'Select', colProps: { span: 6 }, componentProps: { options: INBOUND_STATUS_OPTIONS } },
];
