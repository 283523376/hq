<template>
  <div class="wms-stocktake">
    <BasicTable @register="registerTable">
      <template #tableTitle>
        <a-button type="primary" preIcon="ant-design:plus-outlined" v-auth="'wms:stocktake:add'" @click="openCreate">
          新建盘点
        </a-button>
      </template>
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'status'">
          <Tag :color="STATUS_COLOR[record.status] || 'default'">{{ record.status }}</Tag>
        </template>
      </template>
      <template #action="{ record }">
        <TableAction :actions="actions(record)" :dropDownActions="dropActions(record)" />
      </template>
    </BasicTable>

    <a-modal v-model:open="createOpen" title="新建盘点" @ok="handleCreate" :confirm-loading="creating">
      <a-form layout="vertical">
        <a-form-item label="盘点类型">
          <a-select v-model:value="createType" :options="STOCKTAKE_TYPE_OPTIONS" />
        </a-form-item>
        <a-form-item label="库位（要盘的库位ID，逗号/换行分隔）">
          <a-textarea v-model:value="createLocations" :rows="4" placeholder="loc-id-1,loc-id-2,..." />
        </a-form-item>
        <a-form-item label="备注">
          <a-input v-model:value="createRemark" />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal v-model:open="recordOpen" title="录入实盘" @ok="handleRecord" :confirm-loading="acting">
      <a-form layout="vertical">
        <a-form-item label="每行一条：明细ID,实盘数量,差异原因(可空)；留空数量的行请勿填写（未录入≠0）">
          <a-textarea v-model:value="recordRows" :rows="5" placeholder="d1,12,&#10;d2,7,破损" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script lang="ts" setup>
  import { computed, ref } from 'vue';
  import { useRoute } from 'vue-router';
  import { BasicTable, TableAction, useTable } from '/@/components/Table';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { STATUS_COLOR, STOCKTAKE_TYPE_OPTIONS, columns, searchFormSchema } from './stocktake.data';
  import {
    adjustStocktake,
    archiveStocktake,
    confirmDiff,
    createStocktake,
    listStocktake,
    recordActual,
    reviewStocktake,
    startStocktake,
    writebackStocktake,
  } from './stocktake.api';

  const { createMessage } = useMessage();
  const route = useRoute();
  // 两个子菜单共用同一页面：按路由路径区分类型（/wms/stocktake/cycle → 周期）
  const routeType = computed(() => (route.path.includes('/cycle') ? '周期' : '定期'));
  const pageTitle = computed(() => `${routeType.value}盘点`);

  const [registerTable, { reload }] = useTable({
    title: pageTitle,
    api: (params) => listStocktake({ ...params, stocktakeType: routeType.value }),
    columns,
    formConfig: { schemas: searchFormSchema },
    showTableSetting: true,
    useSearchForm: true,
    actionColumn: { width: 160, title: '操作', dataIndex: 'action' },
  });

  const createOpen = ref(false);
  const creating = ref(false);
  const createType = ref('定期');
  const createLocations = ref('');
  const createRemark = ref('');
  const recordOpen = ref(false);
  const recordId = ref('');
  const recordRows = ref('');
  const acting = ref(false);

  function openCreate() {
    createType.value = routeType.value;
    createLocations.value = '';
    createRemark.value = '';
    createOpen.value = true;
  }

  async function handleCreate() {
    const locationIds = createLocations.value
      .split(/[,\n]/)
      .map((s) => s.trim())
      .filter(Boolean);
    if (!locationIds.length) {
      createMessage.warning('请填写库位');
      return;
    }
    creating.value = true;
    try {
      const res: any = await createStocktake({
        stocktakeType: createType.value,
        locationIds,
        remark: createRemark.value,
      });
      createMessage.success(`已创建：${res.stocktakeNo}`);
      createOpen.value = false;
      reload();
    } finally {
      creating.value = false;
    }
  }

  async function run(action: () => Promise<any>, done?: string) {
    acting.value = true;
    try {
      const msg: any = await action();
      createMessage.success((msg as string) || done || '操作成功');
      reload();
    } finally {
      acting.value = false;
    }
  }

  function actions(record: any) {
    const list = [{ label: '详情', onClick: () => createMessage.info(`明细接口：/wms/stocktake/details?id=${record.id}`) }];
    if (record.status === '盘点中') {
      list.push({
        label: '录入实盘',
        onClick: () => {
          recordId.value = record.id;
          recordRows.value = '';
          recordOpen.value = true;
        },
      });
      list.push({ label: '差异确认', onClick: () => run(() => confirmDiff(record.id), '已确认') });
    }
    if (record.status === '复核中') {
      list.push({
        label: '复核',
        popConfirm: {
          title: '以当前登录人复核通过？（复核人不得是发起人）',
          confirm: () => run(() => reviewStocktake(record.id), '复核通过'),
        },
      });
    }
    return list;
  }

  function dropActions(record: any) {
    const items: any[] = [];
    if (record.status === '草稿') {
      items.push({
        label: '开始盘点',
        popConfirm: { title: '开始盘点并冻结库位？', confirm: () => run(() => startStocktake(record.id)) },
      });
    }
    if (record.status === '差异确认') {
      items.push({
        label: '调整库存',
        popConfirm: { title: '按实盘覆盖库存？', confirm: () => run(() => adjustStocktake(record.id), '已调整') },
      });
    }
    if (record.status === '已调整') {
      items.push({
        label: '回写ERP',
        popConfirm: { title: '盘盈/盘亏回写金蝶？', confirm: () => run(() => writebackStocktake(record.id), '已回写') },
      });
      items.push({
        label: '归档',
        popConfirm: { title: '归档后不可再操作？', confirm: () => run(() => archiveStocktake(record.id), '已归档') },
      });
    }
    return items;
  }

  function handleRecord() {
    const actuals = recordRows.value
      .split('\n')
      .map((line) => line.split(','))
      .filter((parts) => parts[0]?.trim())
      .map((parts) => ({
        id: parts[0].trim(),
        actualQty: parts[1] === undefined || parts[1].trim() === '' ? null : Number(parts[1].trim()),
        diffReason: parts[2]?.trim(),
      }));
    if (!actuals.length) {
      createMessage.warning('请至少填写一行');
      return;
    }
    run(() => recordActual(recordId.value, actuals), '已录入');
  }
</script>
