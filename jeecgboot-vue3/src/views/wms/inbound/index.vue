<template>
  <div class="wms-inbound">
    <!-- 收料台：扫码/录入采购订单号，生成收料通知单 -->
    <a-card :bordered="false" size="small" title="收料台" class="wms-inbound__receive">
      <a-form layout="inline" class="wms-inbound__form">
        <a-form-item label="采购订单号">
          <a-input
            ref="poInputRef"
            v-model:value="receiveForm.purchaseOrderNo"
            placeholder="扫码枪扫描或手工录入，回车即提交"
            allow-clear
            style="width: 320px"
            @press-enter="handleReceive"
          />
        </a-form-item>
        <a-form-item>
          <a-checkbox v-model:checked="receiveForm.packageDamaged">外包装损坏</a-checkbox>
        </a-form-item>
        <a-form-item>
          <a-button type="link" @click="addRow">+ 添加明细行</a-button>
        </a-form-item>
      </a-form>

      <a-alert
        v-if="receiveForm.packageDamaged"
        type="warning"
        show-icon
        message="外包装损坏将直接生成「已拒收」单据，请同时联系采购业务员安排供应商退货。"
        class="wms-inbound__alert"
      />

      <a-table
        :data-source="receiveForm.details"
        :columns="receiveColumns"
        :pagination="false"
        row-key="key"
        size="small"
        bordered
      >
        <template #bodyCell="{ column, record, index }">
          <template v-if="column.key === 'materialId'">
            <a-select
              v-model:value="record.materialId"
              show-search
              allow-clear
              :filter-option="false"
              placeholder="选择物料（输入编码/名称远程搜索）"
              :options="materialOptions"
              :not-found-content="materialSearching ? '搜索中…' : undefined"
              style="width: 100%"
              @search="handleMaterialSearch"
            />
          </template>
          <template v-else-if="column.key === 'quantity'">
            <a-input-number v-model:value="record.quantity" :min="0.001" :precision="3" style="width: 100%" />
          </template>
          <template v-else-if="column.key === 'batchNo'">
            <a-input v-model:value="record.batchNo" placeholder="批次号（必填）" />
          </template>
          <template v-else-if="column.key === 'action'">
            <a-button type="link" danger @click="removeRow(index)">删除</a-button>
          </template>
        </template>
      </a-table>

      <div class="wms-inbound__actions">
        <a-button type="primary" :loading="receiving" v-auth="'wms:inbound:receive'" @click="handleReceive">
          提交收料
        </a-button>
        <a-button @click="resetReceiveForm">清空</a-button>
      </div>
    </a-card>

    <!-- 入库单列表 -->
    <BasicTable @register="registerTable">
      <template #tableTitle>
        <span class="wms-inbound__list-title">入库单 / 收料通知单</span>
      </template>
      <template #status="{ record }">
        <a-tag :color="statusColor(record.inboundStatus)">{{ record.inboundStatus }}</a-tag>
      </template>
      <template #syncStatus="{ record }">
        <a-tooltip v-if="record.syncStatus === '同步失败'" :title="record.lastSyncError">
          <a-tag color="red">同步失败</a-tag>
        </a-tooltip>
        <a-tag v-else :color="record.syncStatus === '同步成功' ? 'green' : 'default'">
          {{ record.syncStatus === '无需同步' ? '无需同步' : record.syncStatus }}
        </a-tag>
      </template>
      <template #action="{ record }">
        <TableAction :actions="getTableAction(record)" />
      </template>
    </BasicTable>

    <!-- 详情抽屉 -->
    <a-drawer v-model:open="detailOpen" title="入库单详情" width="720" destroy-on-close>
      <template v-if="detail">
        <a-descriptions :column="2" size="small" bordered>
          <a-descriptions-item label="入库单号">{{ detail.order.orderNo }}</a-descriptions-item>
          <a-descriptions-item label="采购订单号">{{ detail.order.purchaseOrderNo }}</a-descriptions-item>
          <a-descriptions-item label="供应商">{{ detail.order.supplierName || '-' }}</a-descriptions-item>
          <a-descriptions-item label="状态">
            <a-tag :color="statusColor(detail.order.inboundStatus)">{{ detail.order.inboundStatus }}</a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="同步状态">{{ detail.order.syncStatus }}</a-descriptions-item>
          <a-descriptions-item label="审核人/时间">
            {{ detail.order.auditBy || '-' }} / {{ detail.order.auditTime || '-' }}
          </a-descriptions-item>
          <a-descriptions-item v-if="detail.order.rejectReason" label="拒收/作废原因" :span="2">
            {{ detail.order.rejectReason }}
          </a-descriptions-item>
        </a-descriptions>

        <a-divider orientation="left">明细</a-divider>
        <a-table
          :data-source="detail.details"
          :columns="detailColumns"
          :pagination="false"
          row-key="id"
          size="small"
          bordered
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'locationId'">
              {{ locationLabel(record.locationId) }}
            </template>
          </template>
        </a-table>

        <a-divider orientation="left">检验</a-divider>
        <a-descriptions :column="2" size="small" bordered>
          <a-descriptions-item label="检验任务号">{{ detail.inspection?.taskNo || '-' }}</a-descriptions-item>
          <a-descriptions-item label="检验状态">{{ detail.inspection?.inspectStatus || '-' }}</a-descriptions-item>
          <a-descriptions-item label="检验员">{{ detail.inspection?.inspector || '-' }}</a-descriptions-item>
          <a-descriptions-item label="检验时间">{{ detail.inspection?.inspectTime || '-' }}</a-descriptions-item>
          <a-descriptions-item v-if="detail.inspection?.resultRemark" label="结果说明" :span="2">
            {{ detail.inspection.resultRemark }}
          </a-descriptions-item>
        </a-descriptions>
      </template>
    </a-drawer>

    <LocationAllocateModal @register="registerAllocateModal" @success="handleSuccess" />
  </div>
</template>

<script lang="ts" name="wms-inbound" setup>
  import { h, onMounted, reactive, ref } from 'vue';
  import { BasicTable, TableAction } from '/@/components/Table';
  import { useModal } from '/@/components/Modal';
  import { useListPage } from '/@/hooks/system/useListPage';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { Modal } from 'ant-design-vue';
  import { defHttp } from '/@/utils/http/axios';
  import LocationAllocateModal from './LocationAllocateModal.vue';
  import { getInboundDetail, listInbound, receiveInbound, resyncInbound, voidInbound } from './inbound.api';

  const { createMessage } = useMessage();
  const [registerAllocateModal, { openModal: openAllocateModal }] = useModal();

  // ------------------------------------------------------------ 收料台

  let rowSeed = 0;
  const poInputRef = ref();
  const receiving = ref(false);
  const receiveForm = reactive<{ purchaseOrderNo: string; packageDamaged: boolean; details: any[] }>({
    purchaseOrderNo: '',
    packageDamaged: false,
    details: [],
  });

  const receiveColumns = [
    { title: '物料', key: 'materialId', width: 320 },
    { title: '数量', key: 'quantity', width: 140 },
    { title: '批次号', key: 'batchNo', width: 200 },
    { title: '操作', key: 'action', width: 80 },
  ];

  const detailColumns = [
    { title: '物料编码', dataIndex: 'materialCode', width: 140 },
    { title: '物料名称', dataIndex: 'materialName', width: 160 },
    { title: '应收数量', dataIndex: 'quantity', width: 100 },
    { title: '实收数量', dataIndex: 'actualQuantity', width: 100 },
    { title: '批次号', dataIndex: 'batchNo', width: 140 },
    { title: '库位', key: 'locationId', width: 140 },
  ];

  const materialOptions = ref<{ label: string; value: string }[]>([]);
  const materialSearching = ref(false);
  /** 已选物料的标签缓存：远程搜索结果变化后，已选项仍能显示正确名称 */
  const materialLabelMap = ref<Record<string, string>>({});
  let materialSearchTimer: any = null;
  /** 库位 id → 库位编码（详情抽屉展示用） */
  const locationMap = ref<Record<string, string>>({});

  function addRow() {
    receiveForm.details.push({ key: ++rowSeed, materialId: undefined, quantity: 1, batchNo: '' });
  }

  function removeRow(index: number) {
    receiveForm.details.splice(index, 1);
  }

  function resetReceiveForm() {
    receiveForm.purchaseOrderNo = '';
    receiveForm.packageDamaged = false;
    receiveForm.details = [];
    addRow();
    poInputRef.value?.focus?.();
  }

  /** 提交收料：成功后清空表单（防扫码枪误触双击重复提交） */
  async function handleReceive() {
    // 扫码枪连按回车 / 用户连点：请求返回前直接忽略后续提交，避免生成两张收料单
    if (receiving.value) {
      return;
    }
    if (!receiveForm.purchaseOrderNo || !receiveForm.purchaseOrderNo.trim()) {
      createMessage.warning('请先扫码或录入采购订单号');
      return;
    }
    const details = receiveForm.details.filter((d) => d.materialId);
    if (!details.length) {
      createMessage.warning('请至少录入一行物料明细');
      return;
    }
    if (details.some((d) => !d.batchNo || !d.batchNo.trim())) {
      createMessage.warning('每行明细都必须填写批次号');
      return;
    }
    receiving.value = true;
    try {
      const res: any = await receiveInbound({
        purchaseOrderNo: receiveForm.purchaseOrderNo.trim(),
        packageDamaged: receiveForm.packageDamaged,
        details: details.map((d) => ({
          materialId: d.materialId,
          quantity: d.quantity,
          batchNo: d.batchNo.trim(),
        })),
      });
      if (res?.inboundStatus === '已拒收') {
        createMessage.warning(
          `已生成拒收单 ${res.orderNo}：外包装损坏，请联系采购业务员安排供应商退货。`,
          8
        );
      } else {
        createMessage.success(`收料成功，已生成收料通知单 ${res.orderNo}，检验任务已通知检验员`);
      }
      resetReceiveForm();
      reload();
    } finally {
      receiving.value = false;
    }
  }

  // ------------------------------------------------------------ 列表

  const { tableContext } = useListPage({
    tableProps: {
      api: listInbound,
      columns: [
        { title: '入库单号', dataIndex: 'orderNo', width: 170 },
        { title: '采购订单号', dataIndex: 'purchaseOrderNo', width: 160 },
        { title: '供应商', dataIndex: 'supplierName', width: 160 },
        { title: '状态', dataIndex: 'inboundStatus', slots: { customRender: 'status' }, width: 110 },
        { title: '同步状态', dataIndex: 'syncStatus', slots: { customRender: 'syncStatus' }, width: 110 },
        { title: '创建时间', dataIndex: 'createTime', width: 170 },
      ],
      rowKey: 'id',
      formConfig: {
        schemas: [
          {
            field: 'inboundStatus',
            label: '状态',
            component: 'Select',
            componentProps: {
              options: ['待检验', '待分配', '待上架', '已审核', '已拒收', '检验不合格', '作废'].map((v) => ({
                label: v,
                value: v,
              })),
            },
          },
          { field: 'orderNo', label: '入库单号', component: 'Input' },
          { field: 'supplierName', label: '供应商', component: 'Input' },
        ],
      },
      actionColumn: { width: 260 },
    },
  });
  const [registerTable, { reload }] = tableContext;

  function statusColor(status: string) {
    const map: Record<string, string> = {
      待检验: 'orange',
      待分配: 'blue',
      待上架: 'cyan',
      已审核: 'green',
      已拒收: 'red',
      检验不合格: 'red',
      作废: 'default',
    };
    return map[status] || 'default';
  }

  function locationLabel(locationId: string) {
    return locationId ? locationMap.value[locationId] || locationId : '-';
  }

  function getTableAction(record: any) {
    const actions: any[] = [
      {
        label: '详情',
        onClick: () => openDetail(record),
      },
    ];
    if (['待分配', '待上架'].includes(record.inboundStatus)) {
      actions.push({
        label: '库位分配',
        auth: 'wms:inbound:allocate',
        onClick: () => openAllocateModal(true, { record }),
      });
    }
    if (['待检验', '待分配', '待上架'].includes(record.inboundStatus)) {
      actions.push({
        label: '作废',
        auth: 'wms:inbound:void',
        color: 'error',
        onClick: () => handleVoid(record),
      });
    }
    if (record.inboundStatus === '已审核') {
      actions.push({
        label: '重试同步',
        auth: 'wms:inbound:sync',
        onClick: () => handleResync(record),
      });
    }
    return actions;
  }

  // ------------------------------------------------------------ 详情

  const detailOpen = ref(false);
  const detail = ref<any>(null);

  async function openDetail(record: any) {
    detail.value = await getInboundDetail(record.id);
    detailOpen.value = true;
  }

  // ------------------------------------------------------------ 作废 / 同步

  function handleVoid(record: any) {
    let reason = '';
    Modal.confirm({
      title: `作废入库单 ${record.orderNo}？`,
      content: h('div', [
        h('p', '作废仅适用于尚未上架（未产生库存）的单据，作废后不可恢复。'),
        h('textarea', {
          class: 'ant-input',
          rows: 3,
          placeholder: '请填写作废原因（必填）',
          onInput: (e: any) => (reason = e.target.value),
        }),
      ]),
      okText: '确认作废',
      okType: 'danger',
      cancelText: '取消',
      async onOk() {
        if (!reason || !reason.trim()) {
          createMessage.warning('作废原因不能为空');
          return Promise.reject();
        }
        await voidInbound(record.id, reason.trim());
        createMessage.success('已作废');
        reload();
      },
    });
  }

  async function handleResync(record: any) {
    try {
      await resyncInbound(record.id);
      createMessage.success('同步成功');
    } finally {
      reload();
    }
  }

  function handleSuccess() {
    reload();
  }

  // ------------------------------------------------------------ 初始化

  /** 物料远程搜索（首次加载 50 条；输入关键字后按名称/编码模糊查询，避免 500 条上限） */
  async function loadMaterialOptions(keyword?: string) {
    materialSearching.value = true;
    try {
      const params: any = { pageNo: 1, pageSize: 50 };
      if (keyword && keyword.trim()) {
        // QueryGenerator 约定：值两侧带 * 表示 LIKE
        params.materialName = `*${keyword.trim()}*`;
      }
      const res: any = await defHttp.get({ url: '/wms/material/list', params });
      const options = (res?.records || []).map((m: any) => ({
        label: `${m.materialCode} ${m.materialName}`,
        value: m.id,
      }));
      // 合并已选项，避免搜索后已选物料在下拉里显示为原始 ID
      const selected = receiveForm.details
        .map((d) => d.materialId)
        .filter(Boolean)
        .map((id: string) => ({ value: id, label: materialLabelMap.value[id] || id }));
      const merged: Record<string, any> = {};
      [...selected, ...options].forEach((o) => (merged[o.value] = o));
      materialOptions.value = Object.values(merged);
      for (const o of options) {
        materialLabelMap.value[o.value] = o.label;
      }
    } catch (e) {
      // 物料加载失败不阻塞页面，收料时由后端返回文案
    } finally {
      materialSearching.value = false;
    }
  }

  function handleMaterialSearch(keyword: string) {
    if (materialSearchTimer) {
      clearTimeout(materialSearchTimer);
    }
    materialSearchTimer = setTimeout(() => loadMaterialOptions(keyword), 300);
  }

  onMounted(async () => {
    resetReceiveForm();
    await loadMaterialOptions();
    try {
      const res: any = await defHttp.get({ url: '/wms/location/list', params: { pageNo: 1, pageSize: 2000 } });
      const map: Record<string, string> = {};
      for (const loc of res?.records || []) {
        map[loc.id] = loc.locationCode;
      }
      locationMap.value = map;
    } catch (e) {
      // 库位编码仅用于详情展示，失败时退化为显示 ID
    }
  });
</script>

<style lang="less" scoped>
  .wms-inbound {
    padding: 16px;
  }

  .wms-inbound__receive {
    margin-bottom: 16px;
  }

  .wms-inbound__alert {
    margin-bottom: 12px;
  }

  .wms-inbound__actions {
    margin-top: 12px;
    display: flex;
    gap: 8px;
  }

  .wms-inbound__list-title {
    font-size: 16px;
    font-weight: 600;
  }
</style>
