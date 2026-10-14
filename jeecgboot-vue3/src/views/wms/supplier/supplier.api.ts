import { defHttp } from '/@/utils/http/axios';

enum Api {
  list = '/wms/supplier/list',
  listAll = '/wms/supplier/listAll',
  add = '/wms/supplier/add',
  edit = '/wms/supplier/edit',
  disable = '/wms/supplier/disable',
  deleteOne = '/wms/supplier/delete',
  deleteBatch = '/wms/supplier/deleteBatch',
  checkCode = '/wms/supplier/checkCode',
}

export const list = (params?) => defHttp.get({ url: Api.list, params });

/** 启用中的供应商（订单页下拉用） */
export const listAllEnabled = () => defHttp.get({ url: Api.listAll });

/** 供应商编码唯一性校验：返回原始报文以便读取 message */
export const checkSupplierCode = (params) => defHttp.get({ url: Api.checkCode, params }, { isTransformResponse: false });

export const saveOrUpdateSupplier = (params, isUpdate) => {
  let url = isUpdate ? Api.edit : Api.add;
  return defHttp.post({ url: url, params });
};

export const disableSupplier = (params) => defHttp.post({ url: Api.disable, params }, { joinParamsToUrl: true });

export const deleteSupplier = (params, handleSuccess) => {
  return defHttp.delete({ url: Api.deleteOne, params }, { joinParamsToUrl: true }).then(() => {
    handleSuccess();
  });
};

export const batchDeleteSupplier = (params, handleSuccess) => {
  return defHttp.delete({ url: Api.deleteBatch, params }, { joinParamsToUrl: true }).then(() => {
    handleSuccess();
  });
};
