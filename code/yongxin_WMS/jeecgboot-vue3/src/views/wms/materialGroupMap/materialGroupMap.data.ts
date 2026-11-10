import { h } from 'vue';
import { Tag } from 'ant-design-vue';
import { BasicColumn, FormSchema } from '/@/components/Table';

/** 分类类型（后端 WmsConstants.CATEGORY_TYPE_*；非法值后端整批拒绝，前端同款四选一） */
export const CATEGORY_TYPE_OPTIONS = ['原材料', '半成品', '成品', '辅料'].map((v) => ({ label: v, value: v }));

/** 分类类型 Tag 配色（主列表展示用） */
export const CATEGORY_TYPE_COLORS: Record<string, string> = {
  原材料: 'orange',
  半成品: 'blue',
  成品: 'green',
  辅料: 'purple',
};

/** 主列表列 */
export const columns: BasicColumn[] = [
  { title: '分组编码', align: 'center', dataIndex: 'groupNumber', width: 150 },
  { title: '分组名称', align: 'center', dataIndex: 'groupName', width: 220, ellipsis: true },
  {
    title: '分类类型',
    align: 'center',
    dataIndex: 'categoryType',
    width: 110,
    customRender: ({ text }) => h(Tag, { color: CATEGORY_TYPE_COLORS[text] || 'default' }, () => text),
  },
  { title: '备注', align: 'center', dataIndex: 'remark', ellipsis: true },
  { title: '更新时间', align: 'center', dataIndex: 'updateTime', width: 170 },
];

/**
 * 主列表筛选。
 *
 * 注意：这里刻意用 Input 而不是 JInput —— JInput 会把值包成 `*xxx*` 发给后端
 * （jeecg QueryGenerator 的 like 语法），但 wms 列表的模糊查询是 controller 手写
 * `queryWrapper.like`，不剥星号，星号会进 SQL 变成字面量导致查不到（实测
 * keyword=*019* 命中 0 行、keyword=019 命中 1 行；material 等既有页同样存在此问题）。
 * 模糊语义由后端 like 保证，前端发裸值即可。
 */
export const searchFormSchema: FormSchema[] = [
  {
    label: '全局关键词',
    field: 'keyword',
    component: 'Input',
    colProps: { span: 6 },
    componentProps: { placeholder: '编码/名称/类型/备注，如 10126', allowClear: true },
  },
  { label: '分组编码', field: 'groupNumber', component: 'Input', colProps: { span: 6 }, componentProps: { placeholder: '任意连续子串', allowClear: true } },
  {
    label: '分类类型',
    field: 'categoryType',
    component: 'Select',
    colProps: { span: 6 },
    componentProps: { options: CATEGORY_TYPE_OPTIONS, allowClear: true, placeholder: '请选择分类类型' },
  },
];

/** 编辑弹层表单：分组编码/名称是幂等键与冗余列，只读展示；可改的只有分类类型与备注 */
export const formSchema: FormSchema[] = [
  { label: '', field: 'id', component: 'Input', show: false },
  { label: '分组编码', field: 'groupNumber', component: 'Input', dynamicDisabled: () => true },
  { label: '分组名称', field: 'groupName', component: 'Input', dynamicDisabled: () => true },
  {
    label: '分类类型',
    field: 'categoryType',
    component: 'Select',
    required: true,
    componentProps: { options: CATEGORY_TYPE_OPTIONS, placeholder: '请选择分类类型' },
  },
  { label: '备注', field: 'remark', component: 'InputTextArea', componentProps: { maxlength: 200, rows: 3, placeholder: '选填' } },
];
