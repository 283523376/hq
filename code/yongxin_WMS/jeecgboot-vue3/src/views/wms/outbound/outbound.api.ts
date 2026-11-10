import { defHttp } from '/@/utils/http/axios';

/** 接口前缀（出库单各动作共用 /wms/outbound） */
const OUTBOUND = '/wms/outbound';

/** 外协出库：从金蝶同步已审核委外订单为只读草稿（本期不含明细） */
export const syncSubcontractOrders = (top = 50) =>
  // 关闭默认成功提示：后端「跳过」也是 code=200，否则会与页面的黄色警告同时弹出
  defHttp.post({ url: `${OUTBOUND}/sync-subcontract`, params: { top } }, { joinParamsToUrl: true, successMessageMode: 'none' });

/** 样品出库：创建样品出库单 */
export const createSample = (params) => defHttp.post({ url: `${OUTBOUND}/sample`, params });

/** CK-001 扫码校验工单并调出需求明细 */
export const scanPick = (workOrderNo: string) =>
  defHttp.get({ url: `${OUTBOUND}/pick/scan`, params: { workOrderNo } });

/** CK-001 创建领料出库单 */
export const createPick = (params) => defHttp.post({ url: `${OUTBOUND}/pick`, params });

/**
 * CK-001 取消领料（body `{reason}`）。
 *
 * 只对「生产领料 + 草稿/待分配/已分配/异常待人工」可用，其余状态由后端状态机拒绝。
 */
export const cancelPick = (id: string, params: { reason?: string }) =>
  defHttp.post({ url: `${OUTBOUND}/pick/${id}/cancel`, params }, { successMessageMode: 'none' });

/** 设备下拉（领料可指定设备，可空；按编码/名称远程搜索） */
export const listDeviceOptions = (keyword?: string, pageSize = 100) => {
  const params: any = { pageNo: 1, pageSize };
  if (keyword && keyword.trim()) {
    params.keyword = keyword.trim();
  }
  return defHttp.get({ url: '/wms/device/list', params });
};

/** 按金蝶用料清单自动生成领料待办（出库单反向生成）；同步类接口不弹默认绿条 */
export const generatePickDemands = () =>
  defHttp.post({ url: `${OUTBOUND}/generate-pick-demands` }, { successMessageMode: 'none' });

/** 按金蝶发货通知单生成销售出库待办（仅已审核 C；仓库映射未配置时全部跳过） */
export const generateSalesDemands = () =>
  defHttp.post({ url: `${OUTBOUND}/generate-sales-demands` }, { successMessageMode: 'none' });

/** CK-002 创建销售订单与出库单；保留原始报文以便展示后端「销售订单同步失败」文案 */
export const createSales = (params) =>
  defHttp.post({ url: `${OUTBOUND}/sales`, params }, { isTransformResponse: false });

/** 出库单分页 */
export const listOutbound = (params?) => defHttp.get({ url: `${OUTBOUND}/list`, params });

/** 出库单详情（主表 + 需求明细 + 分配明细） */
export const getOutboundDetail = (id: string) => defHttp.get({ url: `${OUTBOUND}/${id}` });

/** 分配库位/料箱 */
export const allocateOutbound = (id: string) => defHttp.post({ url: `${OUTBOUND}/${id}/allocate` });

/** 下发 WCS/RMS */
export const dispatchOutbound = (id: string) => defHttp.post({ url: `${OUTBOUND}/${id}/dispatch` });

/** 复核（CK-002） */
export const reviewOutbound = (id: string, params) =>
  defHttp.post({ url: `${OUTBOUND}/${id}/review`, params });

/** 取料确认（CK-001） */
export const confirmPick = (id: string, params) =>
  defHttp.post({ url: `${OUTBOUND}/${id}/confirm-pick`, params });

/** 确认出库（CK-002） */
export const confirmOutbound = (id: string, params) =>
  defHttp.post({ url: `${OUTBOUND}/${id}/confirm`, params });

/** 手工重新同步出库单 */
export const syncOutbound = (id: string) => defHttp.post({ url: `${OUTBOUND}/${id}/sync` });

/** CK-002：预览金蝶销售订单（含分录与剩余可出数量） */
export const previewSalesOrder = (no: string) => defHttp.get({ url: `${OUTBOUND}/sales-order`, params: { no } });

/** CK-002：拉取金蝶销售订单（按单号或日期窗口） */
export const syncSalesOrder = (params) => defHttp.post({ url: `${OUTBOUND}/sales-order/sync`, params });

/** 关单 */
export const closeOutbound = (id: string, params) =>
  defHttp.post({ url: `${OUTBOUND}/${id}/close`, params });

/** 物料远程搜索（手工录入领料明细/销售明细用） */
export const listMaterialOptions = (keyword?: string, pageSize = 50) => {
  const params: any = { pageNo: 1, pageSize };
  if (keyword && keyword.trim()) {
    // QueryGenerator 约定：值两侧带 * 表示 LIKE
    params.materialName = `*${keyword.trim()}*`;
  }
  return defHttp.get({ url: '/wms/material/list', params });
};
