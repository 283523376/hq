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
 * 导出待推送包。
 *
 * 后端导出失败时仍返回 HTTP 200 + `Result.error` JSON（无 Content-Disposition），
 * 所以这里拿原始 blob，由页面判断 `blob.type` 是否 JSON 再决定提示错误还是落盘。
 */
export const exportSnapshot = (params) =>
  defHttp.get(
    { url: Api.snapshotExport, params, responseType: 'blob', timeout: 120000 },
    { isTransformResponse: false },
  );

/** 导出同步流水（xlsx）。失败语义同 {@link exportSnapshot}。 */
export const exportFlow = (params) =>
  defHttp.get(
    { url: Api.flowExport, params, responseType: 'blob', timeout: 120000 },
    { isTransformResponse: false },
  );
