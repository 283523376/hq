import { BasicColumn, FormSchema } from '/@/components/Table';
import { checkCustomerCode } from './customer.api';

/** 状态选项 */
export const STATUS_OPTIONS = [
  { label: '启用', value: '1' },
  { label: '停用', value: '0' },
];

export const columns: BasicColumn[] = [
  { title: '客户编码', align: 'center', dataIndex: 'customerCode', width: 140 },
  { title: '客户名称', align: 'center', dataIndex: 'customerName', width: 180 },
  { title: '联系人', align: 'center', dataIndex: 'contact', width: 100 },
  { title: '联系电话', align: 'center', dataIndex: 'phone', width: 130 },
  {
    title: '状态',
    align: 'center',
    dataIndex: 'status',
    width: 80,
    customRender: ({ text }) => (text === '1' ? '启用' : '停用'),
  },
];

export const searchFormSchema: FormSchema[] = [
  {
    label: '全局关键词',
    field: 'keyword',
    component: 'JInput',
    colProps: { span: 6 },
    componentProps: { placeholder: '跨字段模糊匹配，如 客户编码' },
  },
  { label: '客户编码', field: 'customerCode', component: 'JInput', colProps: { span: 6 } },
  { label: '客户名称', field: 'customerName', component: 'JInput', colProps: { span: 6 } },
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
    label: '客户编码',
    field: 'customerCode',
    component: 'Input',
    dynamicRules: ({ model }) => {
      return [
        { required: true, message: '请输入客户编码!' },
        {
          // 唯一性：请求返回的第二参数用于排除自身
          validator: async (_rule: any, value: string) => {
            if (!value) {
              return Promise.resolve();
            }
            const res: any = await checkCustomerCode({ code: value, id: model.id });
            if (res && res.success === false) {
              return Promise.reject(res.message || '编码已存在，请重新输入');
            }
            return Promise.resolve();
          },
        },
      ];
    },
  },
  { label: '客户名称', field: 'customerName', component: 'Input', required: true },
  { label: '联系人', field: 'contact', component: 'Input' },
  { label: '联系电话', field: 'phone', component: 'Input' },
  {
    label: '状态',
    field: 'status',
    component: 'RadioButtonGroup',
    defaultValue: '1',
    componentProps: { options: STATUS_OPTIONS },
  },
];
