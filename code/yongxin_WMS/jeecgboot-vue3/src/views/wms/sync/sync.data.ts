import { h } from 'vue';
import { Tag } from 'ant-design-vue';
import { BasicColumn } from '/@/components/Table';

// ---------------------------------------------------------------- 下拉选项
// 取值必须与后端 WmsConstants 的中文字面量逐字一致（改一处要改两处）。

/** 对账范围：FULL/TODAY/WAREHOUSE（英文编码，见 WmsConstants.SYNC_SCOPE_*）。 */
export const SYNC_SCOPE_OPTIONS = [
  { label: '全量库存', value: 'FULL' },
  { label: '今日变动', value: 'TODAY' },
  { label: '指定仓库', value: 'WAREHOUSE' },
];

export const SYNC_SCOPE_LABEL: Record<string, string> = {
  FULL: '全量库存',
  TODAY: '今日变动',
  WAREHOUSE: '指定仓库',
};

/** 批次状态：执行中/成功/失败（WmsConstants.SYNC_RUN_STATUS_*）。 */
export const SYNC_RUN_STATUS_OPTIONS = ['执行中', '成功', '失败'].map((v) => ({
  label: v,
  value: v,
}));

/** 快照匹配状态：一致/数量差异/仅WMS/仅ERP（WmsConstants.SNAPSHOT_MATCH_*）。 */
export const SNAPSHOT_MATCH_STATUS_OPTIONS = ['一致', '数量差异', '仅WMS', '仅ERP'].map((v) => ({
  label: v,
  value: v,
}));

/** 差异类型：多账/少账/仅WMS/仅ERP（WmsConstants.DIFF_TYPE_* + 两个仅单边类型）。 */
export const DIFF_TYPE_OPTIONS = ['多账', '少账', '仅WMS', '仅ERP'].map((v) => ({
  label: v,
  value: v,
}));

/** 差异状态：待核对/已核对/已忽略（WmsConstants.DIFF_STATUS_*）。 */
export const DIFF_STATUS_OPTIONS = ['待核对', '已核对', '已忽略'].map((v) => ({
  label: v,
  value: v,
}));

/** 流水类型：入库/出库/调拨/盘点（WmsSyncFlow.flowType）。 */
export const FLOW_TYPE_OPTIONS = ['入库', '出库', '调拨', '盘点'].map((v) => ({
  label: v,
  value: v,
}));

/** 流水方向：IN/OUT。 */
export const FLOW_DIRECTION_OPTIONS = [
  { label: '入库', value: 'IN' },
  { label: '出库', value: 'OUT' },
];

/** 同步日志方向：ERP_TO_WMS/WMS_TO_ERP/WMS_INTERNAL（WmsConstants.SYNC_LOG_DIRECTION_*）。 */
export const SYNC_LOG_DIRECTION_OPTIONS = [
  { label: 'ERP → WMS', value: 'ERP_TO_WMS' },
  { label: 'WMS → ERP', value: 'WMS_TO_ERP' },
  { label: 'WMS 内部', value: 'WMS_INTERNAL' },
];

export const SYNC_LOG_DIRECTION_LABEL: Record<string, string> = {
  ERP_TO_WMS: 'ERP → WMS',
  WMS_TO_ERP: 'WMS → ERP',
  WMS_INTERNAL: 'WMS 内部',
};

/**
 * 同步日志对象类型。
 *
 * 后端多数是各服务里的 `SYNC_OBJECT_TYPE` 私有常量（没有集中到 WmsConstants），
 * 这里按代码实际取值登记；新增同步服务时记得补一条，否则筛不到。
 */
export const SYNC_LOG_OBJECT_TYPE_OPTIONS = [
  { label: '生产工单', value: 'WORK_ORDER' },
  { label: '工序汇报', value: 'OPERATION_REPORT' },
  { label: '委外', value: 'SUBCONTRACT' },
  { label: '物料', value: 'MATERIAL' },
  { label: '物料分类', value: 'MATERIAL_CATEGORY' },
  { label: '仓库', value: 'WAREHOUSE' },
  { label: '仓位', value: 'KINGDEE_BIN' },
  { label: '库存对账', value: 'INVENTORY_RECONCILE' },
  { label: '库存预警', value: 'INVENTORY_WARNING' },
];

/** 同步日志结果：同步成功/同步失败（WmsConstants.SYNC_STATUS_*）。 */
export const SYNC_LOG_STATUS_OPTIONS = ['同步成功', '同步失败'].map((v) => ({
  label: v,
  value: v,
}));

// ---------------------------------------------------------------- 配色

export const RUN_STATUS_COLOR: Record<string, string> = {
  执行中: 'processing',
  成功: 'success',
  失败: 'error',
};

export const SNAPSHOT_MATCH_COLOR: Record<string, string> = {
  一致: 'success',
  数量差异: 'warning',
  仅WMS: 'blue',
  仅ERP: 'purple',
};

export const DIFF_STATUS_COLOR: Record<string, string> = {
  待核对: 'warning',
  已核对: 'success',
  已忽略: 'default',
};

export const DIFF_TYPE_COLOR: Record<string, string> = {
  多账: 'red',
  少账: 'orange',
  仅WMS: 'blue',
  仅ERP: 'purple',
};

const tag = (color: string, text: string) => h(Tag, { color }, () => text || '-');

// ---------------------------------------------------------------- 列定义

/** 对账批次表 */
export const runColumns: BasicColumn[] = [
  { title: '批次号', align: 'center', dataIndex: 'runNo', width: 190, fixed: 'left' },
  {
    title: '范围',
    align: 'center',
    dataIndex: 'scopeType',
    width: 100,
    customRender: ({ text }) => SYNC_SCOPE_LABEL[text] || text || '-',
  },
  {
    title: '时间窗',
    align: 'center',
    key: 'window',
    width: 330,
    customRender: ({ record }) => `${record.windowStart || '—'} ~ ${record.windowEnd || '—'}`,
  },
  {
    title: '状态',
    align: 'center',
    dataIndex: 'status',
    width: 90,
    customRender: ({ text }) => tag(RUN_STATUS_COLOR[text] || 'default', text),
  },
  { title: '快照数', align: 'center', dataIndex: 'snapshotCount', width: 90 },
  { title: '差异数', align: 'center', dataIndex: 'diffCount', width: 90 },
  {
    title: '耗时',
    align: 'center',
    dataIndex: 'costMs',
    width: 100,
    customRender: ({ text }) => {
      if (text === null || text === undefined) {
        return '—';
      }
      return Number(text) >= 1000 ? `${(Number(text) / 1000).toFixed(1)} s` : `${text} ms`;
    },
  },
  { title: '操作人', align: 'center', dataIndex: 'operator', width: 100 },
  { title: '错误', align: 'left', dataIndex: 'errorMessage', width: 240, ellipsis: true },
  { title: '操作', align: 'center', key: 'action', width: 230, fixed: 'right' },
];

/** 待推送包（库存快照）表 */
export const snapshotColumns: BasicColumn[] = [
  { title: '仓库编码', align: 'center', dataIndex: 'warehouseCode', width: 110 },
  { title: '物料编码', align: 'center', dataIndex: 'materialCode', width: 140 },
  { title: '物料名称', align: 'center', dataIndex: 'materialName', width: 170 },
  { title: '单位', align: 'center', dataIndex: 'unit', width: 70 },
  { title: '批次号', align: 'center', dataIndex: 'batchNo', width: 150 },
  { title: 'WMS数量', align: 'center', dataIndex: 'wmsQty', width: 110 },
  { title: '金蝶数量', align: 'center', dataIndex: 'erpQty', width: 110 },
  { title: '差异数量', align: 'center', dataIndex: 'diffQty', width: 110 },
  {
    title: '匹配状态',
    align: 'center',
    dataIndex: 'matchStatus',
    width: 100,
    customRender: ({ text }) => tag(SNAPSHOT_MATCH_COLOR[text] || 'default', text),
  },
  { title: '锁定数量', align: 'center', dataIndex: 'lockedQty', width: 100 },
  { title: '快照时点', align: 'center', dataIndex: 'snapshotTime', width: 160 },
];

/** 同步流水表 */
export const flowColumns: BasicColumn[] = [
  {
    title: '类型',
    align: 'center',
    dataIndex: 'flowType',
    width: 90,
  },
  {
    title: '方向',
    align: 'center',
    dataIndex: 'direction',
    width: 80,
    customRender: ({ text }) =>
      text === 'IN' ? tag('green', '入库') : text === 'OUT' ? tag('red', '出库') : tag('default', text),
  },
  { title: '业务单号', align: 'center', dataIndex: 'billNo', width: 200 },
  { title: '仓库编码', align: 'center', dataIndex: 'warehouseCode', width: 110 },
  { title: '物料编码', align: 'center', dataIndex: 'materialCode', width: 140 },
  { title: '物料名称', align: 'center', dataIndex: 'materialName', width: 170 },
  { title: '批次号', align: 'center', dataIndex: 'batchNo', width: 150 },
  { title: '数量', align: 'center', dataIndex: 'qty', width: 110 },
  { title: '业务时间', align: 'center', dataIndex: 'bizTime', width: 160 },
];

/** 差异清单表 */
export const diffColumns: BasicColumn[] = [
  { title: '仓库编码', align: 'center', dataIndex: 'warehouseCode', width: 110 },
  { title: '物料编码', align: 'center', dataIndex: 'materialCode', width: 140 },
  { title: '物料名称', align: 'center', dataIndex: 'materialName', width: 170 },
  { title: '批次号', align: 'center', dataIndex: 'batchNo', width: 150 },
  { title: 'WMS数量', align: 'center', dataIndex: 'wmsQty', width: 100 },
  { title: '金蝶数量', align: 'center', dataIndex: 'erpQty', width: 100 },
  { title: '差异数量', align: 'center', dataIndex: 'diffQty', width: 100 },
  {
    title: '差异类型',
    align: 'center',
    dataIndex: 'diffType',
    width: 100,
    customRender: ({ text }) => tag(DIFF_TYPE_COLOR[text] || 'default', text),
  },
  {
    title: '状态',
    align: 'center',
    dataIndex: 'status',
    width: 90,
    customRender: ({ text }) => tag(DIFF_STATUS_COLOR[text] || 'default', text),
  },
  { title: '处理人', align: 'center', dataIndex: 'handleBy', width: 100 },
  { title: '处理时间', align: 'center', dataIndex: 'handleTime', width: 160 },
  { title: '处理备注', align: 'left', dataIndex: 'handleRemark', width: 200, ellipsis: true },
  { title: '盘点单号', align: 'center', dataIndex: 'stocktakeNo', width: 180 },
  { title: '操作', align: 'center', key: 'action', width: 170, fixed: 'right' },
];

/** 同步日志表 */
export const logColumns: BasicColumn[] = [
  { title: '发生时间', align: 'center', dataIndex: 'createTime', width: 165 },
  {
    title: '方向',
    align: 'center',
    dataIndex: 'direction',
    width: 110,
    customRender: ({ text }) => SYNC_LOG_DIRECTION_LABEL[text] || text || '-',
  },
  { title: '对象类型', align: 'center', dataIndex: 'objectType', width: 150 },
  { title: '业务单号', align: 'center', dataIndex: 'objectNo', width: 190 },
  { title: '对端', align: 'center', dataIndex: 'target', width: 90 },
  {
    title: '结果',
    align: 'center',
    dataIndex: 'status',
    width: 90,
    customRender: ({ text }) =>
      tag(text === '同步成功' ? 'success' : text === '同步失败' ? 'error' : 'default', text),
  },
  { title: '失败原因', align: 'left', dataIndex: 'errorMessage', width: 240, ellipsis: true },
  { title: '耗时(ms)', align: 'center', dataIndex: 'costMs', width: 100 },
  { title: '操作', align: 'center', key: 'action', width: 110, fixed: 'right' },
];
