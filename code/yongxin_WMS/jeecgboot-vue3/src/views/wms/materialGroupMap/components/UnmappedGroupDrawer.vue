<template>
  <a-drawer v-model:open="visible" title="从未映射分组认领" width="900" destroy-on-close>
    <a-alert type="info" show-icon style="margin-bottom: 8px" :message="summaryText" />
    <a-alert
      v-if="lastResult"
      type="success"
      show-icon
      closable
      style="margin-bottom: 8px"
      :message="lastResult"
      @close="lastResult = ''"
    />
    <a-alert type="warning" show-icon style="margin-bottom: 12px" message="未认领的分组在物料分组同步时不参与分类，其分类类型保持不变" />
    <div style="margin-bottom: 12px">
      <a-input v-model:value="keyword" placeholder="按分组编码 / 名称过滤（任意连续子串）" allow-clear style="width: 320px" />
    </div>
    <a-table
      :columns="unmappedColumns"
      :data-source="filteredRows"
      :row-selection="rowSelection"
      :loading="loading"
      row-key="categoryId"
      size="small"
      :pagination="{ pageSize: 20, showSizeChanger: true, pageSizeOptions: ['20', '50', '100'], showTotal: (t) => `共 ${t} 组` }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'categoryType'">
          <a-select
            :value="typeByRow[record.categoryId]"
            :options="CATEGORY_TYPE_OPTIONS"
            placeholder="选择分类类型"
            style="width: 150px"
            @change="(v) => (typeByRow[record.categoryId] = v)"
          />
        </template>
        <template v-else-if="column.dataIndex === 'materialCount'">
          <span>{{ record.materialCount }}</span>
        </template>
      </template>
    </a-table>
    <template #footer>
      <a-space>
        <span>已选 {{ selectedRowKeys.length }} 组</span>
        <a-button @click="visible = false">关闭</a-button>
        <a-button
          type="primary"
          :disabled="!selectedRowKeys.length"
          :loading="saving"
          v-auth="['wms:materialGroupMap:create', 'wms:materialGroupMap:edit']"
          @click="handleSave"
        >
          保存所选
        </a-button>
      </a-space>
    </template>
  </a-drawer>
</template>

<script lang="ts" setup>
  import { computed, reactive, ref, watch } from 'vue';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { CATEGORY_TYPE_OPTIONS } from '../materialGroupMap.data';
  import { listUnmappedGroups, saveBatch } from '../materialGroupMap.api';
  import type { UnmappedGroup } from '../materialGroupMap.api';

  const props = defineProps<{ open: boolean }>();
  const emit = defineEmits(['update:open', 'success']);

  const { createMessage } = useMessage();

  const visible = computed({
    get: () => props.open,
    set: (v) => emit('update:open', v),
  });

  const loading = ref(false);
  const saving = ref(false);
  const rows = ref<UnmappedGroup[]>([]);
  const keyword = ref('');
  /** 行勾选（key = categoryId） */
  const selectedRowKeys = ref<string[]>([]);
  /** 每行选中的分类类型（key = categoryId，值 = 原材料/半成品/成品/辅料） */
  const typeByRow = reactive<Record<string, string>>({});
  /** 最近一次保存的计数反馈（任务要求展示 created/updated/unchanged） */
  const lastResult = ref('');

  const unmappedColumns = [
    { title: '分组编码', dataIndex: 'groupNumber', width: 160 },
    { title: '分组名称', dataIndex: 'groupName', ellipsis: true },
    { title: '物料数', dataIndex: 'materialCount', width: 100, align: 'right' as const },
    { title: '分类类型', dataIndex: 'categoryType', width: 170 },
  ];

  const summaryText = computed(() => {
    const materials = rows.value.reduce((sum, r) => sum + Number(r.materialCount || 0), 0);
    return `未映射 ${rows.value.length} 组，覆盖 ${materials} 个物料`;
  });

  const filteredRows = computed(() => {
    const kw = keyword.value.trim().toLowerCase();
    if (!kw) {
      return rows.value;
    }
    return rows.value.filter(
      (r) => (r.groupNumber || '').toLowerCase().includes(kw) || (r.groupName || '').toLowerCase().includes(kw),
    );
  });

  const rowSelection = computed(() => ({
    selectedRowKeys: selectedRowKeys.value,
    onChange: (keys: (string | number)[]) => {
      selectedRowKeys.value = keys.map(String);
    },
  }));

  watch(
    () => props.open,
    (v) => {
      if (v) {
        load();
      }
    },
  );

  async function load() {
    loading.value = true;
    try {
      rows.value = (await listUnmappedGroups()) || [];
      // 打开即重置上一轮的选择痕迹，避免旧勾选混进新一轮认领
      selectedRowKeys.value = [];
      keyword.value = '';
      Object.keys(typeByRow).forEach((k) => delete typeByRow[k]);
    } finally {
      loading.value = false;
    }
  }

  async function handleSave() {
    const checked = rows.value.filter((r) => selectedRowKeys.value.includes(r.categoryId));
    if (!checked.length) {
      createMessage.warning('请先勾选要认领的分组');
      return;
    }
    const missing = checked.filter((r) => !typeByRow[r.categoryId]);
    if (missing.length) {
      createMessage.warning(`还有 ${missing.length} 行未选择分类类型，请补齐后再保存`);
      return;
    }
    saving.value = true;
    try {
      const result = await saveBatch(
        checked.map((r) => ({
          groupNumber: r.groupNumber,
          groupName: r.groupName,
          categoryType: typeByRow[r.categoryId],
        })),
      );
      lastResult.value = `本次保存：新增 ${result.created}、更新 ${result.updated}、无变化 ${result.unchanged}`;
      // 保存成功的全局 toast 由 defHttp 按后端 message 自动弹出，这里只展示计数明细
      // 双刷新：主列表由父组件 success 回调 reload，未映射列表这里重拉
      emit('success');
      await load();
    } finally {
      saving.value = false;
    }
  }
</script>
