import { defHttp } from '/@/utils/http/axios';

enum Api {
  list = '/wms/defect/list',
  scrap = '/wms/defect/scrap',
  inbound = '/wms/defect/inbound',
  returnToConsignor = '/wms/defect/return',
  records = '/wms/defect/records',
}

/**
 * 不良品台账（只含废品仓，实时查询）。
 *
 * 支持 `ownershipType`（OWNED 默认 / CONSIGNED）与 `consignorId`：
 * 受托不良入废品仓后仍记受托所有权，不显式传 CONSIGNED 就看不到这些行。
 */
export const list = (params?) => defHttp.get({ url: Api.list, params });

/**
 * 受托方下拉（V2.4：受托方与金蝶一致，走**客户档案**；仅库存类型=受托时参与筛选）。
 */
export const listCustomerOptions = () => defHttp.get({ url: '/wms/customer/listAll' });

/**
 * 报废出库。
 *
 * 返回 `{ outboundNo, scrappedQty, remainingQty, syncSuccess }`。
 * **报废不可逆**：扣减库存并生成「报废出库」单，页面必须先二次确认。
 */
export const scrap = (params) => defHttp.post({ url: Api.scrap, params });

/** 不良品入库登记（入参与 `WmsDefectInboundDTO` 逐字一致，物料只传 materialId） */
export const recordInbound = (params) =>
  defHttp.post({ url: Api.inbound, params }, { successMessageMode: 'none' });

/**
 * 受托不良退货（生成采购退货单）。
 *
 * 返回更新后的记录：**`returnBillNo` 可能为空**——金蝶侧已存在同来源单据时后端只置
 * 「已退货」并留空单号等人工回填，页面必须把这种情况提示清楚，不能显示成失败。
 * 关闭默认成功提示，改由页面按返回内容给不同文案。
 */
export const returnToConsignor = (params) =>
  defHttp.post({ url: Api.returnToConsignor, params }, { successMessageMode: 'none' });

/**
 * 不良品记录分页。
 *
 * 后端该方法直接绑实体，只认 `materialCode` / `sourceBillNo` / `sourceType` /
 * `disposalType` / `status` 这些实体字段。**不要传 createTime/updateTime**——
 * 时间字段没有 @DateTimeFormat，传了会 400。
 */
export const listRecords = (params) => defHttp.get({ url: Api.records, params });

/** 物料远程搜索（不良品登记按物料 ID 提交） */
export const searchMaterialOptions = (keyword?: string, pageSize = 50) => {
  const params: any = { pageNo: 1, pageSize };
  if (keyword && keyword.trim()) {
    params.materialName = `*${keyword.trim()}*`;
  }
  return defHttp.get({ url: '/wms/material/list', params });
};
