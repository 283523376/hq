import { defHttp } from '/@/utils/http/axios';

enum Api {
  list = '/wms/work-order/list',
  syncKingdee = '/wms/work-order/sync-kingdee',
  inboundRecords = '/wms/work-order/inbound-records',
}

export const listWorkOrder = (params?) => defHttp.get({ url: Api.list, params });

/**
 * 从金蝶 PRD_MO 同步生产工单（BD-002 V2.2 唯一业务写入口）。
 * 未传起止日期时后端默认取最近 7 天，并在返回值里回显实际生效窗口。
 */
export const syncKingdeeWorkOrder = (params?: {
  billNo?: string;
  startDate?: string;
  endDate?: string;
  top?: number;
}) => defHttp.post({ url: Api.syncKingdee, params, data: {} });

/** 工单关联的生产入库记录（蓝图：查询工单执行情况）。 */
export const fetchInboundRecords = (workOrderNo: string) =>
  defHttp.get({ url: Api.inboundRecords, params: { workOrderNo } });
