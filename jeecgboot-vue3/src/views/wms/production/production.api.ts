import { defHttp } from '/@/utils/http/axios';

/** 接口前缀（生产入库多个动作共用 /wms/production） */
const PRODUCTION = '/wms/production';

/** 扫码调出工单信息 */
export const scanWorkOrder = (workOrderNo: string) =>
  defHttp.get({ url: `${PRODUCTION}/work-order`, params: { workOrderNo } });

/** 提交生产入库（一次提交 = 一个载具） */
export const submitProductionInbound = (params) =>
  defHttp.post({ url: `${PRODUCTION}/submit`, params });

/** 生产入库分页 */
export const listProductionInbound = (params?) =>
  defHttp.get({ url: `${PRODUCTION}/list`, params });

/** 生产入库详情 */
export const getProductionInbound = (id: string) => defHttp.get({ url: `${PRODUCTION}/${id}` });

/** 重新下发任务 */
export const dispatchProductionInbound = (id: string) =>
  defHttp.post({ url: `${PRODUCTION}/${id}/dispatch` });

/** 转人工完成 */
export const manualCompleteProductionInbound = (id: string, params) =>
  defHttp.post({ url: `${PRODUCTION}/${id}/manual-complete`, params });

/** 关单 */
export const closeProductionInbound = (id: string, reason: string) =>
  defHttp.post({ url: `${PRODUCTION}/${id}/close`, params: { reason } });

/** 手动重试同步 */
export const resyncProductionInbound = (id: string) =>
  defHttp.post({ url: `${PRODUCTION}/${id}/sync` });
