import { defHttp } from '/@/utils/http/axios';

enum Api {
  list = '/wms/customer/list',
  listAll = '/wms/customer/listAll',
  add = '/wms/customer/add',
  edit = '/wms/customer/edit',
  disable = '/wms/customer/disable',
  deleteOne = '/wms/customer/delete',
  deleteBatch = '/wms/customer/deleteBatch',
  checkCode = '/wms/customer/checkCode',
  checkDelete = '/wms/customer/checkDelete',
  checkDeleteBatch = '/wms/customer/checkDeleteBatch',
  exportXls = '/wms/customer/exportXls',
}

/** 导出地址（useListPage 的 exportConfig.url 需要字符串地址） */
export const getExportUrl = Api.exportXls;

/** 删除预检：返回可删性与不可删原因（deletable=false 时 blockers 非空） */
export const checkCustomerDelete = (params: { id: string }) => defHttp.get({ url: Api.checkDelete, params });

/** 批量删除预检：列出不可删明细 */
export const checkCustomerDeleteBatch = (params: { ids: string }) => defHttp.get({ url: Api.checkDeleteBatch, params });

export const list = (params?) => defHttp.get({ url: Api.list, params });

/** 启用中的客户（受托方下拉用） */
export const listAllEnabled = () => defHttp.get({ url: Api.listAll });

/** 客户编码唯一性校验：返回原始报文以便读取 message */
export const checkCustomerCode = (params) => defHttp.get({ url: Api.checkCode, params }, { isTransformResponse: false });

export const saveOrUpdateCustomer = (params, isUpdate) => {
  const url = isUpdate ? Api.edit : Api.add;
  return defHttp.post({ url: url, params });
};

export const disableCustomer = (params) => defHttp.post({ url: Api.disable, params }, { joinParamsToUrl: true });

export const deleteCustomer = (params, handleSuccess) => {
  return defHttp.delete({ url: Api.deleteOne, params }, { joinParamsToUrl: true }).then(() => {
    handleSuccess();
  });
};

export const batchDeleteCustomer = (params, handleSuccess) => {
  return defHttp.delete({ url: Api.deleteBatch, params }, { joinParamsToUrl: true }).then(() => {
    handleSuccess();
  });
};
