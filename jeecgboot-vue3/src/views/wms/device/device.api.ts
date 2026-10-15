import { defHttp } from '/@/utils/http/axios';

enum Api {
  list = '/wms/device/list',
  listUnbound = '/wms/device/listUnbound',
  add = '/wms/device/add',
  edit = '/wms/device/edit',
  bind = '/wms/device/bind',
  unbind = '/wms/device/unbind',
  reportStatus = '/wms/device/reportStatus',
  deleteOne = '/wms/device/delete',
  deleteBatch = '/wms/device/deleteBatch',
  checkCode = '/wms/device/checkCode',
}

export const list = (params?) => defHttp.get({ url: Api.list, params });

/** 未归属库区的设备精简列表 */
export const listUnbound = (params?) => defHttp.get({ url: Api.listUnbound, params });

export const checkDeviceCode = (params) => defHttp.get({ url: Api.checkCode, params }, { isTransformResponse: false });

export const saveOrUpdateDevice = (params, isUpdate) => defHttp.post({ url: isUpdate ? Api.edit : Api.add, params });

/**
 * 绑定设备到库区。
 *
 * 返回 `{ bound, movedDevices, offlineDevices, warning }`。
 * **绑定成功与告警是两件事**：离线设备照样绑定成功，只体现在 warning 里。
 */
export const bindDevices = (params) => defHttp.post({ url: Api.bind, params });

export const unbindDevices = (params) => defHttp.post({ url: Api.unbind, params });

export const reportDeviceStatus = (params) => defHttp.post({ url: Api.reportStatus, params });

export const deleteDevice = (params, handleSuccess) =>
  defHttp.delete({ url: Api.deleteOne, params }, { joinParamsToUrl: true }).then(() => handleSuccess());

export const batchDeleteDevice = (params, handleSuccess) =>
  defHttp.delete({ url: Api.deleteBatch, params }, { joinParamsToUrl: true }).then(() => handleSuccess());