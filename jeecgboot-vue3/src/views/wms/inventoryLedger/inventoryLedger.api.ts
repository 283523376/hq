import { defHttp } from '/@/utils/http/axios';

enum Api {
  page = '/wms/inventoryLedger/page',
  exportTask = '/wms/inventoryLedger/export',
  taskPage = '/wms/inventoryLedger/export/task/page',
  taskBase = '/wms/inventoryLedger/export/task',
  warehouseList = '/wms/warehouse/list',
}

/** 库存台账分页查询 */
export const queryLedger = (params) => defHttp.get({ url: Api.page, params });

/** 提交导出任务（返回 taskId） */
export const createExportTask = (params) => defHttp.post({ url: Api.exportTask, params });

/** 我的导出任务分页 */
export const queryTaskPage = (params) => defHttp.get({ url: Api.taskPage, params });

/** 重试失败任务 */
export const retryTask = (id: string) => defHttp.post({ url: `${Api.taskBase}/${id}/retry` });

/** 删除任务（连文件一起删） */
export const deleteTask = (id: string) =>
  defHttp.delete({ url: `${Api.taskBase}/${id}` }, { joinParamsToUrl: true });

/** 仓库下拉（含停用仓，台账要能看见停用仓里的存量） */
export const listWarehouseOptions = (params) => defHttp.get({ url: Api.warehouseList, params });

/** 受托方下拉（复用启用中的供应商档案，仅 `ownershipType=CONSIGNED` 时参与筛选） */
export const listSupplierOptions = () => defHttp.get({ url: '/wms/supplier/listAll' });
