import { defHttp } from '/@/utils/http/axios';

/** 接口前缀：库存调拨（CK-004） */
const TRANSFER = '/wms/transfer';

/** 调拨单分页 */
export const listTransfer = (params?) => defHttp.get({ url: `${TRANSFER}/list`, params });

/** 调拨单详情（主表 + 明细 + 搬运任务 + ERP 等待时长） */
export const getTransferDetail = (id: string) => defHttp.get({ url: `${TRANSFER}/${id}` });

/** 新建调拨单 */
export const createTransfer = (params) => defHttp.post({ url: TRANSFER, params });

/** 分配并下发（同仓走米塔 RMS） */
export const dispatchTransfer = (id: string) => defHttp.post({ url: `${TRANSFER}/${id}/dispatch` });

/** 跨仓出发确认（→在途） */
export const departTransfer = (id: string) => defHttp.post({ url: `${TRANSFER}/${id}/depart` });

/** 跨仓到达确认（→待入库，落暂存库位） */
export const arriveTransfer = (id: string) => defHttp.post({ url: `${TRANSFER}/${id}/arrive` });

/** 跨仓上架确认（→实物完成） */
export const putawayTransfer = (id: string, params?) => defHttp.post({ url: `${TRANSFER}/${id}/putaway`, params });

/** 提交/刷新金蝶调拨单（Phase B） */
export const syncTransferErp = (id: string) => defHttp.post({ url: `${TRANSFER}/${id}/sync-erp` });

/** 生成反向冲销单（Phase B） */
export const reverseTransfer = (id: string) => defHttp.post({ url: `${TRANSFER}/${id}/reverse` });

/** 关单（释放锁定） */
export const closeTransfer = (id: string, reason: string) =>
  defHttp.post({ url: `${TRANSFER}/${id}/close`, params: { reason } });

/** 库区列表（选择调出/调入仓用） */
export const listWarehouse = (params?) => defHttp.get({ url: '/wms/warehouse/list', params });

/** 物料列表（选择调拨物料用） */
export const listMaterial = (params?) => defHttp.get({ url: '/wms/material/list', params });
