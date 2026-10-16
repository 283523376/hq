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
  importTemplate = '/wms/material/importTemplate',
  checkDelete = '/wms/material/checkDelete',
  checkDeleteBatch = '/wms/material/checkDeleteBatch',
}

/** 删除预检：物料当前恒可删；未来库存表接入后这里会给出禁止原因 */
export const checkMaterialDelete = (params: { id: string }) => defHttp.get({ url: Api.checkDelete, params });

/** 批量删除预检：列出不可删明细 */
export const checkMaterialDeleteBatch = (params: { ids: string }) => defHttp.get({ url: Api.checkDeleteBatch, params });

export const getImportUrl = Api.importExcel;
export const getExportUrl = Api.exportXls;
export const getTemplateUrl = Api.importTemplate;

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
