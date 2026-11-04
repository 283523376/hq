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

/**
 * 上架（仅 DIRECT 单；入参为 `WmsPutawayDTO`）。
 *
 * 返回 `{ syncStatus, erpBillNo, lastSyncError }`：**库存入账与金蝶回写是两个结果**，
 * 回写失败不会抛错（库存已入账）。页面必须按返回值分支提示，不能写死「已触发回写」。
 */
export const putawayRawInbound = (id: string, params) =>
  defHttp.post({ url: `${RAW}/${id}/putaway`, params }, { successMessageMode: 'none' });

/**
 * 重试回写（补偿入口）。
 *
 * 只对「已审核」且非大改小的单有效；已有金蝶单号的只补提交，已同步成功的幂等返回。
 * 返回与上架同款的 `{ syncStatus, erpBillNo, lastSyncError }`。
 */
export const resyncRawInbound = (id: string) =>
  defHttp.post({ url: `${RAW}/${id}/resync` }, { successMessageMode: 'none' });

/**
 * 原材料入库单分页（`source_type` 为 PURCHASE/CONSIGNED 的单据）。
 *
 * 不用 `/wms/inbound/list`：那是全量入库单，会混入生产入库与历史采购收料单，
 * 而那些单的 `source_type` 为空，前端只能靠状态猜「处理方式」，会误判成大改小。
 */
export const listRawInbound = (params?) => defHttp.get({ url: `${RAW}/page`, params });

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
