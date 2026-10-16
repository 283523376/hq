<template>
  <div class="wms-system-config">
    <a-row :gutter="12">
      <!-- 左：分组菜单 -->
      <a-col :xl="6" :lg="8" :md="24">
        <a-card :bordered="false" title="配置分组" size="small">
          <a-menu :selected-keys="[currentGroup]" mode="inline" @click="handleGroupChange">
            <a-menu-item v-for="g in groups" :key="g.code">
              {{ g.name }}
            </a-menu-item>
          </a-menu>
        </a-card>
      </a-col>

      <!-- 右：当前分组表单 -->
      <a-col :xl="18" :lg="16" :md="24">
        <a-card :bordered="false" size="small">
          <template #title>
            <span>{{ currentGroupName }}</span>
            <span v-if="dirty" class="wms-dirty-tip">（有未保存的修改）</span>
          </template>
          <template #extra>
            <a-button
              type="primary"
              v-auth="'wms:systemConfig:save'"
              :loading="saving"
              :disabled="!dirty"
              @click="handleSave"
            >
              保存
            </a-button>
            <a-button
              v-auth="'wms:systemConfig:rollback'"
              :loading="rolling"
              :disabled="!canRollbackCurrent"
              @click="handleRollback"
            >
              回滚上一版本
            </a-button>
          </template>

          <a-spin :spinning="loading">
            <a-form :label-col="{ span: 6 }" :wrapper-col="{ span: 12 }">
              <a-form-item v-for="item in currentItems" :key="item.key" :label="item.label">
                <!-- 布尔 -->
                <a-switch
                  v-if="item.type === 'BOOL'"
                  :checked="formValues[item.key] === 'true'"
                  @change="(v) => (formValues[item.key] = v ? 'true' : 'false')"
                />
                <!-- 多选：逗号分隔值 ↔ 数组 -->
                <a-select
                  v-else-if="item.type === 'MULTI'"
                  mode="multiple"
                  :value="multiValue(item)"
                  :options="(item.options || []).map((o) => ({ label: o, value: o }))"
                  @change="(v) => (formValues[item.key] = (v || []).join(','))"
                />
                <!-- 整数 -->
                <a-input-number
                  v-else-if="item.type === 'INT'"
                  :value="intValue(item)"
                  :min="0"
                  style="width: 100%"
                  @change="(v) => (formValues[item.key] = v === null || v === undefined ? '' : String(v))"
                />
                <!-- 文本 -->
                <a-input
                  v-else
                  :value="formValues[item.key]"
                  @change="(e) => (formValues[item.key] = e.target.value)"
                />
                <div class="wms-item-meta">
                  键：{{ item.key }}
                  <span v-if="item.versionNo > 0">· 当前第 {{ item.versionNo }} 版</span>
                  <span v-else>· 尚未保存过（显示默认值）</span>
                </div>
              </a-form-item>
            </a-form>
          </a-spin>
        </a-card>
      </a-col>
    </a-row>
  </div>
</template>

<script lang="ts" name="wms-system-config" setup>
  import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
  import { onBeforeRouteLeave } from 'vue-router';
  import { Modal } from 'ant-design-vue';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { getDefinitions, saveGroupConfig, rollbackGroupConfig } from './systemConfig.api';

  const { createMessage } = useMessage();

  const loading = ref(false);
  const saving = ref(false);
  const rolling = ref(false);
  /** 分组结构 + 值，全部来自 /definitions */
  const groups = ref<any[]>([]);
  const currentGroup = ref('');
  /** 表单当前值：key -> string（与后端一致，全部按字符串传） */
  const formValues = reactive<Record<string, string>>({});
  /** 上次加载/保存后的快照，用于判断「有未保存的修改」 */
  let snapshot: Record<string, string> = {};

  const currentItems = computed(
    () => groups.value.find((g) => g.code === currentGroup.value)?.items ?? [],
  );
  const currentGroupName = computed(
    () => groups.value.find((g) => g.code === currentGroup.value)?.name ?? '',
  );
  const dirty = computed(() => JSON.stringify(formValues) !== JSON.stringify(snapshot));
  const canRollbackCurrent = computed(() => currentItems.value.some((i: any) => i.canRollback));

  function multiValue(item: any): string[] {
    const raw = formValues[item.key];
    return raw ? raw.split(',').filter((s: string) => s) : [];
  }

  function intValue(item: any): number | undefined {
    const raw = formValues[item.key];
    return raw === '' || raw === undefined ? undefined : Number(raw);
  }

  /** 把某组的项灌进表单并重置快照 */
  function loadGroupIntoForm(items: any[]) {
    for (const key of Object.keys(formValues)) {
      delete formValues[key];
    }
    for (const item of items) {
      formValues[item.key] = item.value ?? '';
    }
    snapshot = JSON.parse(JSON.stringify(formValues));
  }

  async function loadAll() {
    loading.value = true;
    try {
      const res: any = await getDefinitions();
      const list = res?.result ?? res ?? [];
      groups.value = list;
      if (!currentGroup.value && list.length) {
        currentGroup.value = list[0].code;
      }
      const current = list.find((g: any) => g.code === currentGroup.value) ?? list[0];
      if (current) {
        loadGroupIntoForm(current.items);
      }
    } finally {
      loading.value = false;
    }
  }

  onMounted(loadAll);

  /**
   * 浏览器刷新 / 关闭 / 后退时提示未保存的改动。
   *
   * <p>`beforeunload` 只能同步挂一个原生确认框（文案不可自定义，这是浏览器限制），
   * 但它覆盖了「刷新」这条最容易丢数据的路径；页内路由切换由下面 onBeforeRouteLeave 负责。
   */
  function handleBeforeUnload(e: BeforeUnloadEvent) {
    if (dirty.value) {
      e.preventDefault();
      e.returnValue = '';
    }
  }

  onMounted(() => window.addEventListener('beforeunload', handleBeforeUnload));
  onBeforeUnmount(() => window.removeEventListener('beforeunload', handleBeforeUnload));

  /** 离开本页前的确认（返回 false 即取消导航）。 */
  function confirmLeaveInPage(): Promise<boolean> {
    return new Promise((resolve) => {
      Modal.confirm({
        title: '有未保存的修改',
        content: '离开本页会丢失当前修改，确定继续吗？',
        okText: '放弃修改并离开',
        cancelText: '留在本页',
        onOk: () => resolve(true),
        onCancel: () => resolve(false),
      });
    });
  }

  // 切到其它菜单 / 后退时同样要拦：原先只拦了页内左侧分组，刷新与顶部菜单会静默丢改动
  onBeforeRouteLeave(async () => {
    if (!dirty.value) {
      return true;
    }
    return await confirmLeaveInPage();
  });

  /** 切换分组前提示未保存的改动（不能静默丢失） */
  function handleGroupChange(e: any) {
    const next = e?.key;
    if (!next || next === currentGroup.value) {
      return;
    }
    if (dirty.value) {
      Modal.confirm({
        title: '有未保存的修改',
        content: '切换分组会丢失当前修改，确定继续吗？',
        okText: '继续切换',
        cancelText: '留在本页',
        onOk: () => {
          currentGroup.value = next;
          loadGroupIntoForm(currentItems.value);
        },
      });
      return;
    }
    currentGroup.value = next;
    loadGroupIntoForm(currentItems.value);
  }

  function buildPayload() {
    return {
      group: currentGroup.value,
      items: currentItems.value.map((i: any) => ({ key: i.key, value: formValues[i.key] ?? '' })),
    };
  }

  /** 冲突时：固定文案 + 明细列表（明细来自 result.conflicts） */
  function showConflicts(message: string, conflicts: any[]) {
    Modal.error({
      title: message,
      content: conflicts?.length
        ? conflicts.map((c: any) => `${c.name || c.key}：${c.reason}`).join('；')
        : '请检查各项取值的取值范围与相互关系',
    });
  }

  async function handleSave() {
    saving.value = true;
    try {
      const res: any = await saveGroupConfig(buildPayload());
      // isTransformResponse:false → 拿到原始报文，自行判断
      if (res?.success === false) {
        showConflicts(res.message, res.result?.conflicts ?? []);
        return;
      }
      createMessage.success('保存成功');
      await loadAll();
    } finally {
      saving.value = false;
    }
  }

  function handleRollback() {
    Modal.confirm({
      title: '回滚确认',
      content: `将「${currentGroupName.value}」整组回退到上一版本，当前值会被覆盖。确定继续吗？`,
      okText: '确认回滚',
      cancelText: '取消',
      onOk: async () => {
        rolling.value = true;
        try {
          const res: any = await rollbackGroupConfig({ group: currentGroup.value });
          if (res?.success === false) {
            showConflicts(res.message, res.result?.conflicts ?? []);
            return;
          }
          createMessage.success('回滚成功');
          await loadAll();
        } finally {
          rolling.value = false;
        }
      },
    });
  }
</script>

<style lang="less" scoped>
  .wms-dirty-tip {
    margin-left: 8px;
    font-size: 12px;
    font-weight: normal;
    color: #fa8c16;
  }

  .wms-item-meta {
    margin-top: 4px;
    font-size: 12px;
    color: rgba(0, 0, 0, 0.45);
  }
</style>
