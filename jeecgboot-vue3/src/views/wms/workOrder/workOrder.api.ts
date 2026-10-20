import { defHttp } from '/@/utils/http/axios';

enum Api {
  list = '/wms/work-order/list',
  add = '/wms/work-order/add',
  edit = '/wms/work-order/edit',
  deleteOne = '/wms/work-order',
  syncKingdee = '/wms/work-order/sync-kingdee',
}

export const listWorkOrder = (params?) => defHttp.get({ url: Api.list, params });

export const saveOrUpdateWorkOrder = (params, isUpdate) => {
  const url = isUpdate ? Api.edit : Api.add;
  return isUpdate ? defHttp.put({ url, params }) : defHttp.post({ url, params });
};

export const deleteWorkOrder = (params, handleSuccess) => {
  return defHttp.delete({ url: `${Api.deleteOne}/${params.id}` }).then(() => {
    handleSuccess();
  });
};

/**
 * 从金蝶 PRD_MO 同步生产工单。
 * params 是查询参数（billNo/top），data 传空对象避免 defHttp 把 params 当请求体。
 */
export const syncKingdeeWorkOrder = (params?: { billNo?: string; top?: number }) => {
  return defHttp.post({ url: Api.syncKingdee, params, data: {} });
};
