<template>
  <div class="wms-sync-diff">
    <!-- 筛选 -->
    <a-card :bordered="false" style="margin-bottom: 12px">
      <a-form layout="inline" style="row-gap: 8px">
        <a-form-item label="对账批次">
          <a-select
            v-model:value="query.runId"
            :options="runOptions"
            show-search
            option-filter-prop="label"
            placeholder="全部批次"
            allow-clear
            style="width: 340px"
          />
        </a-form-item>
        <a-form-item label="仓库">
          <a-select
            v-model:value="query.warehouseCode"
            :options="warehouseOptions"
            placeholder="全部仓库"
            show-search
            option-filter-prop="label"
            allow-clear
            style="width: 220px"
          />
        </a-form-item>
        <a-form-item label="物料">
          <a-input
            v-model:value="query.materialKeyword"
            placeholder="编码或名称"
            allow-clear
            style="width: 180px"
            @press-enter="handleQuery"
          />
        </a-form-item>
        <a-form-item label="状态">
          <a-select
            v-model:value="query.status"
            :options="DIFF_STATUS_OPTIONS"
            placeholder="全部"
            allow-clear
            style="width: 120px"
          />
        </a-form-item>
        <a-form-item label="差异类型">
          <a-select
            v-model:value="query.diffType"
            :options="DIFF_TYPE_OPTIONS"
            placeholder="全部"
            allow-clear
            style="width: 120px"
          />
        </a-form-item>
        <a-form-item>
          <a-space>
            <a-button
              type="primary"
              preIcon="ant-design:search-outlined"
              v-auth="'wms:sync:diff:view'"
              :loading="loading"
              @click="handleQuery"
            >
              查询
            </a-button>
            <a-button preIcon="ant-design:reload-outlined" @click="handleReset">重置</a-button>
          </a-space>
        </a-form-item>
      </a-form>
      <a-alert type="warning" show-icon style="margin-top: 12px">
        <template #message>差异处理口径</template>
        <template #description>
          差异是正常业务结果，不是故障。「发起盘点」只生成盘点草稿，且盘点只按「WMS 账面 vs 实盘」调整；
          若实盘等于 WMS 账面，多账/少账差异不会被消除，需在金蝶侧人工处理。
        </template>
      </a-alert>
    </a-card>

    <!-- 差异清单 -->
    <a-card title="差异清单" :bordered="false">
      <a-table
        :columns="scaledColumns"
        :data-source="records"
        :loading="loading"
        :pagination="pagination"
        row-key="id"
        size="small"
        :scroll="{ x: tableScrollX }"
        :locale="{ emptyText: '暂无差异记录' }"
        @change="handleTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'action'">
            <a-space>
              <a-button
                type="link"
                size="small"
                v-auth="'wms:sync:diff:handle'"
                :disabled="record.status !== '待核对'"
                @click="openHandle(record)"
              >
                标记处理
              </a-button>
              <a-tooltip :title="stocktakeDisabledReason(record)">
                <span>
                  <a-button
                    type="link"
                    size="small"
                    v-auth="'wms:sync:diff:stocktake'"
                    :disabled="!!stocktakeDisabledReason(record)"
                    @click="openStocktake(record)"
                  >
                    发起盘点
                  </a-button>
                </span>
              </a-tooltip>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-card>

    <!-- 标记处理 -->
    <a-modal
      v-model:open="handleOpen"
      title="标记处理"
      :confirm-loading="handling"
      ok-text="提交"
      cancel-text="取消"
      @ok="submitHandle"
    >
      <a-form layout="vertical">
        <a-form-item label="差异">
          <span>
            {{ current?.warehouseCode || '—' }} / {{ current?.materialCode || '（无金蝶编码）' }}
            {{ current?.materialName || '' }} / 批次 {{ current?.batchNo || '—' }}
          </span>
        </a-form-item>
        <a-form-item label="差异类型">
          <span>{{ current?.diffType || '—' }}（WMS {{ current?.wmsQty ?? '—' }}，金蝶 {{ current?.erpQty ?? '—' }}）</span>
        </a-form-item>
        <a-form-item label="处理结果" required>
          <a-radio-group v-model:value="handleTargetStatus">
            <a-radio value="已核对">已核对</a-radio>
            <a-radio value="已忽略">已忽略</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item label="处理备注" required>
          <a-textarea
            v-model:value="handleRemark"
            :rows="3"
            placeholder="填写核对结论或忽略原因（必填）"
          />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script lang="ts" name="wms-sync-diff" setup>
  import { computed, h, onMounted, reactive, ref } from 'vue';
  import { useRoute } from 'vue-router';
  import { Modal } from 'ant-design-vue';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { scaleScrollX, scaleTableColumns } from '/@/hooks/web/useFontScale';
  import {
    DIFF_STATUS_OPTIONS,
    DIFF_TYPE_OPTIONS,
    SYNC_SCOPE_LABEL,
    diffColumns,
  } from './sync.data';
  import {
    createStocktakeFromDiff,
    handleDiff,
    listWarehouseOptions,
    pageDiffs,
    pageRuns,
  } from './sync.api';

  defineOptions({ name: 'WmsSyncDiff' });

  const { createMessage } = useMessage();
  const route = useRoute();

  const scaledColumns = computed(() => scaleTableColumns(diffColumns));
  const tableScrollX = computed(() => scaleScrollX(2000));

  // ---------------------------------------------------------------- 下拉
  const runOptions = ref<{ label: string; value: string }[]>([]);
  const warehouseOptions = ref<{ label: string; value: string }[]>([]);

  async function fetchRunOptions() {
    try {
      const res: any = await pageRuns({ pageNo: 1, pageSize: 100 });
      runOptions.value = (res.records || []).map((r: any) => ({
        label: `${r.runNo}（${SYNC_SCOPE_LABEL[r.scopeType] || r.scopeType} · ${r.status}）`,
        value: r.id,
      }));
    } catch (e: any) {
      // defHttp 已统一弹错误提示
    }
  }

  async function fetchWarehouseOptions() {
    try {
      const res: any = await listWarehouseOptions({ pageNo: 1, pageSize: 500 });
      warehouseOptions.value = (res.records || []).map((w: any) => ({
        label: `${w.warehouseCode} ${w.warehouseName}${w.status === '0' ? '（停用）' : ''}`,
        value: w.warehouseCode,
      }));
    } catch (e: any) {
      // defHttp 已统一弹错误提示
    }
  }

  // ---------------------------------------------------------------- 列表
  const query = reactive({
    runId: undefined as string | undefined,
    warehouseCode: undefined as string | undefined,
    materialKeyword: '',
    status: undefined as string | undefined,
    diffType: undefined as string | undefined,
  });

  const loading = ref(false);
  const records = ref<any[]>([]);
  const total = ref(0);
  const pageNo = ref(1);
  const pageSize = ref(20);

  const pagination = computed(() => ({
    current: pageNo.value,
    pageSize: pageSize.value,
    total: total.value,
    showSizeChanger: true,
    pageSizeOptions: ['20', '50', '100'],
    showTotal: (t: number) => `共 ${t} 条`,
  }));

  async function fetchData() {
    loading.value = true;
    try {
      const res: any = await pageDiffs({
        runId: query.runId || undefined,
        warehouseCode: query.warehouseCode || undefined,
        materialKeyword: query.materialKeyword || undefined,
        status: query.status || undefined,
        diffType: query.diffType || undefined,
        pageNo: pageNo.value,
        pageSize: pageSize.value,
      });
      records.value = res.records || [];
      total.value = Number(res.total || 0);
    } finally {
      loading.value = false;
    }
  }

  function handleQuery() {
    pageNo.value = 1;
    fetchData();
  }

  function handleReset() {
    query.runId = undefined;
    query.warehouseCode = undefined;
    query.materialKeyword = '';
    query.status = undefined;
    query.diffType = undefined;
    handleQuery();
  }

  function handleTableChange(pag: { current?: number; pageSize?: number }) {
    if (pag.pageSize && pag.pageSize !== pageSize.value) {
      pageSize.value = pag.pageSize;
      pageNo.value = 1;
    } else if (pag.current) {
      pageNo.value = pag.current;
    }
    fetchData();
  }

  // ---------------------------------------------------------------- 标记处理
  const handleOpen = ref(false);
  const handling = ref(false);
  const current = ref<any>(null);
  const handleTargetStatus = ref('已核对');
  const handleRemark = ref('');

  function openHandle(record: any) {
    current.value = record;
    handleTargetStatus.value = '已核对';
    handleRemark.value = '';
    handleOpen.value = true;
  }

  async function submitHandle() {
    if (!handleRemark.value.trim()) {
      createMessage.warning('处理备注必填');
      return;
    }
    handling.value = true;
    try {
      await handleDiff(current.value.id, {
        targetStatus: handleTargetStatus.value,
        remark: handleRemark.value.trim(),
      });
      handleOpen.value = false;
      createMessage.success(`已标记为「${handleTargetStatus.value}」`);
      await fetchData();
    } catch (e: any) {
      // defHttp 已统一弹错误提示
    } finally {
      handling.value = false;
    }
  }

  // ---------------------------------------------------------------- 发起盘点
  /**
   * 不能发起盘点的原因（undefined = 可以发起）。
   *
   * 与后端 createStocktake 的拒绝条件保持一致：已发起过、未绑金蝶编码、仅ERP/无账面、非待核对。
   */
  function stocktakeDisabledReason(record: any): string | undefined {
    if (record.stocktakeId) {
      return `已发起盘点：${record.stocktakeNo || record.stocktakeId}`;
    }
    if (!record.materialCode) {
      return '物料未绑定金蝶编码，无法发起盘点，请先完成物料主数据同步';
    }
    if (record.status !== '待核对') {
      return '只有「待核对」的差异可以发起盘点';
    }
    if (record.diffType === '仅ERP' || record.wmsQty == null || Number(record.wmsQty) <= 0) {
      return 'WMS 无账面库存，无法盘点；请走生产入库或其他入库修正';
    }
    return undefined;
  }

  function openStocktake(record: any) {
    const reason = stocktakeDisabledReason(record);
    if (reason) {
      createMessage.warning(reason);
      return;
    }
    Modal.confirm({
      title: '发起盘点确认',
      width: 560,
      okText: '确认发起',
      cancelText: '取消',
      okType: 'danger',
      content: h('div', [
        h(
          'p',
          `将对「${record.warehouseCode || '—'} / ${record.materialCode}${
            record.batchNo ? ' / ' + record.batchNo : ''
          }」发起盘点，请先确认以下风险：`,
        ),
        h('ol', { style: 'padding-left: 20px; margin: 8px 0;' }, [
          h('li', '只生成盘点草稿不立即冻结'),
          h('li', '开始盘点后冻结整片库位（同库位其它物料一起盘）'),
          h('li', '盘点差异调整会回写金蝶 ERP'),
        ]),
        h(
          'p',
          { style: 'color: #faad14; margin-bottom: 0;' },
          '若实盘等于 WMS 账面，多账/少账差异不会被消除，需在金蝶侧人工处理。',
        ),
      ]),
      onOk: async () => {
        try {
          const order: any = await createStocktakeFromDiff(record.id);
          createMessage.success(`盘点草稿已创建：${order?.stocktakeNo || '—'}，请到盘点页按正常流程处理`);
          await fetchData();
        } catch (e: any) {
          // defHttp 已统一弹错误提示
        }
      },
    });
  }

  onMounted(async () => {
    const queryRunId = route.query.runId as string | undefined;
    if (queryRunId) {
      query.runId = queryRunId;
    }
    await Promise.all([fetchRunOptions(), fetchWarehouseOptions()]);
    await fetchData();
  });
</script>

<style lang="less" scoped>
  .tip {
    color: @text-color-secondary;
  }
</style>
