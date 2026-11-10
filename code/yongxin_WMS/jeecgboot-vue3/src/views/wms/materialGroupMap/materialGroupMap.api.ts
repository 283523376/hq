import { defHttp } from '/@/utils/http/axios';

enum Api {
  list = '/wms/materialGroupMap/list',
  listAll = '/wms/materialGroupMap/listAll',
  unmappedGroups = '/wms/materialGroupMap/unmappedGroups',
  saveBatch = '/wms/materialGroupMap/saveBatch',
  base = '/wms/materialGroupMap',
}

/** 映射行（对应后端 WmsMaterialGroupMap 实体） */
export interface MaterialGroupMapItem {
  id?: string;
  /** 金蝶物料分组编码（upsert 幂等键） */
  groupNumber: string;
  /** 金蝶物料分组名称（冗余，同步自动回填） */
  groupName?: string;
  /** 分类类型：原材料/半成品/成品/辅料 */
  categoryType: string;
  remark?: string;
}

/** 批量保存结果（对应后端 WmsMaterialGroupMapSaveResultVO） */
export interface SaveBatchResult {
  created: number;
  updated: number;
  unchanged: number;
  message?: string;
}

/** 未映射分组（对应后端 WmsUnmappedGroupVO，按物料数降序） */
export interface UnmappedGroup {
  categoryId: string;
  groupNumber: string;
  groupName?: string;
  materialCount: number;
}

/** 分页列表：groupNumber/groupName 任意连续子串模糊、categoryType 等值、keyword 跨字段 */
export const list = (params?) => defHttp.get({ url: Api.list, params });

/** 全部映射（下拉/导出用，本页暂未消费，预留） */
export const listAll = () => defHttp.get({ url: Api.listAll });

/** 已同步但尚未映射的分组（按物料数从多到少） */
export const listUnmappedGroups = (): Promise<UnmappedGroup[]> => defHttp.get({ url: Api.unmappedGroups });

/**
 * 批量 upsert：按分组编码幂等。
 * 分类类型非法时后端整批拒绝（文案含「第 N 行」），defHttp 会把 message 原样弹出，前端不吞。
 */
export const saveBatch = (params: MaterialGroupMapItem[]): Promise<SaveBatchResult> =>
  defHttp.post({ url: Api.saveBatch, params });

/** 删除映射（逻辑删除；删除后该分组回到「未映射分组」列表） */
export const deleteOne = (id: string) => defHttp.delete({ url: `${Api.base}/${id}` });
