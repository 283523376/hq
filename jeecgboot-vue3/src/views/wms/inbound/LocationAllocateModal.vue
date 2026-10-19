<template>
  <BasicModal
    v-bind="$attrs"
    :title="`库位分配与上架 - ${record?.orderNo || ''}`"
    :width="900"
    :can-fullscreen="false"
    :mask-closable="false"
    destroy-on-close
    @register="registerModal"
    @ok="handlePutaway"
    @visible-change="handleVisibleChange"
  >
    <a-spin :spinning="loading">
      <div class="allocate-toolbar">
        <a-button type="primary" ghost :loading="allocating" v-auth="'wms:inbound:allocate'" @click="handleAutoAllocate">
          自动分配库位
        </a-button>
        <span class="allocate-toolbar__hint">
          分配规则：半成品 → 立库；辅料 → 线边柜。也可在下方逐行手工选择空闲库位。
        </span>
      </div>

      <a-alert
        v-for="(w, i) in warnings"
        :key="i"
        type="warning"
        show-icon
        :message="w"
        class="allocate-alert"
      />

      <a-table :data-source="rows" :columns="columns" :pagination="false" row-key="detailId" size="small" bordered>
        <template #bodyCell="{ column, record: row }">
          <template v-if="column.key === 'actualQuantity'">
            <a-input-number
              v-model:value="row.actualQuantity"
              :min="0"
              :precision="3"
              style="width: 100%"
              @change="handleActualChange(row)"
            />
          </template>
          <template v-else-if="column.key === 'locationId'">
            <a-select
              v-model:value="row.locationId"
              show-search
              allow-clear
              placeholder="选择空闲库位"
              :options="row.locationOptions"
              option-filter-prop="label"
              style="width: 100%"
            />
          </template>
          <template v-else-if="column.key === 'shortage'">
            <a-tag v-if="isShortage(row)" color="orange">短装</a-tag>
            <span v-else>-</span>
          </template>
        </template>
      </a-table>
    </a-spin>
  </BasicModal>
</template>

<script lang="ts" name="wms-location-allocate" setup>
  import { ref } from 'vue';
  import { BasicModal, useModalInner } from '/@/components/Modal';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { Modal } from 'ant-design-vue';
  import { autoAllocate, getInboundDetail, listFreeLocations, putaway } from './inbound.api';

  const emit = defineEmits(['success', 'register']);
  const { createMessage } = useMessage();

  const loading = ref(false);
  const allocating = ref(false);
  const record = ref<any>(null);
  const rows = ref<any[]>([]);
  const warnings = ref<string[]>([]);

  const columns = [
    { title: '物料', dataIndex: 'materialName', width: 180 },
    { title: '应收', dataIndex: 'quantity', width: 90 },
    { title: '实收', key: 'actualQuantity', width: 130 },
    { title: '短装', key: 'shortage', width: 80 },
    { title: '库位', key: 'locationId', width: 260 },
  ];

  const [registerModal, { setModalProps, closeModal }] = useModalInner(async (data) => {
    record.value = data?.record;
    warnings.value = [];
    setModalProps({ confirmLoading: false });
    await loadRows();
  });

  function isShortage(row: any) {
    return Number(row.actualQuantity) < Number(row.quantity);
  }

  /** 载入明细，并给每行拉取该物料类型对应的空闲库位 */
  async function loadRows() {
    if (!record.value) return;
    loading.value = true;
    try {
      const detail: any = await getInboundDetail(record.value.id);
      // 重载（如点完「自动分配库位」）时保留用户已录入的实收数量与手工选择的库位，
      // 只对后端新出现的行用默认值——否则用户改过的短装数量会被静默还原成应收数。
      const previous: Record<string, any> = {};
      for (const row of rows.value) {
        previous[row.detailId] = row;
      }
      const list = (detail?.details || []).map((d: any) => {
        const old = previous[d.id];
        return {
          detailId: d.id,
          materialId: d.materialId,
          materialName: d.materialName,
          quantity: d.quantity,
          actualQuantity: old ? old.actualQuantity : d.actualQuantity ?? d.quantity,
          locationId: (old && old.locationId) || d.locationId || undefined,
          locationOptions: [],
        };
      });
      await Promise.all(
        list.map(async (row: any) => {
          try {
            const free: any = await listFreeLocations({ materialId: row.materialId });
            row.locationOptions = (free || []).map((l: any) => ({
              label: `${l.locationCode}（${l.status}）`,
              value: l.id,
            }));
            // 已分配的库位不在空闲列表里时补进去，避免下拉显示为空
            if (row.locationId && !row.locationOptions.some((o: any) => o.value === row.locationId)) {
              row.locationOptions.unshift({ label: `${row.locationId}（已分配）`, value: row.locationId });
            }
          } catch (e) {
            row.locationOptions = [];
          }
        })
      );
      rows.value = list;
    } finally {
      loading.value = false;
    }
  }

  async function handleAutoAllocate() {
    if (!record.value) return;
    allocating.value = true;
    try {
      const res: any = await autoAllocate(record.value.id);
      warnings.value = res?.warnings || [];
      if (res?.allocatedCount > 0) {
        createMessage.success(`已自动分配 ${res.allocatedCount} 行`);
      }
      if (res?.unallocated?.length) {
        const names = res.unallocated.map((u: any) => u.materialName).join('、');
        warnings.value = [...warnings.value, `以下明细未能自动分配：${names}，请手工选择库位`];
      }
      await loadRows();
    } finally {
      allocating.value = false;
    }
  }

  function handleActualChange(_row: any) {
    // 仅用于触发视图刷新；实收=0 会在提交前二次确认
  }

  async function doPutaway() {
    setModalProps({ confirmLoading: true });
    try {
      await putaway(record.value.id, {
        details: rows.value.map((r) => ({
          detailId: r.detailId,
          actualQuantity: r.actualQuantity,
          locationId: r.locationId,
        })),
      });
      createMessage.success('上架成功，已自动审核并触发 ERP 同步');
      closeModal();
      emit('success');
      return true;
    } finally {
      setModalProps({ confirmLoading: false });
    }
  }

  function handlePutaway() {
    const missing = rows.value.filter((r) => Number(r.actualQuantity) > 0 && !r.locationId);
    if (missing.length) {
      createMessage.warning(`还有 ${missing.length} 行未选择库位`);
      return;
    }
    if (rows.value.every((r) => Number(r.actualQuantity) === 0)) {
      createMessage.warning('整单实收不能全为 0');
      return;
    }
    const shortage = rows.value.filter(isShortage);
    const zero = rows.value.filter((r) => Number(r.actualQuantity) === 0);
    if (shortage.length || zero.length) {
      Modal.confirm({
        title: '实收数量与应收不一致',
        content:
          `存在 ${shortage.length} 行短装` + (zero.length ? `（其中 ${zero.length} 行实收为 0，将跳过不入库）` : '') +
          '，确认按实收入库？',
        okText: '确认入库',
        cancelText: '返回修改',
        onOk: doPutaway,
      });
      return;
    }
    doPutaway();
  }

  function handleVisibleChange(visible: boolean) {
    if (!visible) {
      warnings.value = [];
    }
  }
</script>

<style lang="less" scoped>
  .allocate-toolbar {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 12px;
  }

  .allocate-toolbar__hint {
    color: rgba(0, 0, 0, 0.45);
    font-size: 12px;
  }

  .allocate-alert {
    margin-bottom: 8px;
  }
</style>
