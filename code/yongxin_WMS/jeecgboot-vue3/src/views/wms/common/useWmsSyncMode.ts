import { computed, onMounted, ref } from 'vue';
import { listGroupConfig } from '../systemConfig/systemConfig.api';

/**
 * 「自动同步」开关（系统配置 `sync.auto`）。
 *
 * <p>关闭后后台定时任务（回写补偿、定时拉取）本轮直接跳过，业务单据会停在「待同步」，
 * 需要页面上提供**手工同步**入口。读不到配置时按「自动同步」处理——
 * 宁可少显示一个按钮，也不要因为一次读配置失败把页面搞挂。
 */
export function useWmsSyncMode() {
  const autoSync = ref(true);
  const loaded = ref(false);

  onMounted(async () => {
    try {
      const res: any = await listGroupConfig({ group: 'sync' });
      const items = Array.isArray(res) ? res : res?.result ?? [];
      const item = (items || []).find((i: any) => i?.key === 'sync.auto');
      if (item) {
        autoSync.value = String(item.value) === 'true';
      }
    } catch (e) {
      // 忽略：配置读不到时按自动同步处理
    } finally {
      loaded.value = true;
    }
  });

  /** true = 自动同步已关闭，页面要提供手工同步按钮 */
  const manualOnly = computed(() => !autoSync.value);

  return { autoSync, loaded, manualOnly };
}
