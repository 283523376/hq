import { defHttp } from '/@/utils/http/axios';

const BASE = '/wms/stocktake';

export const listStocktake = (params) => defHttp.get({ url: `${BASE}/list`, params });
export const stocktakeDetails = (id: string) => defHttp.get({ url: `${BASE}/details`, params: { id } });
export const createStocktake = (params) => defHttp.post({ url: `${BASE}/create`, params });
export const startStocktake = (id: string) =>
  defHttp.post({ url: `${BASE}/start`, params: { id } }, { joinParamsToUrl: true });
export const recordActual = (id: string, actuals) =>
  // id 放路径、actuals 数组作为请求体：vben 的 post 第二参是 RequestOptions，传不进 body
  defHttp.post({ url: `${BASE}/recordActual/${id}`, params: actuals });
export const confirmDiff = (id: string) =>
  defHttp.post({ url: `${BASE}/confirmDiff`, params: { id } }, { joinParamsToUrl: true });
// 复核人由后端取当前登录用户，前端不再传
export const reviewStocktake = (id: string) =>
  defHttp.post({ url: `${BASE}/review`, params: { id } }, { joinParamsToUrl: true });
export const adjustStocktake = (id: string) =>
  defHttp.post({ url: `${BASE}/adjust`, params: { id } }, { joinParamsToUrl: true });
export const writebackStocktake = (id: string) =>
  defHttp.post({ url: `${BASE}/writeback`, params: { id } }, { joinParamsToUrl: true });
export const archiveStocktake = (id: string) =>
  defHttp.post({ url: `${BASE}/archive`, params: { id } }, { joinParamsToUrl: true });
