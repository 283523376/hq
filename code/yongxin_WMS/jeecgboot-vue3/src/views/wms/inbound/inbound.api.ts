import { defHttp } from '/@/utils/http/axios';

/** 接口前缀（多个动作共用 /wms/inbound 前缀，故用常量而非枚举，避免重复枚举值） */
const INBOUND = '/wms/inbound';
const INSPECTION = '/wms/inspection';

/** 收料（生成收料通知单） */
export const receiveInbound = (params) => defHttp.post({ url: `${INBOUND}/receive`, params });

/** 入库单分页 */
export const listInbound = (params?) => defHttp.get({ url: `${INBOUND}/list`, params });

/** 单据详情（单据 + 明细 + 检验任务） */
export const getInboundDetail = (id: string) => defHttp.get({ url: `${INBOUND}/${id}` });

/** 自动分配库位 */
export const autoAllocate = (id: string) => defHttp.post({ url: `${INBOUND}/${id}/auto-allocate` });

/** 空闲库位（按物料或库区类型过滤） */
export const listFreeLocations = (params) => defHttp.get({ url: `${INBOUND}/free-locations`, params });

/** 上架确认 */
export const putaway = (id: string, params) => defHttp.post({ url: `${INBOUND}/${id}/putaway`, params });

/** 作废 */
export const voidInbound = (id: string, reason: string) =>
  defHttp.post({ url: `${INBOUND}/${id}/void`, params: { reason } });

/** 手动重试 ERP 同步 */
export const resyncInbound = (id: string) => defHttp.post({ url: `${INBOUND}/${id}/sync` });

/** 检验任务分页 */
export const listInspectionTasks = (params?) => defHttp.get({ url: `${INSPECTION}/list`, params });

/** 提交检验结果 */
export const completeInspection = (id: string, params) =>
  defHttp.post({ url: `${INSPECTION}/${id}/complete`, params });
