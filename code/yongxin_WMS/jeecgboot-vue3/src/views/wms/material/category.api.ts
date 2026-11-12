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
  checkDelete = '/wms/materialCategory/checkDelete',
  syncKingdee = '/wms/materialCategory/sync-kingdee',
  syncMaterialGroups = '/wms/materialCategory/sync-from-kingdee',
}

/**
 * 全量同步类接口的超时时间：一次要拉 4.6 万物料的全部页，实测 ~30s，
 * 全局默认 10s 必然报「接口请求超时」（2026-11-12 实测）。
 */
export const SYNC_TIMEOUT_MS = 180 * 1000;

/** 从金蝶同步物料分组（建/复用分类并补写分组映射）；合并后这是分类唯一的数据来源 */
export const syncMaterialGroupsFromKingdee = () =>
  defHttp.post({ url: Api.syncMaterialGroups, timeout: SYNC_TIMEOUT_MS }, { successMessageMode: 'none' });

/** 从金蝶同步物料分组映射（未配置 category-map 时后端仅空转） */
export const syncCategoryFromKingdee = () =>
  // 同上：同步类接口由页面自己决定提示文案
  defHttp.post({ url: Api.syncKingdee, timeout: SYNC_TIMEOUT_MS }, { successMessageMode: 'none' });

/** 删除预检：分类下有物料/子分类时返回不可删原因（树形单删，无批量端点） */
export const checkCategoryDelete = (params: { id: string }) => defHttp.get({ url: Api.checkDelete, params });

/** 分类树（全量，含 children） */
export const queryTreeList = (params?) => defHttp.get({ url: Api.treeList, params });

/** 按 id 查分类（编辑回显用） */
export const queryCategoryById = (params) => defHttp.get({ url: Api.queryById, params }, { isTransformResponse: false });

/** 分类编码唯一性校验：返回原始报文以便读取 message */
export const checkCategoryCode = (params) => defHttp.get({ url: Api.checkCode, params }, { isTransformResponse: false });

export const saveOrUpdateCategory = (params, isUpdate) => {
  const url = isUpdate ? Api.edit : Api.add;
  return defHttp.post({ url: url, params });
};

/** 停用 */
export const disableCategory = (params) => defHttp.post({ url: Api.disable, params }, { joinParamsToUrl: true });

export const deleteCategory = (params, handleSuccess) => {
  return defHttp.delete({ url: Api.deleteOne, params }, { joinParamsToUrl: true }).then(() => {
    handleSuccess();
  });
};
