import { defHttp } from '/@/utils/http/axios';

enum Api {
  list = '/wms/location/list',
  add = '/wms/location/add',
  edit = '/wms/location/edit',
  generate = '/wms/location/generate',
  updateAttr = '/wms/location/updateAttr',
  release = '/wms/location/release',
  deleteOne = '/wms/location/delete',
  deleteBatch = '/wms/location/deleteBatch',
  checkCode = '/wms/location/checkCode',
}

export const list = (params?) => defHttp.get({ url: Api.list, params });

export const checkLocationCode = (params) => defHttp.get({ url: Api.checkCode, params }, { isTransformResponse: false });

/**
 * 批量生成库位。返回 `{ generated, skipped }`。
 *
 * 注意 `skipped` 是「已存在被跳过」而不是失败 —— 靠它才有幂等续做。
 * 调用方必须把两个数都显示出来，否则重跑后看到「生成了 0 个」会被误读为失败。
 */
export const generateLocations = (params) => defHttp.post({ url: Api.generate, params });

export const updateLocationAttr = (params) => defHttp.post({ url: Api.updateAttr, params });

export const releaseLocation = (params) => defHttp.post({ url: Api.release, params }, { joinParamsToUrl: true });

export const saveOrUpdateLocation = (params, isUpdate) => defHttp.post({ url: isUpdate ? Api.edit : Api.add, params });

export const deleteLocation = (params, handleSuccess) =>
  defHttp.delete({ url: Api.deleteOne, params }, { joinParamsToUrl: true }).then(() => handleSuccess());

export const batchDeleteLocation = (params, handleSuccess) =>
  defHttp.delete({ url: Api.deleteBatch, params }, { joinParamsToUrl: true }).then(() => handleSuccess());