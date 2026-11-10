import { h } from 'vue';
import { useMessage } from '/@/hooks/web/useMessage';

const { createConfirm, createErrorModal } = useMessage();

/** 对齐后端 org.jeecg.modules.wms.vo.WmsDeleteCheckVO */
export interface WmsDeleteBlocker {
  message: string;
  count: number;
  samples: string[];
}

export interface WmsDeleteCheck {
  deletable: boolean;
  blockers: WmsDeleteBlocker[];
}

/** 对齐后端 org.jeecg.modules.wms.vo.WmsBatchDeleteCheckVO */
export interface WmsBatchDeleteCheck {
  deletable: boolean;
  blockedItems: { id: string; code: string; reason: string }[];
}

export interface WmsDeleteCheckApis {
  checkDelete: (params: { id: string }) => Promise<WmsDeleteCheck>;
  checkDeleteBatch?: (params: { ids: string }) => Promise<WmsBatchDeleteCheck>;
  remove: (params: { id: string }, handleSuccess: () => void) => Promise<any>;
  removeBatch: (params: { ids: string }, handleSuccess: () => void) => Promise<any>;
}

/** 弹「无法删除」禁止框：blockers 逐条一行（树组件与列表 composable 共用）。 */
export function showDeleteBlocked(blockers: WmsDeleteBlocker[] | undefined, title = '无法删除') {
  const lines = (blockers ?? []).map((b) => {
    // 数量为 1 时「（1）」没有信息量（设备「已绑定库区」、库位「已占用」这类状态型 blocker 尤其如此）
    const count = b.count > 1 ? `（${b.count}）` : '';
    const sample = b.samples?.length ? `：${b.samples.join('、')}` : '';
    return `${b.message}${count}${sample}`;
  });
  createErrorModal({
    title,
    content: lines.length
      ? h('div', null, lines.map((l) => h('div', l)))
      : '该记录存在关联数据，不允许删除',
  });
}

/**
 * WMS 删除守卫统一交互（spec 2026-10-16 §4）：
 * 点删除先预检——可删才弹确认框，不可删弹禁止框列出原因；
 * 后端守卫仍在删除时兜底（预检与删除之间存在并发窗口）。
 */
export function useWmsDeleteCheck(apis: WmsDeleteCheckApis, onSuccess: () => void) {
  async function confirmDelete(record: { id: string }) {
    const check: WmsDeleteCheck = await apis.checkDelete({ id: record.id });
    if (!check?.deletable) {
      showDeleteBlocked(check?.blockers);
      return;
    }
    createConfirm({
      iconType: 'warning',
      title: '确定删除吗?',
      content: '删除后数据保留删除时间戳，可在数据库中恢复',
      onOk: async () => {
        await apis.remove({ id: record.id }, onSuccess);
      },
    });
  }

  async function confirmBatchDelete(ids: string[]) {
    if (!ids.length) {
      return;
    }
    if (apis.checkDeleteBatch) {
      const check = await apis.checkDeleteBatch({ ids: ids.join(',') });
      if (!check?.deletable) {
        const lines = (check?.blockedItems ?? []).map((it) => `${it.code}：${it.reason}`);
        createErrorModal({
          title: '以下记录无法删除',
          content: lines.length
            ? h('div', null, lines.map((l) => h('div', l)))
            : '所选记录存在关联数据，不允许删除',
        });
        return;
      }
    }
    createConfirm({
      iconType: 'warning',
      title: `确定删除选中的 ${ids.length} 条记录吗?`,
      content: '删除后数据保留删除时间戳，可在数据库中恢复',
      onOk: async () => {
        await apis.removeBatch({ ids: ids.join(',') }, onSuccess);
      },
    });
  }

  return { confirmDelete, confirmBatchDelete };
}
