import { defHttp } from '/@/utils/http/axios';

enum Api {
  list = '/wms/warehouse/list',
  treeList = '/wms/warehouse/treeList',
  add = '/wms/warehouse/add',
  edit = '/wms/warehouse/edit',
  disable = '/wms/warehouse/disable',
  deleteOne = '/wms/warehouse/delete',
  deleteBatch = '/wms/warehouse/deleteBatch',
  checkCode = '/wms/warehouse/checkCode',
  queryById = '/wms/warehouse/queryById',
  outOfEnvelopeCount = '/wms/warehouse/outOfEnvelopeCount',
}

export const list = (params?) => defHttp.get({ url: Api.list, params });

/** 两层库区树：库区类型 → 库区。返回数组（或 {result: [...]}，调用方做兼容） */
export const queryTreeList = (params?) => defHttp.get({ url: Api.treeList, params });

/** 库区详情，含 outOfEnvelopeCount（位于当前维度之外的库位数） */
export const queryById = (params) => defHttp.get({ url: Api.queryById, params });

/** 库区编码唯一性校验：返回原始报文以便读取 message */
export const checkWarehouseCode = (params) => defHttp.get({ url: Api.checkCode, params }, { isTransformResponse: false });

export const saveOrUpdateWarehouse = (params, isUpdate) => defHttp.post({ url: isUpdate ? Api.edit : Api.add, params });

export const disableWarehouse = (params) => defHttp.post({ url: Api.disable, params }, { joinParamsToUrl: true });

export const deleteWarehouse = (params, handleSuccess) =>
  defHttp.delete({ url: Api.deleteOne, params }, { joinParamsToUrl: true }).then(() => handleSuccess());

export const batchDeleteWarehouse = (params, handleSuccess) =>
  defHttp.delete({ url: Api.deleteBatch, params }, { joinParamsToUrl: true }).then(() => handleSuccess());

export const getOutOfEnvelopeCount = (params) => defHttp.get({ url: Api.outOfEnvelopeCount, params });