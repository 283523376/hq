import { FormSchema } from '/@/components/Table';
import { checkCategoryCode } from './category.api';
import { CATEGORY_TYPE_OPTIONS, STATUS_OPTIONS } from './material.data';

export const categoryFormSchema: FormSchema[] = [
  { label: '', field: 'id', component: 'Input', show: false },
  {
    label: '分类编码',
    field: 'categoryCode',
    component: 'Input',
    dynamicRules: ({ model }) => {
      return [
        { required: true, message: '请输入分类编码!' },
        {
          validator: async (_rule: any, value: string) => {
            if (!value) {
              return Promise.resolve();
            }
            const res: any = await checkCategoryCode({ code: value, id: model.id });
            if (res && res.success === false) {
              return Promise.reject(res.message || '编码已存在，请重新输入');
            }
            return Promise.resolve();
          },
        },
      ];
    },
  },
  { label: '分类名称', field: 'categoryName', component: 'Input', required: true },
  {
    label: '父级分类',
    field: 'parentId',
    component: 'TreeSelect',
    defaultValue: '0',
    componentProps: {
      placeholder: '不选则为顶级分类',
      allowClear: true,
      showSearch: true,
      treeDefaultExpandAll: true,
      fieldNames: { label: 'title', value: 'id', children: 'children' },
      getPopupContainer: () => document.body,
    },
  },
  {
    label: '分类类型',
    field: 'categoryType',
    component: 'Select',
    componentProps: { options: CATEGORY_TYPE_OPTIONS, placeholder: '请选择分类类型', allowClear: true },
  },
  {
    label: '状态',
    field: 'status',
    component: 'RadioButtonGroup',
    defaultValue: '1',
    componentProps: { options: STATUS_OPTIONS },
  },
];
