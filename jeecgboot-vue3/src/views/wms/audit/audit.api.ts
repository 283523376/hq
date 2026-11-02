import { defHttp } from '/@/utils/http/axios';

enum Api {
  list = '/wms/audit/list',
  impact = '/wms/audit/impact',
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
