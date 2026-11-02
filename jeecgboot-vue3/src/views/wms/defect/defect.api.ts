import { defHttp } from '/@/utils/http/axios';

enum Api {
  list = '/wms/defect/list',
  scrap = '/wms/defect/scrap',
}

/** 不良品台账（只含废品仓，实时查询） */
export const list = (params?) => defHttp.get({ url: Api.list, params });

/**
 * 报废出库。
 *
 * 返回 `{ outboundNo, scrappedQty, remainingQty, syncSuccess }`。
 * **报废不可逆**：扣减库存并生成「报废出库」单，页面必须先二次确认。
 */
export const scrap = (params) => defHttp.post({ url: Api.scrap, params });
