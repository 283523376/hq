import { defHttp } from '/@/utils/http/axios';

enum Api {
  list = '/wms/order/list',
  add = '/wms/order/add',
  edit = '/wms/order/edit',
  audit = '/wms/order/audit',
  dispatch = '/wms/order/dispatch',
  resync = '/wms/order/resync',
  deleteOne = '/wms/order/delete',
  deleteBatch = '/wms/order/deleteBatch',
  checkOrderNo = '/wms/order/checkOrderNo',
}

export const list = (params?) => defHttp.get({ url: Api.list, params });

/** 订单号唯一性校验：返回原始报文以便读取 message */
export const checkOrderNo = (params) => defHttp.get({ url: Api.checkOrderNo, params }, { isTransformResponse: false });

export const saveOrUpdateOrder = (params, isUpdate) => {
  let url = isUpdate ? Api.edit : Api.add;
  return defHttp.post({ url: url, params });
};

export const auditOrder = (params) => defHttp.post({ url: Api.audit, params });

/** 下达（内部会触发同步，返回 { synced, syncError, retryCount }） */
export const dispatchOrder = (params) => defHttp.post({ url: Api.dispatch, params }, { joinParamsToUrl: true });

/** 手动重新同步 */
export const resyncOrder = (params) => defHttp.post({ url: Api.resync, params }, { joinParamsToUrl: true });

export const deleteOrder = (params, handleSuccess) => {
  return defHttp.delete({ url: Api.deleteOne, params }, { joinParamsToUrl: true }).then(() => {
    handleSuccess();
  });
};

export const batchDeleteOrder = (params, handleSuccess) => {
  return defHttp.delete({ url: Api.deleteBatch, params }, { joinParamsToUrl: true }).then(() => {
    handleSuccess();
  });
};
