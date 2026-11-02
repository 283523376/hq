<template>
  <div :class="`${prefixCls}`">
    <BasicTable @register="registerTable">
      <template #tableTitle>
        <a-button type="primary" preIcon="ant-design:search-outlined" @click="openImpact">影响面查询</a-button>
        <a-button preIcon="ant-design:download-outlined" v-auth="'wms:audit:export'" :loading="exporting" @click="handleExport">
          导出
        </a-button>
        <span class="tip">按工单号查它会牵动哪些单据与库存</span>
      </template>
    </BasicTable>

    <a-drawer v-model:open="impactOpen" title="影响面查询" width="1000" placement="right" :destroyOnClose="true">
      <div class="impact-search">
        <a-input v-model:value="impactWorkOrderNo" placeholder="输入工单号，例如 MO1012615790" allow-clear @pressEnter="loadImpact" />
        <a-button type="primary" :loading="impactLoading" @click="loadImpact">查询</a-button>
      </div>

      <template v-if="impact">
        <!-- 缺口必须醒目：查不到不代表没影响，只代表已被删除、只剩日志 -->
        <a-alert
          v-for="(gap, index) in impact.gaps"
          :key="index"
          type="warning"
          show-icon
          :message="gap"
          style="margin-bottom: 8px"
        />

        <a-descriptions bordered size="small" :column="3" title="工单" style="margin-bottom: 16px">
          <a-descriptions-item label="工单号">{{ impact.workOrder?.workOrderNo || '—' }}</a-descriptions-item>
          <a-descriptions-item label="物料">{{ impact.workOrder?.materialCode || '—' }}</a-descriptions-item>
          <a-descriptions-item label="状态">{{ impact.workOrder?.workOrderStatus || '—' }}</a-descriptions-item>
          <a-descriptions-item label="计划数量">{{ impact.workOrder?.planQty ?? '—' }}</a-descriptions-item>
          <a-descriptions-item label="批次">{{ impact.workOrder?.batchNo || '—' }}</a-descriptions-item>
          <a-descriptions-item label="ERP 单据状态">{{ impact.workOrder?.erpDocumentStatus || '—' }}</a-descriptions-item>
        </a-descriptions>

        <a-divider orientation="left">生产入库单（{{ impact.productionInbounds?.length || 0 }}）</a-divider>
        <a-table
          size="small"
          :pagination="false"
          :columns="inboundColumns"
          :data-source="impact.productionInbounds || []"
          :row-key="(r) => r.id"
          :locale="{ emptyText: '无' }"
        />

        <a-divider orientation="left">出库 / 领料单（{{ impact.outbounds?.length || 0 }}）</a-divider>
        <a-table
          size="small"
          :pagination="false"
          :columns="outboundColumns"
          :data-source="outboundRows"
          :row-key="(r) => r.key"
          :locale="{ emptyText: '无' }"
        />

        <a-divider orientation="left">受影响的库存行（当前库实时，{{ impact.inventoryRows?.length || 0 }}）</a-divider>
        <a-table
          size="small"
          :pagination="false"
          :columns="inventoryColumns"
          :data-source="impact.inventoryRows || []"
          :row-key="(r) => r.id"
          :locale="{ emptyText: '无' }"
        />

        <a-divider orientation="left">已回写 ERP 的单据（{{ impact.erpBills?.length || 0 }}）</a-divider>
        <a-table
          size="small"
          :pagination="false"
          :columns="erpColumns"
          :data-source="impact.erpBills || []"
          :row-key="(r) => `${r.billNo}-${r.sourceNo}`"
          :locale="{ emptyText: '无' }"
        />

        <a-divider orientation="left">变更时间线（{{ impact.timeline?.length || 0 }}）</a-divider>
        <a-table
          size="small"
          :pagination="false"
          :columns="timelineColumns"
          :data-source="impact.timeline || []"
          :row-key="(r) => r.id"
          :locale="{ emptyText: '无审计记录（可能早于留痕功能上线）' }"
        />
      </template>
      <a-empty v-else-if="!impactLoading" description="输入工单号后点查询" />
    </a-drawer>
  </div>
</template>

<script lang="ts" name="wms-audit" setup>
  import { computed, ref } from 'vue';
  import dayjs from 'dayjs';
  import { BasicTable } from '/@/components/Table';
  import { useListPage } from '/@/hooks/system/useListPage';
  import { useDesign } from '/@/hooks/web/useDesign';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { columns, searchFormSchema } from './audit.data';
  import { list, queryImpact, createAuditExport } from './audit.api';

  const { prefixCls } = useDesign('wms-audit');
  const { createMessage } = useMessage();

  const { tableContext } = useListPage({
    tableProps: {
      api: list,
      columns,
      rowKey: 'id',
      formConfig: { schemas: searchFormSchema },
      // 搜索区的时间是 RangePicker（[起, 止]），后端要 startTime/endTime 两个参数
      beforeFetch: (params) => {
        const { timeRange, ...rest } = params;
        if (Array.isArray(timeRange) && timeRange.length === 2) {
          rest.startTime = dayjs(timeRange[0]).format('YYYY-MM-DD HH:mm:ss');
          rest.endTime = dayjs(timeRange[1]).format('YYYY-MM-DD HH:mm:ss');
        }
        return rest;
      },
    },
  });
  const [registerTable, { getForm }] = tableContext;

  // ---------------------------------------------------------------- 导出
  const exporting = ref(false);

  /** 导出走异步任务：这里只负责提交，文件在「导出任务」里下载 */
  async function handleExport() {
    const { timeRange, ...filters } = (await getForm().validate()) || {};
    if (Array.isArray(timeRange) && timeRange.length === 2) {
      filters.startTime = dayjs(timeRange[0]).format('YYYY-MM-DD HH:mm:ss');
      filters.endTime = dayjs(timeRange[1]).format('YYYY-MM-DD HH:mm:ss');
    }
    exporting.value = true;
    try {
      await createAuditExport(filters);
      createMessage.success('导出任务已提交，请到导出任务列表下载');
    } catch (e: any) {
      createMessage.error(e?.message || '提交导出任务失败');
    } finally {
      exporting.value = false;
    }
  }

  // ---------------------------------------------------------------- 影响面
  const impactOpen = ref(false);
  const impactLoading = ref(false);
  const impactWorkOrderNo = ref('');
  const impact = ref<any>(null);

  function openImpact() {
    impactOpen.value = true;
  }

  async function loadImpact() {
    const no = (impactWorkOrderNo.value || '').trim();
    if (!no) {
      createMessage.warning('请输入工单号');
      return;
    }
    impactLoading.value = true;
    try {
      impact.value = await queryImpact(no);
    } catch (e: any) {
      impact.value = null;
      createMessage.error(e?.message || '查询失败');
    } finally {
      impactLoading.value = false;
    }
  }

  /** 出库单与其明细拍平展示：明细是我们真正要看的那一层 */
  const outboundRows = computed(() => {
    const rows: any[] = [];
    (impact.value?.outbounds || []).forEach((item: any) => {
      const order = item.order || {};
      (item.details?.length ? item.details : [{}]).forEach((detail: any) => {
        rows.push({
          key: `${order.id}-${detail.id || 'head'}`,
          outboundNo: order.outboundNo,
          outboundType: order.outboundType,
          status: order.status,
          erpBillNo: order.erpBillNo,
          materialCode: detail.materialCode,
          requiredQty: detail.requiredQty,
          allocatedQty: detail.allocatedQty,
        });
      });
    });
    return rows;
  });

  const inboundColumns = [
    { title: '入库单号', dataIndex: 'inboundNo', width: 190 },
    { title: '物料', dataIndex: 'materialCode', width: 180 },
    { title: '批次', dataIndex: 'batchNo', width: 120 },
    { title: '数量', dataIndex: 'quantity', width: 90 },
    { title: '状态', dataIndex: 'status', width: 100 },
    { title: '库位', dataIndex: 'locationCode', width: 110 },
    { title: 'ERP 单号', dataIndex: 'erpBillNo', width: 150 },
  ];

  const outboundColumns = [
    { title: '出库单号', dataIndex: 'outboundNo', width: 190 },
    { title: '类型', dataIndex: 'outboundType', width: 100 },
    { title: '状态', dataIndex: 'status', width: 100 },
    { title: '物料', dataIndex: 'materialCode', width: 180 },
    { title: '需求数量', dataIndex: 'requiredQty', width: 90 },
    { title: '已分配', dataIndex: 'allocatedQty', width: 90 },
    { title: 'ERP 单号', dataIndex: 'erpBillNo', width: 150 },
  ];

  const inventoryColumns = [
    { title: '物料', dataIndex: 'materialCode', width: 180 },
    { title: '物料名称', dataIndex: 'materialName', width: 160 },
    { title: '批次', dataIndex: 'batchNo', width: 120 },
    { title: '仓库', dataIndex: 'warehouseCode', width: 100 },
    { title: '库位', dataIndex: 'locationCode', width: 110 },
    { title: '数量', dataIndex: 'quantity', width: 90 },
    { title: '锁定', dataIndex: 'lockedQty', width: 90 },
  ];

  const erpColumns = [
    { title: 'ERP 单号', dataIndex: 'billNo', width: 180 },
    { title: '单据类型', dataIndex: 'type', width: 120 },
    { title: '来源 WMS 单号', dataIndex: 'sourceNo', width: 200 },
    { title: '时间', dataIndex: 'time', width: 170 },
  ];

  const timelineColumns = [
    { title: '时间', dataIndex: 'createTime', width: 170 },
    { title: '动作', dataIndex: 'action', width: 100 },
    { title: '通道', dataIndex: 'channel', width: 100 },
    { title: '单据', dataIndex: 'bizNo', width: 190 },
    { title: '结果', dataIndex: 'result', width: 80 },
    { title: '操作人', dataIndex: 'operator', width: 100 },
    { title: '说明', dataIndex: 'errorMsg', width: 220, ellipsis: true },
  ];
</script>

<style lang="less" scoped>
  .tip {
    margin-left: 12px;
    color: @text-color-secondary;
  }

  .impact-search {
    display: flex;
    gap: 8px;
    margin-bottom: 16px;
  }
</style>
