import { h } from 'vue';
import { Tag } from 'ant-design-vue';
import { BasicColumn, FormSchema } from '/@/components/Table';

/** 与后端 WmsConstants.AUDIT_* 的中文字面量一致，改一处要改两处（体检脚本会漏，注意同步） */
export const AUDIT_CHANNEL_OPTIONS = [
  { label: '界面操作', value: '界面操作' },
  { label: 'ERP同步', value: 'ERP同步' },
  { label: '回写ERP', value: '回写ERP' },
  { label: '设备回调', value: '设备回调' },
  { label: '定时任务', value: '定时任务' },
];

export const AUDIT_RESULT_OPTIONS = [
  { label: '成功', value: '成功' },
  { label: '失败', value: '失败' },
];

export const AUDIT_BIZ_TYPE_OPTIONS = [
  { label: '工单', value: '工单' },
  { label: '入库单', value: '入库单' },
  { label: '出库单', value: '出库单' },
  { label: '调拨单', value: '调拨单' },
  { label: '盘点单', value: '盘点单' },
  { label: '系统配置', value: '系统配置' },
  { label: '主数据', value: '主数据' },
  { label: '销售订单', value: '销售订单' },
];

/** 结果色：失败必须刺眼——它就是「要去看一眼」的信号 */
const RESULT_COLOR: Record<string, string> = { 成功: 'green', 失败: 'red' };

export const columns: BasicColumn[] = [
  { title: '发生时间', align: 'center', dataIndex: 'createTime', width: 170 },
  { title: '业务类型', align: 'center', dataIndex: 'bizType', width: 100 },
  { title: '业务单号', align: 'center', dataIndex: 'bizNo', width: 200 },
  { title: '关联单号', align: 'center', dataIndex: 'bizRef', width: 160 },
  { title: '动作', align: 'center', dataIndex: 'action', width: 100 },
  { title: '通道', align: 'center', dataIndex: 'channel', width: 100 },
  {
    title: '结果',
    align: 'center',
    dataIndex: 'result',
    width: 80,
    customRender: ({ text }) => h(Tag, { color: RESULT_COLOR[text] || 'default' }, () => text || '-'),
  },
  { title: '操作人', align: 'center', dataIndex: 'operator', width: 110 },
  { title: '来源IP', align: 'center', dataIndex: 'ip', width: 130 },
  { title: '耗时(ms)', align: 'center', dataIndex: 'costMs', width: 90 },
  { title: '失败原因', align: 'left', dataIndex: 'errorMsg', width: 260, ellipsis: true },
];

export const searchFormSchema: FormSchema[] = [
  { field: 'bizNo', label: '业务单号', component: 'Input', colProps: { span: 6 }, componentProps: { placeholder: '支持模糊' } },
  { field: 'bizType', label: '业务类型', component: 'Select', colProps: { span: 6 }, componentProps: { options: AUDIT_BIZ_TYPE_OPTIONS, allowClear: true } },
  { field: 'channel', label: '通道', component: 'Select', colProps: { span: 6 }, componentProps: { options: AUDIT_CHANNEL_OPTIONS, allowClear: true } },
  { field: 'result', label: '结果', component: 'Select', colProps: { span: 6 }, componentProps: { options: AUDIT_RESULT_OPTIONS, allowClear: true } },
  { field: 'operator', label: '操作人', component: 'Input', colProps: { span: 6 } },
  {
    field: 'timeRange',
    label: '发生时间',
    component: 'RangePicker',
    colProps: { span: 12 },
    componentProps: {
      valueType: 'Date',
      showTime: true,
      style: { width: '100%' },
      // 后端按 startTime/endTime 两个参数接收
      onChange: undefined,
    },
  },
];
