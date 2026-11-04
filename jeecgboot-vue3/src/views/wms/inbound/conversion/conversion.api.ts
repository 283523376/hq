import { defHttp } from '/@/utils/http/axios';

/** 接口前缀（建单/完成/取消/分页共用 /wms/conversion） */
const CONVERSION = '/wms/conversion';

/** 建单（入参与后端 `WmsConversionCreateDTO` 逐字一致） */
export const createConversion = (params) =>
  defHttp.post({ url: CONVERSION, params }, { successMessageMode: 'none' });

/**
 * 完成改制（DRAFT→DONE，生成生产入库单）。
 *
 * 重复完成由后端按 `status='DRAFT'` 条件更新拒绝并返回文案，**不要吞掉错误**——
 * defHttp 会把后端 message 原样弹出（成功提示由页面自己给，避免两条绿条）。
 */
export const completeConversion = (id: string) =>
  defHttp.post({ url: `${CONVERSION}/${id}/complete` }, { successMessageMode: 'none' });

/** 取消改制（DRAFT→CANCELLED） */
export const cancelConversion = (id: string) =>
  defHttp.post({ url: `${CONVERSION}/${id}/cancel` }, { successMessageMode: 'none' });

/** 分页列表（conversionNo / productionOrderNo / status） */
export const pageConversion = (params?) => defHttp.get({ url: `${CONVERSION}/page`, params });

/** 生产订单远程搜索（建单下拉：必须是已同步到 WMS 且已下达的工单） */
export const searchWorkOrders = (keyword?: string, pageSize = 50) => {
  const params: any = { pageNo: 1, pageSize };
  if (keyword && keyword.trim()) {
    params.keyword = keyword.trim();
  }
  return defHttp.get({ url: '/wms/work-order/list', params });
};

/** 物料远程搜索（投入/产出物料按编码选择，入参用的是物料编码而非 ID） */
export const searchMaterialOptions = (keyword?: string, pageSize = 50) => {
  const params: any = { pageNo: 1, pageSize };
  if (keyword && keyword.trim()) {
    params.keyword = keyword.trim();
  }
  return defHttp.get({ url: '/wms/material/list', params });
};
