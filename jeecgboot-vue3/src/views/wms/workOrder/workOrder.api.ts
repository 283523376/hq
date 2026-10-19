import { defHttp } from '/@/utils/http/axios';

enum Api {
  list = '/wms/work-order/list',
  add = '/wms/work-order/add',
  edit = '/wms/work-order/edit',
  deleteOne = '/wms/work-order',
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
