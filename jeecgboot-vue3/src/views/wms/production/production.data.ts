import { Tag, Tooltip } from 'ant-design-vue';
import { h } from 'vue';
import { BasicColumn, FormSchema } from '/@/components/Table';

/** 生产入库状态选项 */
export const PRODUCTION_STATUS_OPTIONS = ['待上架', '执行中', '已入库', '异常待人工', '已关闭'].map((v) => ({
  label: v,
  value: v,
}));

/** 任务状态选项 */
export const TASK_STATUS_OPTIONS = ['未下发', '已受理', '执行中', '已完成', '失败', '已关闭'].map((v) => ({
  label: v,
  value: v,
}));

/** 产品类型选项 */
export const PRODUCT_TYPE_OPTIONS = ['半成品', '成品'].map((v) => ({ label: v, value: v }));

/** 同步状态选项 */
export const SYNC_STATUS_OPTIONS = ['无需同步', '待同步', '同步中', '同步成功', '同步失败'].map((v) => ({
  label: v,
  value: v,
}));

const productionStatusColor: Record<string, string> = {
  待上架: 'blue',
  执行中: 'processing',
  已入库: 'success',
  异常待人工: 'error',
  已关闭: 'default',
};

const taskStatusColor: Record<string, string> = {
  未下发: 'default',
  已受理: 'blue',
  执行中: 'processing',
  已完成: 'success',
  失败: 'error',
  已关闭: 'default',
};

const syncStatusColor: Record<string, string> = {
  无需同步: 'default',
  待同步: 'orange',
  同步中: 'processing',
  同步成功: 'success',
  同步失败: 'error',
};

export const columns: BasicColumn[] = [
  { title: '入库单号', align: 'center', dataIndex: 'inboundNo', width: 180 },
  { title: '工单号', align: 'center', dataIndex: 'workOrderNo', width: 150 },
  { title: '产品类型', align: 'center', dataIndex: 'productType', width: 90 },
  { title: '物料', align: 'center', dataIndex: 'materialName', width: 150 },
  { title: '批次号', align: 'center', dataIndex: 'batchNo', width: 120 },
  {
    title: '良品 / 不良',
    align: 'center',
    key: 'qty',
    width: 120,
    customRender: ({ record }) => `${record.goodQty ?? 0} / ${record.badQty ?? 0}`,
  },
  {
    title: '状态',
    align: 'center',
    dataIndex: 'status',
    width: 110,
    customRender: ({ text }) => h(Tag, { color: productionStatusColor[text] || 'default' }, () => text),
  },
  {
    title: '任务状态',
    align: 'center',
    dataIndex: 'taskStatus',
    width: 110,
    customRender: ({ text }) => h(Tag, { color: taskStatusColor[text] || 'default' }, () => text),
  },
  { title: '载具码', align: 'center', dataIndex: 'carrierCode', width: 150 },
  { title: '库位', align: 'center', dataIndex: 'locationCode', width: 150 },
  {
    title: '同步状态',
    align: 'center',
    dataIndex: 'syncStatus',
    width: 150,
    customRender: ({ text, record }) =>
      isBomBlocked(record)
        ? h(
            Tooltip,
            { title: bomBlockedReason(record) },
            () => h(Tag, { color: 'warning' }, () => '同步失败·BOM待确认'),
          )
        : h(Tag, { color: syncStatusColor[text] || 'default' }, () => text),
  },
  { title: '提交时间', align: 'center', dataIndex: 'submitTime', width: 170 },
];

export const searchFormSchema: FormSchema[] = [
  { field: 'inboundNo', label: '入库单号', component: 'Input', colProps: { span: 6 } },
  { field: 'workOrderNo', label: '工单号', component: 'Input', colProps: { span: 6 } },
  {
    field: 'status',
    label: '状态',
    component: 'Select',
    componentProps: { options: PRODUCTION_STATUS_OPTIONS },
    colProps: { span: 6 },
  },
  {
    field: 'taskStatus',
    label: '任务状态',
    component: 'Select',
    componentProps: { options: TASK_STATUS_OPTIONS },
    colProps: { span: 6 },
  },
  {
    field: 'productType',
    label: '产品类型',
    component: 'Select',
    componentProps: { options: PRODUCT_TYPE_OPTIONS },
    colProps: { span: 6 },
  },
  {
    field: 'syncStatus',
    label: '同步状态',
    component: 'Select',
    componentProps: { options: SYNC_STATUS_OPTIONS },
    colProps: { span: 6 },
  },
];

/**
 * 「半成品回写被 BOM 版本阻塞」判定。
 *
 * <p>后端对这类失败会在 last_sync_error 打 {@code [BOM_PENDING]} 前缀（金蝶 PRD_INSTOCK 的
 * BOM 版本字段在 Save 时不可写，必须由生产订单下推带出，方案待金蝶确认）。
 * 兼容历史数据：老记录没有前缀，但错误原文含「BOM版本」。
 */
export function isBomBlocked(record: any): boolean {
  const err = String(record?.lastSyncError || '');
  return err.includes('[BOM_PENDING]') || err.includes('BOM版本');
}

export function bomBlockedReason(record: any): string {
  const err = String(record?.lastSyncError || '')
    .replace('[BOM_PENDING]', '')
    .trim();
  return err || '半成品回写需从生产订单下推带出 BOM 版本，方案待金蝶确认，暂不可重试';
}
