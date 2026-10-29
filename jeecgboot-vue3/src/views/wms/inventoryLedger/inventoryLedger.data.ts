import { BasicColumn } from '/@/components/Table';
import { h } from 'vue';
import { Tag } from 'ant-design-vue';

/** 库存台账明细列——列序与后端 WmsInventoryLedgerVO 的 @Excel orderNum 保持一致 */
export const ledgerColumns: BasicColumn[] = [
  { title: '物料编码', align: 'center', dataIndex: 'materialCode', width: 130 },
  { title: '物料名称', align: 'center', dataIndex: 'materialName', width: 160 },
  { title: '规格', align: 'center', dataIndex: 'spec', width: 90 },
  { title: '单位', align: 'center', dataIndex: 'unit', width: 60 },
  { title: '批次号', align: 'center', dataIndex: 'batchNo', width: 170 },
  { title: '仓库编码', align: 'center', dataIndex: 'warehouseCode', width: 100 },
  { title: '仓库名称', align: 'center', dataIndex: 'warehouseName', width: 150 },
  {
    title: '仓库状态',
    align: 'center',
    dataIndex: 'warehouseStatus',
    width: 90,
    customRender: ({ text }) =>
      h(
        Tag,
        { color: text === '0' ? 'default' : 'green' },
        () => (text === '0' ? '停用' : '启用'),
      ),
  },
  { title: '库位编码', align: 'center', dataIndex: 'locationCode', width: 130 },
  { title: '料箱/托盘', align: 'center', dataIndex: 'containerCode', width: 130 },
  { title: '库存数量', align: 'center', dataIndex: 'quantity', width: 100 },
  { title: '锁定数量', align: 'center', dataIndex: 'lockedQty', width: 100 },
  { title: '可用量', align: 'center', dataIndex: 'availableQty', width: 100 },
  { title: '首次入库时间', align: 'center', dataIndex: 'firstInboundTime', width: 160 },
  { title: '库龄(天)', align: 'center', dataIndex: 'stockAgeDays', width: 90 },
];

export const EXPORT_STATUS_COLOR: Record<string, string> = {
  PENDING: 'blue',
  RUNNING: 'blue',
  SUCCESS: 'green',
  FAILED: 'red',
};

export const EXPORT_STATUS_LABEL: Record<string, string> = {
  PENDING: '排队中',
  RUNNING: '生成中',
  SUCCESS: '成功',
  FAILED: '失败',
};

/** 导出任务列 */
export const taskColumns: BasicColumn[] = [
  {
    title: '状态',
    align: 'center',
    dataIndex: 'status',
    width: 90,
    customRender: ({ text }) =>
      h(
        Tag,
        { color: EXPORT_STATUS_COLOR[text] || 'default' },
        () => EXPORT_STATUS_LABEL[text] || text,
      ),
  },
  { title: '行数', align: 'center', dataIndex: 'rowCount', width: 100 },
  { title: '文件名', align: 'left', dataIndex: 'fileName', width: 260, ellipsis: true },
  { title: '提交时间', align: 'center', dataIndex: 'createTime', width: 160 },
  { title: '完成时间', align: 'center', dataIndex: 'finishTime', width: 160 },
  { title: '过期时间', align: 'center', dataIndex: 'expireTime', width: 160 },
  { title: '失败原因', align: 'left', dataIndex: 'errorMsg', width: 240, ellipsis: true },
];
