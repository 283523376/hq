import { defHttp } from '/@/utils/http/axios';

enum Api {
  treeList = '/wms/materialCategory/treeList',
  list = '/wms/materialCategory/list',
  queryById = '/wms/materialCategory/queryById',
  add = '/wms/materialCategory/add',
  edit = '/wms/materialCategory/edit',
  disable = '/wms/materialCategory/disable',
  deleteOne = '/wms/materialCategory/delete',
  checkCode = '/wms/materialCategory/checkCode',
}

/** 分类树（全量，含 children） */
export const queryTreeList = (params?) => defHttp.get({ url: Api.treeList, params });

/** 按 id 查分类（编辑回显用） */
export const queryCategoryById = (params) => defHttp.get({ url: Api.queryById, params }, { isTransformResponse: false });

/** 分类编码唯一性校验：返回原始报文以便读取 message */
export const checkCategoryCode = (params) => defHttp.get({ url: Api.checkCode, params }, { isTransformResponse: false });

export const saveOrUpdateCategory = (params, isUpdate) => {
  let url = isUpdate ? Api.edit : Api.add;
  return defHttp.post({ url: url, params });
};

/** 停用 */
export const disableCategory = (params) => defHttp.post({ url: Api.disable, params }, { joinParamsToUrl: true });

export const deleteCategory = (params, handleSuccess) => {
  return defHttp.delete({ url: Api.deleteOne, params }, { joinParamsToUrl: true }).then(() => {
    handleSuccess();
  });
};
