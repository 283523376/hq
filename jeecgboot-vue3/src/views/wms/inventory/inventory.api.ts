import { defHttp } from '/@/utils/http/axios';

enum Api {
  batchStock = '/wms/inventory/batch/stock',
  batchFlow = '/wms/inventory/batch/flow',
}

/** 批次当前库存明细 */
export const getBatchStock = (batchNo: string) =>
  defHttp.get({ url: Api.batchStock, params: { batchNo } });

/** 批次流转记录（时间倒序） */
export const getBatchFlow = (batchNo: string, limit = 200) =>
  defHttp.get({ url: Api.batchFlow, params: { batchNo, limit } });
