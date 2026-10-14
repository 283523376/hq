import { h } from 'vue';
import { Tag, Tooltip } from 'ant-design-vue';
import { BasicColumn, FormSchema } from '/@/components/Table';
import { checkOrderNo } from './order.api';
import { listAllEnabled } from '../supplier/supplier.api';

/** 订单类型选项（与蓝图：公司订单/采购订单/委外订单 一致） */
export const ORDER_TYPE_OPTIONS = [
  { label: '公司订单', value: '公司订单' },
  { label: '采购订单', value: '采购订单' },
  { label: '委外订单', value: '委外订单' },
];

/** 订单状态选项 */
export const ORDER_STATUS_OPTIONS = [
  { label: '待审核', value: '待审核' },
  { label: '已审核', value: '已审核' },
  { label: '已下达', value: '已下达' },
  { label: '已驳回', value: '已驳回' },
];

/** 同步状态选项 */
export const SYNC_STATUS_OPTIONS = [
  { label: '待同步', value: '待同步' },
  { label: '同步中', value: '同步中' },
  { label: '同步成功', value: '同步成功' },
  { label: '同步失败', value: '同步失败' },
];

export const columns: BasicColumn[] = [
  { title: '订单号', align: 'center', dataIndex: 'orderNo', width: 180 },
  { title: '订单类型', align: 'center', dataIndex: 'orderType', width: 110 },
  { title: '供应商', align: 'center', dataIndex: 'supplierName', width: 160 },
  {
    title: '订单状态',
    align: 'center',
    dataIndex: 'orderStatus',
    width: 100,
    customRender: ({ text }) => {
      const colorMap = { 待审核: 'default', 已审核: 'blue', 已下达: 'green', 已驳回: 'red' } as Record<string, string>;
      return h(Tag, { color: colorMap[text] || 'default' }, () => text);
    },
  },
  {
    title: '同步状态',
    align: 'center',
    dataIndex: 'syncStatus',
    width: 110,
    customRender: ({ text, record }) => {
      const colorMap = { 待同步: 'default', 同步中: 'processing', 同步成功: 'green', 同步失败: 'red' } as Record<string, string>;
      const tag = h(Tag, { color: colorMap[text] || 'default' }, () => text);
      // 同步失败时悬浮展示失败原因，让人一眼看到为什么
      if (text === '同步失败' && record.lastSyncError) {
        return h(Tooltip, { title: record.lastSyncError }, () => tag);
      }
      return tag;
    },
  },
  { title: '重试次数', align: 'center', dataIndex: 'retryCount', width: 90 },
  { title: '创建时间', align: 'center', dataIndex: 'createTime', width: 160 },
  { title: '下达时间', align: 'center', dataIndex: 'dispatchTime', width: 160 },
];

export const searchFormSchema: FormSchema[] = [
  { label: '订单号', field: 'orderNo', component: 'JInput', colProps: { span: 6 } },
  {
    label: '订单类型',
    field: 'orderType',
    component: 'Select',
    colProps: { span: 6 },
    componentProps: { options: ORDER_TYPE_OPTIONS, allowClear: true, placeholder: '请选择订单类型' },
  },
  {
    label: '订单状态',
    field: 'orderStatus',
    component: 'Select',
    colProps: { span: 6 },
    componentProps: { options: ORDER_STATUS_OPTIONS, allowClear: true, placeholder: '请选择订单状态' },
  },
  {
    label: '同步状态',
    field: 'syncStatus',
    component: 'Select',
    colProps: { span: 6 },
    componentProps: { options: SYNC_STATUS_OPTIONS, allowClear: true, placeholder: '请选择同步状态' },
  },
];

export const formSchema: FormSchema[] = [
  { label: '', field: 'id', component: 'Input', show: false },
  {
    label: '订单号',
    field: 'orderNo',
    component: 'Input',
    dynamicRules: ({ model }) => {
      return [
        { required: true, message: '请输入订单号!' },
        {
          validator: async (_rule: any, value: string) => {
            if (!value) {
              return Promise.resolve();
            }
            const res: any = await checkOrderNo({ code: value, id: model.id });
            if (res && res.success === false) {
              return Promise.reject(res.message || '订单号已存在，请重新输入');
            }
            return Promise.resolve();
          },
        },
      ];
    },
  },
  {
    label: '订单类型',
    field: 'orderType',
    component: 'Select',
    required: true,
    componentProps: { options: ORDER_TYPE_OPTIONS, placeholder: '请选择订单类型' },
  },
  {
    label: '供应商',
    field: 'supplierId',
    component: 'ApiSelect',
    componentProps: {
      api: listAllEnabled,
      labelField: 'supplierName',
      valueField: 'id',
      placeholder: '请选择供应商',
      showSearch: true,
      optionFilterProp: 'label',
    },
  },
  { label: '备注', field: 'remark', component: 'InputTextArea' },
];

/** 审核弹窗表单（通过 / 驳回 + 意见） */
export const auditFormSchema: FormSchema[] = [
  { label: '', field: 'id', component: 'Input', show: false },
  {
    label: '审核结果',
    field: 'pass',
    component: 'RadioButtonGroup',
    defaultValue: true,
    required: true,
    componentProps: {
      options: [
        { label: '审核通过', value: true },
        { label: '驳回', value: false },
      ],
    },
  },
  {
    label: '审核意见',
    field: 'auditRemark',
    component: 'InputTextArea',
    dynamicRules: ({ values }) => {
      // 驳回时意见必填（后端也会二次强制）
      return values.pass === false ? [{ required: true, message: '驳回时必须填写驳回原因!' }] : [];
    },
  },
];
