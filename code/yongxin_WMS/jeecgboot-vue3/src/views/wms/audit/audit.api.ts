import { defHttp } from '/@/utils/http/axios';

enum Api {
  list = '/wms/audit/list',
  impact = '/wms/audit/impact',
  exportTask = '/wms/audit/export',
}

/** 审计日志分页（只读，没有增删改） */
export const list = (params?) => defHttp.get({ url: Api.list, params });

/**
 * 影响面查询：这张工单在 WMS 碰过什么。
 *
 * 返回 `{ workOrder, productionInbounds, outbounds, inventoryRows, erpBills, timeline, gaps }`。
 * **gaps 非空要显式提示**——查不到不代表没影响，只代表已删除、只剩日志。
 */
export const queryImpact = (workOrderNo: string) =>
  defHttp.get({ url: Api.impact, params: { workOrderNo } });

/**
 * 创建审计日志导出任务（异步）。
 *
 * 任务由后端线程池生成文件，前端在导出任务列表里下载；无数据时任务会失败并给出原因，
 * **不会产出空文件**。
 */
export const createAuditExport = (params?) => defHttp.post({ url: Api.exportTask, params });

// 导出任务与库存台账共用一套（后端端点同 URL，权限按 OR 判定：台账导出或审计导出任一即可）
enum TaskApi {
  taskPage = '/wms/inventoryLedger/export/task/page',
  taskBase = '/wms/inventoryLedger/export/task',
}

/** 我的导出任务分页（按 createBy 隔离，只能看到自己的） */
export const queryTaskPage = (params?) => defHttp.get({ url: TaskApi.taskPage, params });

/** 重试失败任务 */
export const retryTask = (id: string) => defHttp.post({ url: `${TaskApi.taskBase}/${id}/retry` });

/** 删除任务（连文件一起删） */
export const deleteTask = (id: string) =>
  defHttp.delete({ url: `${TaskApi.taskBase}/${id}` }, { joinParamsToUrl: true });
