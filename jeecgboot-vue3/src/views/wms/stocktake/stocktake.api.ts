import { defHttp } from '/@/utils/http/axios';

const BASE = '/wms/stocktake';

export const listStocktake = (params) => defHttp.get({ url: `${BASE}/list`, params });
export const stocktakeDetails = (id: string) => defHttp.get({ url: `${BASE}/details`, params: { id } });
export const createStocktake = (params) => defHttp.post({ url: `${BASE}/create`, params });
export const startStocktake = (id: string) =>
  defHttp.post({ url: `${BASE}/start`, params: { id } }, { joinParamsToUrl: true });
export const recordActual = (id: string, actuals) =>
  defHttp.post({ url: `${BASE}/recordActual`, params: { id } }, { params: actuals, joinParamsToUrl: false });
export const confirmDiff = (id: string) =>
  defHttp.post({ url: `${BASE}/confirmDiff`, params: { id } }, { joinParamsToUrl: true });
export const reviewStocktake = (id: string, reviewer: string) =>
  defHttp.post({ url: `${BASE}/review`, params: { id, reviewer } }, { joinParamsToUrl: true });
export const adjustStocktake = (id: string) =>
  defHttp.post({ url: `${BASE}/adjust`, params: { id } }, { joinParamsToUrl: true });
export const writebackStocktake = (id: string) =>
  defHttp.post({ url: `${BASE}/writeback`, params: { id } }, { joinParamsToUrl: true });
export const archiveStocktake = (id: string) =>
  defHttp.post({ url: `${BASE}/archive`, params: { id } }, { joinParamsToUrl: true });
