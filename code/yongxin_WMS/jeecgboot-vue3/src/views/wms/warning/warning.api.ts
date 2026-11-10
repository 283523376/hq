import { defHttp } from '/@/utils/http/axios';

enum Api {
  ruleList = '/wms/warningRule/list',
  ruleAdd = '/wms/warningRule/add',
  ruleEdit = '/wms/warningRule/edit',
  ruleDelete = '/wms/warningRule/delete',
  warningList = '/wms/inventoryWarning/list',
  pendingCount = '/wms/inventoryWarning/pendingCount',
  handle = '/wms/inventoryWarning/handle',
  scan = '/wms/inventoryWarning/scan',
}

export const listRules = (params) => defHttp.get({ url: Api.ruleList, params });

export const addRule = (params) => defHttp.post({ url: Api.ruleAdd, params });

export const editRule = (params) => defHttp.put({ url: Api.ruleEdit, params });

export const deleteRule = (id: string) =>
  defHttp.delete({ url: Api.ruleDelete, params: { id } }, { joinParamsToUrl: true });

export const listWarnings = (params) => defHttp.get({ url: Api.warningList, params });

export const countPendingWarnings = () => defHttp.get({ url: Api.pendingCount });

export const handleWarning = (params) => defHttp.post({ url: Api.handle, params });

/** 立即扫描：与定时任务同一套逻辑 */
export const scanWarnings = () => defHttp.post({ url: Api.scan });

// 规则表单的下拉数据源（一次性拉前 500 条即可，规则配置是低频操作）
export const listMaterialOptions = (params) => defHttp.get({ url: '/wms/material/list', params });

export const listCategoryOptions = (params) => defHttp.get({ url: '/wms/materialCategory/list', params });

export const listWarehouseOptions = (params) => defHttp.get({ url: '/wms/warehouse/list', params });
