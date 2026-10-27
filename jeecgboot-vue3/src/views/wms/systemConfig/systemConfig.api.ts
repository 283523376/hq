import { defHttp } from '/@/utils/http/axios';

enum Api {
  definitions = '/wms/systemConfig/definitions',
  list = '/wms/systemConfig/list',
  save = '/wms/systemConfig/save',
  history = '/wms/systemConfig/history',
}

/** 分组结构 + 全部组的值（页面首载唯一入口） */
export const getDefinitions = () => defHttp.get({ url: Api.definitions });

/** 单组刷新 */
export const listGroupConfig = (params: { group: string }) => defHttp.get({ url: Api.list, params });

/**
 * 保存某组。返回原始报文以便读取冲突明细：
 * 冲突时 success=false、message 为固定文案「配置项存在冲突，请检查」、result.conflicts 为原因列表。
 */
export const saveGroupConfig = (params) =>
  defHttp.post({ url: Api.save, params }, { isTransformResponse: false });

/** 版本流水（留痕可查证） */
export const listConfigHistory = (params: { key: string }) => defHttp.get({ url: Api.history, params });
