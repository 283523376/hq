import { BasicColumn, FormSchema } from '/@/components/Table';
import { checkWarehouseCode } from './warehouse.api';

/** 库区类型（与后端 WmsConstants 的中文字面量一致） */
export const WAREHOUSE_TYPE_OPTIONS = [
  { label: '立库', value: '立库' },
  { label: '敏捷仓', value: '敏捷仓' },
  { label: '线边柜', value: '线边柜' },
];

export const STATUS_OPTIONS = [
  { label: '启用', value: '1' },
  { label: '停用', value: '0' },
];

export const columns: BasicColumn[] = [
  { title: '库区编码', align: 'center', dataIndex: 'warehouseCode', width: 120 },
  { title: '库区名称', align: 'center', dataIndex: 'warehouseName', width: 160 },
  { title: '库区类型', align: 'center', dataIndex: 'warehouseType', width: 100 },
  { title: '排上限', align: 'center', dataIndex: 'rowCount', width: 80 },
  { title: '列上限', align: 'center', dataIndex: 'colCount', width: 80 },
  { title: '层上限', align: 'center', dataIndex: 'layerCount', width: 80 },
  {
    title: '状态',
    align: 'center',
    dataIndex: 'status',
    width: 80,
    customRender: ({ text }) => (text === '1' ? '启用' : '停用'),
  },
];

export const formSchema: FormSchema[] = [
  { label: '', field: 'id', component: 'Input', show: false },
  {
    label: '库区编码',
    field: 'warehouseCode',
    component: 'Input',
    required: true,
    dynamicRules: ({ model }) => [
      { required: true, message: '请输入库区编码!' },
      {
        validator: async (_rule: any, value: string) => {
          if (!value) {
            return Promise.resolve();
          }
          const res: any = await checkWarehouseCode({ code: value, id: model.id });
          if (res && res.success === false) {
            return Promise.reject(res.message || '库区编码已存在，请重新输入');
          }
          return Promise.resolve();
        },
      },
    ],
  },
  { label: '库区名称', field: 'warehouseName', component: 'Input', required: true },
  {
    label: '库区类型',
    field: 'warehouseType',
    component: 'Select',
    required: true,
    componentProps: { options: WAREHOUSE_TYPE_OPTIONS, placeholder: '请选择库区类型' },
  },
  {
    label: '排数上限',
    field: 'rowCount',
    component: 'InputNumber',
    helpMessage: '仅限制批量生成的范围，不参与库位编码计算，可随时自由增减',
  },
  {
    label: '列数上限',
    field: 'colCount',
    component: 'InputNumber',
    helpMessage: '仅限制批量生成的范围，不参与库位编码计算，可随时自由增减',
  },
  {
    label: '层数上限',
    field: 'layerCount',
    component: 'InputNumber',
    helpMessage: '仅限制批量生成的范围，不参与库位编码计算，可随时自由增减',
  },
  {
    label: '状态',
    field: 'status',
    component: 'RadioButtonGroup',
    defaultValue: '1',
    componentProps: { options: STATUS_OPTIONS },
  },
];