import { h } from 'vue';
import { Tag } from 'ant-design-vue';
import { BasicColumn, FormSchema } from '/@/components/Table';
import { listCustomerOptions } from './defect.api';

/** 库存类型选项（后端 `WmsConstants.OWNERSHIP_*`）：默认只查自有，受托需显式切换 */
export const OWNERSHIP_OPTIONS = [
  { label: '自有（OWNED）', value: 'OWNED' },
  { label: '受托（CONSIGNED）', value: 'CONSIGNED' },
];

/** 废品仓台账列 */
export const columns: BasicColumn[] = [
  { title: '物料编码', align: 'center', dataIndex: 'materialCode', width: 200 },
  { title: '物料名称', align: 'center', dataIndex: 'materialName', width: 150 },
  { title: '规格', align: 'center', dataIndex: 'spec', width: 160, ellipsis: true },
  { title: '批次', align: 'center', dataIndex: 'batchNo', width: 130 },
  {
    title: '库存类型',
    align: 'center',
    dataIndex: 'ownershipType',
    width: 100,
    customRender: ({ text }) =>
      h(Tag, { color: text === 'CONSIGNED' ? 'purple' : 'blue' }, () => (text === 'CONSIGNED' ? '受托' : '自有')),
  },
  { title: '受托方', align: 'center', dataIndex: 'consignorName', width: 160 },
  { title: '仓库', align: 'center', dataIndex: 'warehouseName', width: 120 },
  { title: '库位', align: 'center', dataIndex: 'locationCode', width: 130 },
  { title: '当前数量', align: 'center', dataIndex: 'quantity', width: 100 },
  { title: '锁定', align: 'center', dataIndex: 'lockedQty', width: 80 },
  { title: '可报废', align: 'center', dataIndex: 'availableQty', width: 100 },
  { title: '单位', align: 'center', dataIndex: 'unit', width: 70 },
  { title: '首次入库', align: 'center', dataIndex: 'firstInboundTime', width: 160 },
];

// 文本筛选框用 Input 发裸值：JInput 会包 `*xxx*`，而后端（WmsInventoryMapper.xml 的
// LIKE CONCAT('%', #{...}, '%')）不剥星号，星号进 SQL 成字面量导致恒 0 行
// （详见 materialGroupMap.data.ts 说明）。
export const searchFormSchema: FormSchema[] = [
  {
    label: '全局关键词',
    field: 'materialKeyword',
    component: 'Input',
    colProps: { span: 6 },
    componentProps: { placeholder: '物料编码/名称，如 10126', allowClear: true },
  },
  { label: '批次', field: 'batchNo', component: 'Input', colProps: { span: 6 }, componentProps: { placeholder: '任意连续子串', allowClear: true } },
  { label: '库位', field: 'locationCode', component: 'Input', colProps: { span: 6 }, componentProps: { placeholder: '任意连续子串', allowClear: true } },
  // 受托不良入废品仓后仍记 CONSIGNED，不切到「受托」就永远看不到；查询条件只对受托生效
  {
    label: '库存类型',
    field: 'ownershipType',
    component: 'Select',
    colProps: { span: 6 },
    componentProps: { options: OWNERSHIP_OPTIONS, placeholder: '默认自有' },
  },
  {
    label: '受托方',
    field: 'consignorId',
    component: 'ApiSelect',
    colProps: { span: 6 },
    componentProps: {
      api: listCustomerOptions,
      labelField: 'customerName',
      valueField: 'id',
      placeholder: '仅「受托」库存生效',
      showSearch: true,
      optionFilterProp: 'label',
    },
  },
];

/** 不良品来源类型（后端 `WmsConstants.DEFECT_SOURCE_*`） */
export const DEFECT_SOURCE_OPTIONS = [
  { label: '生产（PRODUCTION）', value: 'PRODUCTION' },
  { label: '受托（CONSIGNED）', value: 'CONSIGNED' },
];

/** 处置方式（后端 `WmsConstants.DEFECT_DISPOSAL_*`） */
export const DEFECT_DISPOSAL_OPTIONS = [
  { label: '入废品仓（SCRAP）', value: 'SCRAP' },
  { label: '退货（RETURN）', value: 'RETURN' },
];

/** 不良品记录状态（后端中文常量） */
export const DEFECT_STATUS_OPTIONS = ['待处理', '已处置', '已退货'].map((v) => ({ label: v, value: v }));

const sourceLabel: Record<string, string> = {
  PRODUCTION: '生产',
  CONSIGNED: '受托',
};

const disposalLabel: Record<string, string> = {
  SCRAP: '入废品仓',
  RETURN: '退货',
};

const statusColor: Record<string, string> = {
  待处理: 'orange',
  已处置: 'success',
  已退货: 'blue',
};

/** 不良品记录列（`GET /wms/defect/records` 直接返回实体） */
export const recordColumns: BasicColumn[] = [
  {
    title: '来源类型',
    align: 'center',
    dataIndex: 'sourceType',
    width: 100,
    customRender: ({ text }) => {
      const label = sourceLabel[text] || text;
      return h(Tag, { color: text === 'CONSIGNED' ? 'purple' : 'blue' }, () => label);
    },
  },
  { title: '来源单号', align: 'center', dataIndex: 'sourceBillNo', width: 200 },
  { title: '物料编码', align: 'center', dataIndex: 'materialCode', width: 180 },
  { title: '批次', align: 'center', dataIndex: 'batchNo', width: 150 },
  { title: '数量', align: 'center', dataIndex: 'qty', width: 100 },
  { title: '原因', align: 'center', dataIndex: 'reason', width: 180, ellipsis: true },
  {
    title: '处置方式',
    align: 'center',
    dataIndex: 'disposalType',
    width: 110,
    customRender: ({ text }) => h(Tag, { color: text === 'RETURN' ? 'orange' : 'default' }, () => disposalLabel[text] || text),
  },
  {
    title: '状态',
    align: 'center',
    dataIndex: 'status',
    width: 100,
    customRender: ({ text }) => h(Tag, { color: statusColor[text] || 'default' }, () => text),
  },
  { title: '退货单号', align: 'center', dataIndex: 'returnBillNo', width: 190 },
  { title: '创建时间', align: 'center', dataIndex: 'createTime', width: 170 },
];

/**
 * 不良品记录筛选条件。
 *
 * 只放后端实体里存在的字段 —— 该端点直接绑 `WmsDefectRecord`，
 * 时间字段没有 @DateTimeFormat，传入时间参数会 400。
 */
export const recordSearchFormSchema: FormSchema[] = [
  {
    label: '物料编码',
    field: 'materialCode',
    component: 'Input',
    colProps: { span: 6 },
    componentProps: { placeholder: '支持任意连续子串', allowClear: true },
  },
  {
    label: '来源单号',
    field: 'sourceBillNo',
    component: 'Input',
    colProps: { span: 6 },
    componentProps: { placeholder: '生产入库单号/受托入库单号', allowClear: true },
  },
  { label: '来源类型', field: 'sourceType', component: 'Select', colProps: { span: 6 }, componentProps: { options: DEFECT_SOURCE_OPTIONS } },
  {
    label: '处置方式',
    field: 'disposalType',
    component: 'Select',
    colProps: { span: 6 },
    componentProps: { options: DEFECT_DISPOSAL_OPTIONS },
  },
  { label: '状态', field: 'status', component: 'Select', colProps: { span: 6 }, componentProps: { options: DEFECT_STATUS_OPTIONS } },
];
