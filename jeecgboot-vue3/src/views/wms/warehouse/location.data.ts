import { h } from 'vue';
import { Tag } from 'ant-design-vue';
import { BasicColumn, FormSchema } from '/@/components/Table';

export const LOCATION_STATUS_OPTIONS = [
  { label: '空闲', value: '空闲' },
  { label: '占用', value: '占用' },
  { label: '故障', value: '故障' },
];

/** 状态色：空闲(绿) / 占用(蓝) / 故障(红)。只有这一处定义。 */
export const LOCATION_STATUS_COLOR: Record<string, string> = {
  空闲: 'green',
  占用: 'blue',
  故障: 'red',
};

export const columns: BasicColumn[] = [
  { title: '库位编码', align: 'center', dataIndex: 'locationCode', width: 180 },
  { title: '库区', align: 'center', dataIndex: 'warehouseName', width: 140 },
  { title: '排', align: 'center', dataIndex: 'rowNo', width: 60 },
  { title: '列', align: 'center', dataIndex: 'colNo', width: 70 },
  { title: '层', align: 'center', dataIndex: 'layerNo', width: 60 },
  { title: '承重', align: 'center', dataIndex: 'maxWeight', width: 100 },
  {
    title: '状态',
    align: 'center',
    dataIndex: 'status',
    width: 90,
    customRender: ({ text }) => h(Tag, { color: LOCATION_STATUS_COLOR[text] || 'default' }, () => text),
  },
  { title: '创建时间', align: 'center', dataIndex: 'createTime', width: 170 },
];

// 文本筛选框用 Input 发裸值：JInput 会包 `*xxx*`，而 WmsLocationController 手写 like 不剥星号，
// 星号进 SQL 成字面量导致恒 0 行（详见 materialGroupMap.data.ts 说明）。
export const searchFormSchema: FormSchema[] = [
  {
    label: '全局关键词',
    field: 'keyword',
    component: 'Input',
    colProps: { span: 6 },
    componentProps: { placeholder: '跨字段模糊匹配，如 10126', allowClear: true },
  },
  { label: '库位编码', field: 'locationCode', component: 'Input', colProps: { span: 6 }, componentProps: { placeholder: '任意连续子串', allowClear: true } },
  {
    label: '状态',
    field: 'status',
    component: 'Select',
    colProps: { span: 6 },
    componentProps: { options: LOCATION_STATUS_OPTIONS, allowClear: true, placeholder: '请选择状态' },
  },
];

export const formSchema: FormSchema[] = [
  { label: '', field: 'id', component: 'Input', show: false },
  { label: '库区', field: 'warehouseId', component: 'Input', show: false },
  { label: '排', field: 'rowNo', component: 'InputNumber', required: true },
  { label: '列', field: 'colNo', component: 'InputNumber', required: true },
  { label: '层', field: 'layerNo', component: 'InputNumber', required: true },
  { label: '承重', field: 'maxWeight', component: 'InputNumber' },
];

export const attrFormSchema: FormSchema[] = [
  { label: '', field: 'id', component: 'Input', show: false },
  { label: '库位编码', field: 'locationCode', component: 'Input', dynamicDisabled: true },
  { label: '承重', field: 'maxWeight', component: 'InputNumber' },
  {
    label: '状态',
    field: 'status',
    component: 'Select',
    componentProps: { options: LOCATION_STATUS_OPTIONS },
    helpMessage: '库位为「占用」时不允许修改属性',
  },
];