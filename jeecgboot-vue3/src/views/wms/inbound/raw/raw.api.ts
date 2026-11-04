import { defHttp } from '/@/utils/http/axios';

/** 接口前缀（到货登记与上架共用 /wms/raw-inbound） */
const RAW = '/wms/raw-inbound';

/**
 * 到货登记（外购/受托 × 大改小/段差棒直入）。
 *
 * 入参与后端 `WmsRawInboundDTO` 逐字一致：明细行只有 materialId / quantity / batchNo /
 * sourceMaterialCode，**没有物料编码**——编码由服务端按主数据回填。
 */
export const receiveRawInbound = (params) =>
  // 页面要按处理方式拼不同的成功文案，关掉默认绿条避免两条提示同时弹
  defHttp.post({ url: `${RAW}/receive`, params }, { successMessageMode: 'none' });

/** 上架（仅 DIRECT 单；入参为 `WmsPutawayDTO`） */
export const putawayRawInbound = (id: string, params) =>
  defHttp.post({ url: `${RAW}/${id}/putaway`, params }, { successMessageMode: 'none' });

/**
 * 入库单分页。
 *
 * 原材料入库与历史采购入库同表（`wms_inbound_order`），后端没有独立的原材料分页端点，
 * 这里复用 `/wms/inbound/list`，前端按 `sourceType` / `inboundType` 区分来源与处理方式。
 */
export const listRawInbound = (params?) => defHttp.get({ url: '/wms/inbound/list', params });

/** 单据详情（单据 + 明细 + 检验任务），上架弹层取明细用 */
export const getRawInboundDetail = (id: string) => defHttp.get({ url: `/wms/inbound/${id}` });

/** 空闲库位（按物料推导仓库类型） */
export const listFreeLocations = (params) => defHttp.get({ url: '/wms/inbound/free-locations', params });

/** 启用中的供应商档案（受托方复用供应商档案，用户已确认） */
export const listSupplierOptions = () => defHttp.get({ url: '/wms/supplier/listAll' });

/**
 * 物料远程搜索（到货明细选择用）。
 *
 * 明细入参要的是 `materialId`，所以下拉的 value 必须是物料主键；label 带编码便于扫码核对。
 */
export const searchMaterialOptions = (keyword?: string, pageSize = 50) => {
  const params: any = { pageNo: 1, pageSize };
  if (keyword && keyword.trim()) {
    // QueryGenerator 约定：值两侧带 * 表示 LIKE（与领料明细的物料搜索一致）
    params.materialName = `*${keyword.trim()}*`;
  }
  return defHttp.get({ url: '/wms/material/list', params });
};
