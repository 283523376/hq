import { defHttp } from '/@/utils/http/axios';

/**
 * 单据打印（DY-001）。
 *
 * 数据只读：WMS 本地单据或金蝶 WebAPI 查询；本模块不写任何账。
 */
enum Api {
  data = '/wms/print/data',
  localBills = '/wms/print/local-bills',
  bills = '/wms/print/bills',
}

/** 工作台单据列表：默认全部可打印单据，单号模糊，每页 20 条 */
export const getBills = (params: { billType: string; keyword?: string; pageNo: number; pageSize: number }) =>
  defHttp.get({ url: Api.bills, params });

/**
 * 取打印数据（分页已由后端切好；快照回退时 snapshotTime 非空）。
 *
 * 不转换响应：业务失败（如「单据不存在或未审核」）时 defHttp 默认不 reject，
 * 页面会「点了没反应」——这里拿完整 Result，由调用方按 success/message 处理。
 */
export const getPrintData = (params: { billType: string; billNo: string }) =>
  defHttp.get({ url: Api.data, params }, { isTransformResponse: false, errorMessageMode: 'none' });

/** 本地单号下拉（盘盈亏查盘点单、销售出库查本地出库单；其他类型返回空数组） */
export const getLocalBills = (params: { billType: string; keyword?: string }) =>
  defHttp.get({ url: Api.localBills, params });
