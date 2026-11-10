import { defHttp } from '/@/utils/http/axios';

/** SB-001 上位系统同步对账（端点与后端 WmsSyncController 一一对应，见 spec §3）。 */
enum Api {
  run = '/wms/sync/run',
  runPage = '/wms/sync/run/page',
  snapshotPage = '/wms/sync/snapshot/page',
  snapshotExport = '/wms/sync/snapshot/export',
  flowPage = '/wms/sync/flow/page',
  flowExport = '/wms/sync/flow/export',
  diffPage = '/wms/sync/diff/page',
  diffExport = '/wms/sync/diff/export',
  diffBase = '/wms/sync/diff',
  logPage = '/wms/sync/log/page',
  warehouseList = '/wms/warehouse/list',
}

/** 创建并异步执行对账批次；返回批次ID（runId），页面据批次状态轮询。 */
export const createRun = (params) => defHttp.post({ url: Api.run, params });

/** 对账批次分页。status 传「执行中/成功/失败」。 */
export const pageRuns = (params) => defHttp.get({ url: Api.runPage, params });

/** 批次详情——「执行中」行每 3 秒轮询它，直到终态。 */
export const getRun = (id: string) => defHttp.get({ url: `${Api.run}/${id}` });

/** 待推送包（快照）分页，runId 必填。 */
export const pageSnapshots = (params) => defHttp.get({ url: Api.snapshotPage, params });

/** 同步流水分页，runId 必填。 */
export const pageFlows = (params) => defHttp.get({ url: Api.flowPage, params });

/** 差异清单分页。 */
export const pageDiffs = (params) => defHttp.get({ url: Api.diffPage, params });

/** 标记差异处理：targetStatus 只能「已核对/已忽略」，remark 必填。 */
export const handleDiff = (id: string, params) =>
  defHttp.post({ url: `${Api.diffBase}/${id}/handle`, params });

/** 由差异发起盘点（后端返回盘点单，前端必须先弹风险确认框）。 */
export const createStocktakeFromDiff = (id: string) =>
  defHttp.post({ url: `${Api.diffBase}/${id}/create-stocktake` });

/** 同步日志分页。 */
export const pageLogs = (params) => defHttp.get({ url: Api.logPage, params });

/** 仓库下拉（含停用仓）；WAREHOUSE 范围选仓库编码用。 */
export const listWarehouseOptions = (params?) => defHttp.get({ url: Api.warehouseList, params });

/**
 * 导出请求统一取原生响应。
 *
 * 后端导出成功与失败都是 HTTP 200，成功 xlsx 给 octet-stream、成功 JSON 包给 application/json，
 * 失败体也是 application/json——只能靠 `Content-Disposition` 是否存在判断成功。
 * 所以必须 `isReturnNativeResponse: true` 取回 headers，页面交给 `useExportDownload` 落盘/提示。
 */
const EXPORT_OPTIONS = { isTransformResponse: false, isReturnNativeResponse: true };
const exportConfig = { responseType: 'blob', timeout: 120000 } as const;

/** 导出待推送包（xlsx 默认 / json）。 */
export const exportSnapshot = (params) =>
  defHttp.get({ ...exportConfig, url: Api.snapshotExport, params }, EXPORT_OPTIONS);

/** 导出同步流水（xlsx）。 */
export const exportFlow = (params) =>
  defHttp.get({ ...exportConfig, url: Api.flowExport, params }, EXPORT_OPTIONS);

/** 导出差异清单（xlsx，runId 必填）。 */
export const exportDiff = (params) =>
  defHttp.get({ ...exportConfig, url: Api.diffExport, params }, EXPORT_OPTIONS);
