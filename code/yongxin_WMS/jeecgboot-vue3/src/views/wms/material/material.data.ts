import { BasicColumn, FormSchema } from '/@/components/Table';
import { checkMaterialCode } from './material.api';

/** 分类类型选项（与蓝图：半成品/成品/辅料 一致） */
export const CATEGORY_TYPE_OPTIONS = [
  { label: '半成品', value: '半成品' },
  { label: '成品', value: '成品' },
  { label: '辅料', value: '辅料' },
];

/** 状态选项 */
export const STATUS_OPTIONS = [
  { label: '启用', value: '1' },
  { label: '停用', value: '0' },
];

export const columns: BasicColumn[] = [
  { title: '物料编码', align: 'center', dataIndex: 'materialCode', width: 140 },
  { title: '物料名称', align: 'center', dataIndex: 'materialName', width: 160 },
  { title: '分类', align: 'center', dataIndex: 'categoryName', width: 140 },
  { title: '规格', align: 'center', dataIndex: 'spec' },
  { title: '单位', align: 'center', dataIndex: 'unit', width: 80 },
  { title: '金蝶编码', align: 'center', dataIndex: 'kingdeeCode', width: 110 },
  { title: '保质期(到期日)', align: 'center', dataIndex: 'shelfLife', width: 130 },
  {
    title: '状态',
    align: 'center',
    dataIndex: 'status',
    width: 80,
    customRender: ({ text }) => (text === '1' ? '启用' : '停用'),
  },
];

/**
 * 注意：文本筛选框刻意用 Input 而不是 JInput —— JInput 会把值包成 `*xxx*` 发给后端
 * （jeecg QueryGenerator 的 like 语法），但本页后端（WmsMaterialController）是手写
 * `queryWrapper.like` 且先把实体文本字段置空，星号不会被翻译，进 SQL 变成字面量
 * 导致恒 0 行。模糊语义由后端 like 保证，前端发裸值即可（详见 materialGroupMap.data.ts）。
 */
export const searchFormSchema: FormSchema[] = [
  {
    label: '全局关键词',
    field: 'keyword',
    component: 'Input',
    colProps: { span: 6 },
    componentProps: { placeholder: '跨字段模糊匹配，如 10126', allowClear: true },
  },
  { label: '物料编码', field: 'materialCode', component: 'Input', colProps: { span: 6 }, componentProps: { placeholder: '任意连续子串', allowClear: true } },
  { label: '物料名称', field: 'materialName', component: 'Input', colProps: { span: 6 }, componentProps: { placeholder: '任意连续子串', allowClear: true } },
  {
    label: '状态',
    field: 'status',
    component: 'Select',
    colProps: { span: 6 },
    componentProps: { options: STATUS_OPTIONS, allowClear: true, placeholder: '请选择状态' },
  },
];

export const formSchema: FormSchema[] = [
  { label: '', field: 'id', component: 'Input', show: false },
  {
    label: '物料编码',
    field: 'materialCode',
    component: 'Input',
    dynamicRules: ({ model }) => {
      return [
        { required: true, message: '请输入物料编码!' },
        {
          // 唯一性：后端返回的第二参数用于排除自身
          validator: async (_rule: any, value: string) => {
            if (!value) {
              return Promise.resolve();
            }
            const res: any = await checkMaterialCode({ code: value, id: model.id });
            if (res && res.success === false) {
              return Promise.reject(res.message || '编码已存在，请重新输入');
            }
            return Promise.resolve();
          },
        },
      ];
    },
  },
  { label: '物料名称', field: 'materialName', component: 'Input', required: true },
  {
    label: '所属分类',
    field: 'categoryId',
    component: 'TreeSelect',
    componentProps: {
      placeholder: '请选择所属分类',
      allowClear: true,
      showSearch: true,
      treeDefaultExpandAll: true,
      fieldNames: { label: 'title', value: 'id', children: 'children' },
      getPopupContainer: () => document.body,
    },
  },
  { label: '规格', field: 'spec', component: 'Input' },
  { label: '单位', field: 'unit', component: 'Input' },
  { label: '金蝶编码', field: 'kingdeeCode', component: 'Input', helpMessage: 'WMS→金蝶编码映射；ERP 同步物料恒等，手工物料需维护' },
  { label: '产品类型', field: 'kingdeeProductType', component: 'Input', helpMessage: '半成品/成品；金蝶无此概念，人工维护' },
  {
    label: '保质期(到期日)',
    field: 'shelfLife',
    component: 'DatePicker',
    componentProps: {
      valueFormat: 'YYYY-MM-DD',
      placeholder: '请选择到期日',
      style: 'width:100%',
      getPopupContainer: () => document.body,
    },
  },
  {
    label: '状态',
    field: 'status',
    component: 'RadioButtonGroup',
    defaultValue: '1',
    componentProps: { options: STATUS_OPTIONS },
  },
];
