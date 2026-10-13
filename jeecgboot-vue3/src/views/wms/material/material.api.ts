import { defHttp } from '/@/utils/http/axios';

enum Api {
  list = '/wms/material/list',
  add = '/wms/material/add',
  edit = '/wms/material/edit',
  disable = '/wms/material/disable',
  deleteOne = '/wms/material/delete',
  deleteBatch = '/wms/material/deleteBatch',
  checkCode = '/wms/material/checkCode',
  importExcel = '/wms/material/importExcel',
  exportXls = '/wms/material/exportXls',
}

export const getImportUrl = Api.importExcel;
export const getExportUrl = Api.exportXls;

export const list = (params?) => defHttp.get({ url: Api.list, params });

/** 物料编码唯一性校验：返回原始报文以便读取 message */
export const checkMaterialCode = (params) => defHttp.get({ url: Api.checkCode, params }, { isTransformResponse: false });

export const saveOrUpdateMaterial = (params, isUpdate) => {
  let url = isUpdate ? Api.edit : Api.add;
  return defHttp.post({ url: url, params });
};

export const disableMaterial = (params) => defHttp.post({ url: Api.disable, params }, { joinParamsToUrl: true });

export const deleteMaterial = (params, handleSuccess) => {
  return defHttp.delete({ url: Api.deleteOne, params }, { joinParamsToUrl: true }).then(() => {
    handleSuccess();
  });
};

export const batchDeleteMaterial = (params, handleSuccess) => {
  return defHttp.delete({ url: Api.deleteBatch, params }, { joinParamsToUrl: true }).then(() => {
    handleSuccess();
  });
};
