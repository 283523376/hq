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
  { title: '保质期(到期日)', align: 'center', dataIndex: 'shelfLife', width: 130 },
  {
    title: '状态',
    align: 'center',
    dataIndex: 'status',
    width: 80,
    customRender: ({ text }) => (text === '1' ? '启用' : '停用'),
  },
];

export const searchFormSchema: FormSchema[] = [
  { label: '物料编码', field: 'materialCode', component: 'JInput', colProps: { span: 6 } },
  { label: '物料名称', field: 'materialName', component: 'JInput', colProps: { span: 6 } },
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
