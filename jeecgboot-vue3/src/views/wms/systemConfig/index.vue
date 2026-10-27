<template>
  <div class="wms-system-config">
    <a-row :gutter="12">
      <!-- 左：分组菜单 -->
      <a-col :xl="6" :lg="8" :md="24">
        <a-card :bordered="false" title="配置分组" size="small">
          <a-menu
            v-model:open-keys="openKeys"
            :selected-keys="[currentGroup]"
            mode="inline"
            @click="handleGroupChange"
          >
            <a-menu-item v-for="g in groups" :key="g.code">
              {{ g.name }}
            </a-menu-item>
            <!-- 前端本地分组：不来自后端 definitions，切换逻辑与后端分组一致 -->
            <!-- 注意：不能用 <a-sub-menu>，unplugin 自动导入解析不了它（会拍平成顶级项），须显式 Menu.SubMenu -->
            <Menu.SubMenu key="fontScale" title="字体缩放">
              <a-menu-item key="fontScale-text">文字缩放</a-menu-item>
              <a-menu-item key="fontScale-zoom">界面缩放</a-menu-item>
            </Menu.SubMenu>
          </a-menu>
        </a-card>
      </a-col>

      <!-- 右：当前分组表单（字体缩放为前端本地分组，显示专属面板） -->
      <a-col :xl="18" :lg="16" :md="24">
        <FontScalePanel v-if="currentGroup === 'fontScale-text'" type="text" />
        <FontScalePanel v-else-if="currentGroup === 'fontScale-zoom'" type="zoom" />
        <a-card v-else :bordered="false" size="small">
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
                <!-- 单选：候选项由后端按真实库区动态给出（value 存库区编码，label 显示编码+名称） -->
                <a-select
                  v-else-if="item.type === 'SELECT'"
                  :value="formValues[item.key] || undefined"
                  allow-clear
                  placeholder="未配置（沿用废品仓默认库位）"
                  :options="(item.optionItems || []).map((o) => ({ label: o.label, value: o.value, disabled: o.disabled }))"
                  @change="(v) => (formValues[item.key] = v || '')"
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
                <div class="wms-item-meta">键：{{ item.key }}</div>
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
  import { Menu, Modal } from 'ant-design-vue';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { getDefinitions, saveGroupConfig } from './systemConfig.api';
  import FontScalePanel from './fontScale/FontScalePanel.vue';

  const { createMessage } = useMessage();

  /** 左侧菜单展开的子菜单；「字体缩放」默认展开 */
  const openKeys = ref<string[]>(['fontScale']);
  const loading = ref(false);
  const saving = ref(false);
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
