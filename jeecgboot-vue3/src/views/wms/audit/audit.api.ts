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
