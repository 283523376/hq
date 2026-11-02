import { h } from 'vue';
import { Tag } from 'ant-design-vue';
import { BasicColumn, FormSchema } from '/@/components/Table';
import { checkDeviceCode } from './device.api';

/** 设备类型（与后端 WmsConstants 的中文字面量一致） */
export const DEVICE_TYPE_OPTIONS = [
  { label: '堆垛机', value: '堆垛机' },
  { label: 'SCR', value: 'SCR' },
  { label: '分拣单元', value: '分拣单元' },
  { label: '充电桩', value: '充电桩' },
];

export const DEVICE_STATUS_OPTIONS = [
  { label: '在线', value: '在线' },
  { label: '离线', value: '离线' },
  { label: '故障', value: '故障' },
];

/** 状态色：在线(绿) / 离线(红) / 故障(橙)。只有这一处定义。 */
export const DEVICE_STATUS_COLOR: Record<string, string> = {
  在线: 'green',
  离线: 'red',
  故障: 'orange',
};

export const columns: BasicColumn[] = [
  { title: '设备编码', align: 'center', dataIndex: 'deviceCode', width: 140 },
  { title: '设备名称', align: 'center', dataIndex: 'deviceName', width: 160 },
  { title: '设备类型', align: 'center', dataIndex: 'deviceType', width: 100 },
  {
    title: '归属库区',
    align: 'center',
    dataIndex: 'warehouseName',
    width: 140,
    // 未绑定与「库区已删除」都回填 null，显示成「未绑定」比空白更明确
    customRender: ({ text }) => text || '未绑定',
  },
  {
    title: '状态',
    align: 'center',
    dataIndex: 'status',
    width: 90,
    // 离线标红，一眼可见（验收标准②的界面侧呈现）
    customRender: ({ text }) => h(Tag, { color: DEVICE_STATUS_COLOR[text] || 'default' }, () => text),
  },
  { title: '最近心跳', align: 'center', dataIndex: 'lastHeartbeat', width: 170 },
  { title: '最近告警', align: 'center', dataIndex: 'lastAlertTime', width: 170 },
];

export const searchFormSchema: FormSchema[] = [
  {
    label: '全局关键词',
    field: 'keyword',
    component: 'JInput',
    colProps: { span: 6 },
    componentProps: { placeholder: '跨字段模糊匹配，如 10126' },
  },
  { label: '设备编码', field: 'deviceCode', component: 'JInput', colProps: { span: 6 } },
  { label: '设备名称', field: 'deviceName', component: 'JInput', colProps: { span: 6 } },
  {
    label: '设备类型',
    field: 'deviceType',
    component: 'Select',
    colProps: { span: 6 },
    componentProps: { options: DEVICE_TYPE_OPTIONS, allowClear: true, placeholder: '请选择类型' },
  },
  {
    // 「只看离线」就是按状态筛选
    label: '状态',
    field: 'status',
    component: 'Select',
    colProps: { span: 6 },
    componentProps: { options: DEVICE_STATUS_OPTIONS, allowClear: true, placeholder: '请选择状态' },
  },
];

export const formSchema: FormSchema[] = [
  { label: '', field: 'id', component: 'Input', show: false },
  {
    label: '设备编码',
    field: 'deviceCode',
    component: 'Input',
    required: true,
    dynamicRules: ({ model }) => [
      { required: true, message: '请输入设备编码!' },
      {
        validator: async (_rule: any, value: string) => {
          if (!value) {
            return Promise.resolve();
          }
          const res: any = await checkDeviceCode({ code: value, id: model.id });
          if (res && res.success === false) {
            return Promise.reject(res.message || '设备编码已存在，请重新输入');
          }
          return Promise.resolve();
        },
      },
    ],
  },
  { label: '设备名称', field: 'deviceName', component: 'Input', required: true },
  {
    label: '设备类型',
    field: 'deviceType',
    component: 'Select',
    required: true,
    componentProps: { options: DEVICE_TYPE_OPTIONS, placeholder: '请选择设备类型' },
  },
  {
    label: '状态',
    field: 'status',
    component: 'Select',
    defaultValue: '离线',
    componentProps: { options: DEVICE_STATUS_OPTIONS },
    helpMessage: '置为「在线」时会同时打上心跳时间',
  },
];

/** 绑定弹窗只选库区；设备用表格勾选 */
export const bindFormSchema: FormSchema[] = [
  {
    label: '目标库区',
    field: 'warehouseId',
    component: 'Select',
    required: true,
    componentProps: { options: [], placeholder: '请选择库区', showSearch: true, optionFilterProp: 'label' },
  },
];