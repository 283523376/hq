<template>
  <div class="wms-warning">
    <a-tabs v-model:activeKey="activeTab" @change="handleTabChange">
      <a-tab-pane key="warning" :tab="warningTabTitle">
        <BasicTable @register="registerWarningTable">
          <template #tableTitle>
            <a-button type="primary" preIcon="ant-design:sync-outlined" v-auth="'wms:warning:scan'" :loading="scanning" @click="handleScan">
              立即扫描
            </a-button>
          </template>
          <template #action="{ record }">
            <TableAction :actions="warningActions(record)" />
          </template>
        </BasicTable>
      </a-tab-pane>

      <a-tab-pane key="rule" tab="规则配置">
        <BasicTable @register="registerRuleTable">
          <template #tableTitle>
            <a-button type="primary" preIcon="ant-design:plus-outlined" v-auth="'wms:warning:add'" @click="openRule()">
              新建规则
            </a-button>
          </template>
          <template #bodyCell="{ column, record }">
            <template v-if="column.dataIndex === 'materialName'">
              {{ scopeTargetLabel(record) }}
            </template>
            <template v-else-if="column.dataIndex === 'warehouseName'">
              {{ warehouseLabel(record.warehouseId) }}
            </template>
          </template>
          <template #action="{ record }">
            <TableAction :actions="ruleActions(record)" />
          </template>
        </BasicTable>
      </a-tab-pane>
    </a-tabs>

    <!-- 规则新建 / 编辑 -->
    <a-modal
      v-model:open="ruleOpen"
      :title="ruleForm.id ? '编辑规则' : '新建规则'"
      :confirm-loading="saving"
      width="620px"
      @ok="handleSaveRule"
    >
      <a-form layout="vertical">
        <a-form-item label="规则名称" required>
          <a-input v-model:value="ruleForm.ruleName" :maxlength="64" placeholder="如：A 类关键件下限" />
        </a-form-item>
        <a-form-item label="规则类型" required>
          <a-select v-model:value="ruleForm.ruleType" :options="RULE_TYPE_OPTIONS" @change="onRuleTypeChange" />
        </a-form-item>
        <a-form-item label="作用域" required>
          <a-select v-model:value="ruleForm.scopeType" :options="SCOPE_TYPE_OPTIONS" />
        </a-form-item>
        <a-form-item v-if="ruleForm.scopeType === 'MATERIAL'" label="物料" required>
          <a-select
            v-model:value="ruleForm.materialId"
            show-search
            option-filter-prop="label"
            :options="materialOptions"
            placeholder="选择物料"
          />
        </a-form-item>
        <a-form-item v-if="ruleForm.scopeType === 'CATEGORY'" label="物料分类" required>
          <a-select
            v-model:value="ruleForm.categoryId"
            show-search
            option-filter-prop="label"
            :options="categoryOptions"
            placeholder="选择物料分类"
          />
        </a-form-item>
        <a-form-item label="限定库区（留空=全部库区）">
          <a-select v-model:value="ruleForm.warehouseId" allowClear show-search option-filter-prop="label" :options="warehouseOptions" />
        </a-form-item>
        <a-form-item v-if="ruleForm.ruleType === 'LOW_STOCK'" label="库存下限" required>
          <a-input-number v-model:value="ruleForm.thresholdQty" :min="0.001" :precision="3" style="width: 100%" />
        </a-form-item>
        <a-form-item v-if="ruleForm.ruleType === 'OVERDUE'" label="存放超期天数" required>
          <a-input-number v-model:value="ruleForm.overdueDays" :min="1" :max="3650" :precision="0" style="width: 100%" />
        </a-form-item>
        <a-form-item label="是否启用">
          <a-switch :checked="ruleForm.enabled === 1" @change="(v) => (ruleForm.enabled = v ? 1 : 0)" />
        </a-form-item>
        <a-form-item label="备注">
          <a-textarea v-model:value="ruleForm.remark" :rows="2" :maxlength="255" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 预警处理 -->
    <a-modal v-model:open="handleOpen" title="处理预警" :confirm-loading="saving" width="520px" @ok="handleSubmit">
      <a-descriptions :column="1" size="small" bordered style="margin-bottom: 12px">
        <a-descriptions-item label="预警单号">{{ current?.warningNo }}</a-descriptions-item>
        <a-descriptions-item label="物料">{{ current?.materialCode }} {{ current?.materialName }}</a-descriptions-item>
        <a-descriptions-item v-if="current?.locationCode" label="库位/批次">
          {{ current?.locationCode }} / {{ current?.batchNo || '-' }}
        </a-descriptions-item>
        <a-descriptions-item label="当前数量">{{ current?.currentQty }}</a-descriptions-item>
      </a-descriptions>
      <a-form layout="vertical">
        <a-form-item label="处理方式" required>
          <a-select v-model:value="handleForm.handleType" :options="HANDLE_TYPE_OPTIONS" />
        </a-form-item>
        <a-form-item label="处理说明" required>
          <a-textarea v-model:value="handleForm.handleRemark" :rows="3" :maxlength="500" placeholder="如：已从 C.04 调拨补 500" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script lang="ts" setup>
  import { computed, onMounted, ref } from 'vue';
  import { BasicTable, TableAction, useTable } from '/@/components/Table';
  import { useMessage } from '/@/hooks/web/useMessage';
  import {
    HANDLE_TYPE_OPTIONS,
    RULE_TYPE_OPTIONS,
    SCOPE_TYPE_OPTIONS,
    ruleColumns,
    ruleSearchSchema,
    warningColumns,
    warningSearchSchema,
  } from './warning.data';
  import {
    addRule,
    countPendingWarnings,
    deleteRule,
    editRule,
    handleWarning,
    listCategoryOptions,
    listMaterialOptions,
    listRules,
    listWarehouseOptions,
    listWarnings,
    scanWarnings,
  } from './warning.api';

  const { createMessage } = useMessage();

  const activeTab = ref('warning');
  const pendingTotal = ref(0);
  const warningTabTitle = computed(() => (pendingTotal.value > 0 ? `预警处理（${pendingTotal.value}）` : '预警处理'));

  const scanning = ref(false);
  const saving = ref(false);

  // ---------------------------------------------- 选项数据
  type Option = { label: string; value: string };
  const materialOptions = ref<Option[]>([]);
  const categoryOptions = ref<Option[]>([]);
  const warehouseOptions = ref<Option[]>([]);
  const materialLabelMap = ref<Record<string, string>>({});
  const categoryLabelMap = ref<Record<string, string>>({});
  const warehouseLabelMap = ref<Record<string, string>>({});

  async function loadOptions() {
    try {
      const [materialRes, categoryRes, warehouseRes]: any[] = await Promise.all([
        listMaterialOptions({ pageNo: 1, pageSize: 500 }),
        listCategoryOptions({ pageNo: 1, pageSize: 500 }),
        listWarehouseOptions({ pageNo: 1, pageSize: 500 }),
      ]);
      materialOptions.value = (materialRes?.records || []).map((m) => ({
        label: `${m.materialCode} ${m.materialName || ''}`.trim(),
        value: m.id,
      }));
      materialLabelMap.value = Object.fromEntries(materialOptions.value.map((o) => [o.value, o.label]));
      categoryOptions.value = (categoryRes?.records || []).map((c) => ({
        label: `${c.categoryCode || ''} ${c.categoryName || ''}`.trim(),
        value: c.id,
      }));
      categoryLabelMap.value = Object.fromEntries(categoryOptions.value.map((o) => [o.value, o.label]));
      warehouseOptions.value = (warehouseRes?.records || []).map((w) => ({
        label: `${w.warehouseCode || ''} ${w.warehouseName || ''}`.trim(),
        value: w.id,
      }));
      warehouseLabelMap.value = Object.fromEntries(warehouseOptions.value.map((o) => [o.value, o.label]));
    } catch (e) {
      // 选项拉取失败不阻断页面，规则弹窗里会显示空列表
    }
  }

  function scopeTargetLabel(record: any) {
    if (record.scopeType === 'MATERIAL') return materialLabelMap.value[record.materialId] || record.materialId || '-';
    if (record.scopeType === 'CATEGORY') return categoryLabelMap.value[record.categoryId] || record.categoryId || '-';
    return '全部';
  }

  function warehouseLabel(warehouseId?: string) {
    if (!warehouseId) return '全部库区';
    return warehouseLabelMap.value[warehouseId] || warehouseId;
  }

  // ---------------------------------------------- 预警列表
  const [registerWarningTable, { reload: reloadWarnings }] = useTable({
    title: '库存预警',
    api: (params) => listWarnings(params),
    columns: warningColumns,
    formConfig: { schemas: warningSearchSchema },
    showTableSetting: true,
    actionColumn: { width: 100, title: '操作', dataIndex: 'action' },
  });

  async function refreshPending() {
    try {
      pendingTotal.value = (await countPendingWarnings()) || 0;
    } catch (e) {
      pendingTotal.value = 0;
    }
  }

  async function handleScan() {
    scanning.value = true;
    try {
      const res: any = await scanWarnings();
      createMessage.success(res?.message || '扫描完成');
      await reloadWarnings();
      await refreshPending();
    } finally {
      scanning.value = false;
    }
  }

  function warningActions(record: any) {
    return [
      {
        label: '处理',
        disabled: record.status !== 'PENDING',
        auth: 'wms:warning:handle',
        onClick: () => openHandle(record),
      },
    ];
  }

  // ---------------------------------------------- 规则列表
  const [registerRuleTable, { reload: reloadRules }] = useTable({
    title: '预警规则',
    api: (params) => listRules(params),
    columns: ruleColumns,
    formConfig: { schemas: ruleSearchSchema },
    showTableSetting: true,
    actionColumn: { width: 160, title: '操作', dataIndex: 'action' },
  });

  function ruleActions(record: any) {
    return [
      {
        label: '编辑',
        auth: 'wms:warning:edit',
        onClick: () => openRule(record),
      },
      {
        label: '删除',
        color: 'error',
        auth: 'wms:warning:delete',
        popConfirm: {
          title: '确认删除该规则？历史预警保留规则快照，不受影响。',
          confirm: () => handleDeleteRule(record),
        },
      },
    ];
  }

  // ---------------------------------------------- 规则表单
  const ruleOpen = ref(false);
  const emptyRule = () => ({
    id: '',
    ruleName: '',
    ruleType: 'LOW_STOCK',
    scopeType: 'ALL',
    materialId: undefined,
    categoryId: undefined,
    warehouseId: undefined,
    thresholdQty: undefined,
    overdueDays: undefined,
    enabled: 1,
    remark: '',
  });
  const ruleForm = ref<any>(emptyRule());

  function openRule(record?: any) {
    ruleForm.value = record ? { ...record } : emptyRule();
    ruleOpen.value = true;
  }

  function onRuleTypeChange() {
    // 两种规则互斥字段：切换类型时清掉另一侧的值，避免后端「不允许填写」校验拦下
    if (ruleForm.value.ruleType === 'LOW_STOCK') {
      ruleForm.value.overdueDays = undefined;
    } else {
      ruleForm.value.thresholdQty = undefined;
    }
  }

  async function handleSaveRule() {
    const form = ruleForm.value;
    if (!form.ruleName?.trim()) {
      createMessage.warning('规则名称不能为空');
      return;
    }
    if (form.ruleType === 'LOW_STOCK' && !(Number(form.thresholdQty) > 0)) {
      createMessage.warning('库存下限必须大于 0');
      return;
    }
    if (form.ruleType === 'OVERDUE' && !(Number(form.overdueDays) >= 1)) {
      createMessage.warning('请填写存放超期天数');
      return;
    }
    saving.value = true;
    try {
      const payload = { ...form };
      if (payload.id) {
        await editRule(payload);
        createMessage.success('已保存');
      } else {
        delete payload.id;
        await addRule(payload);
        createMessage.success('已创建');
      }
      ruleOpen.value = false;
      await reloadRules();
    } finally {
      saving.value = false;
    }
  }

  async function handleDeleteRule(record: any) {
    await deleteRule(record.id);
    createMessage.success('已删除');
    await reloadRules();
  }

  // ---------------------------------------------- 处理
  const handleOpen = ref(false);
  const current = ref<any>(null);
  const handleForm = ref({ handleType: 'REPLENISH', handleRemark: '' });

  function openHandle(record: any) {
    current.value = record;
    handleForm.value = { handleType: 'REPLENISH', handleRemark: '' };
    handleOpen.value = true;
  }

  async function handleSubmit() {
    if (!handleForm.value.handleRemark?.trim()) {
      createMessage.warning('处理说明不能为空');
      return;
    }
    saving.value = true;
    try {
      await handleWarning({ id: current.value.id, ...handleForm.value });
      createMessage.success('已处理并解除');
      handleOpen.value = false;
      await reloadWarnings();
      await refreshPending();
    } finally {
      saving.value = false;
    }
  }

  // ---------------------------------------------- tab
  function handleTabChange(key: string) {
    if (key === 'rule') {
      reloadRules();
    } else {
      reloadWarnings();
      refreshPending();
    }
  }

  onMounted(async () => {
    await loadOptions();
    await refreshPending();
  });
</script>

<style lang="less" scoped>
  .wms-warning {
    padding: 8px;
  }
</style>
