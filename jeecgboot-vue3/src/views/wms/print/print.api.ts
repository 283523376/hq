import { defHttp } from '/@/utils/http/axios';

/**
 * 单据打印（DY-001）。
 *
 * 数据只读：WMS 本地单据或金蝶 WebAPI 查询；本模块不写任何账。
 */
enum Api {
  data = '/wms/print/data',
  localBills = '/wms/print/local-bills',
}

/** 取打印数据（分页已由后端切好；快照回退时 snapshotTime 非空） */
export const getPrintData = (params: { billType: string; billNo: string }) =>
  defHttp.get({ url: Api.data, params }, { successMessageMode: 'none' });

/** 本地单号下拉（盘盈亏查盘点单、销售出库查本地出库单；其他类型返回空数组） */
export const getLocalBills = (params: { billType: string; keyword?: string }) =>
  defHttp.get({ url: Api.localBills, params });
