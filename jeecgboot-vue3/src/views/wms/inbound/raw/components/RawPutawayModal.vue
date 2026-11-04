<template>
  <a-modal
    v-model:open="open"
    :title="`原材料上架 - ${record?.orderNo || ''}`"
    width="900px"
    :confirm-loading="submitting"
    ok-text="确认上架"
    cancel-text="取消"
    destroy-on-close
    @ok="handleSubmit"
  >
    <a-spin :spinning="loading">
      <a-alert
        type="info"
        show-icon
        class="raw-putaway__alert"
        message="上架后立即写入库存（受托货记受托所有权）、置「已审核」并回写金蝶；实收为 0 的行按短装跳过、不入库。"
      />
      <a-table :data-source="rows" :columns="columns" :pagination="false" row-key="detailId" size="small" bordered>
        <template #bodyCell="{ column, record: row }">
          <template v-if="column.key === 'actualQuantity'">
            <a-input-number v-model:value="row.actualQuantity" :min="0" :precision="3" style="width: 100%" />
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
            <a-tag v-if="Number(row.actualQuantity) < Number(row.quantity)" color="orange">短装</a-tag>
            <span v-else>-</span>
          </template>
        </template>
      </a-table>
    </a-spin>
  </a-modal>
</template>

<script lang="ts" setup>
  import { ref, watch } from 'vue';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { getRawInboundDetail, listFreeLocations, putawayRawInbound } from '../raw.api';

  const props = defineProps<{ record: any }>();
  const emit = defineEmits(['success']);

  const open = defineModel<boolean>('open', { default: false });
  const { createMessage } = useMessage();

  const loading = ref(false);
  const submitting = ref(false);
  const rows = ref<any[]>([]);

  const columns = [
    { title: '物料', dataIndex: 'materialName', width: 200 },
    { title: '批次', dataIndex: 'batchNo', width: 150 },
    { title: '应收', dataIndex: 'quantity', width: 90 },
    { title: '实收', key: 'actualQuantity', width: 130 },
    { title: '短装', key: 'shortage', width: 80 },
    { title: '库位', key: 'locationId', width: 260 },
  ];

  watch(open, (visible) => {
    if (visible) {
      void loadRows();
    }
  });

  async function loadRows() {
    if (!props.record?.id) {
      return;
    }
    loading.value = true;
    try {
      const detail: any = await getRawInboundDetail(props.record.id);
      const list = (detail?.details || []).map((d: any) => ({
        detailId: d.id,
        materialId: d.materialId,
        materialName: `${d.materialCode || ''} ${d.materialName || ''}`.trim(),
        batchNo: d.batchNo,
        quantity: d.quantity,
        actualQuantity: d.actualQuantity ?? d.quantity,
        locationId: d.locationId || undefined,
        locationOptions: [] as { label: string; value: string }[],
      }));
      await Promise.all(
        list.map(async (row: any) => {
          try {
            const free: any = await listFreeLocations({ materialId: row.materialId });
            row.locationOptions = (free || []).map((l: any) => ({
              label: `${l.locationCode}（${l.status}）`,
              value: l.id,
            }));
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

  async function handleSubmit() {
    if (submitting.value) {
      return;
    }
    if (!rows.value.length) {
      createMessage.warning('该单据没有可上架的明细');
      return;
    }
    if (rows.value.every((r) => Number(r.actualQuantity) <= 0)) {
      createMessage.warning('整单实收不能全为 0');
      return;
    }
    const missing = rows.value.filter((r) => Number(r.actualQuantity) > 0 && !r.locationId);
    if (missing.length) {
      createMessage.warning(`还有 ${missing.length} 行未选择库位`);
      return;
    }
    submitting.value = true;
    try {
      await putawayRawInbound(props.record.id, {
        details: rows.value.map((r) => ({
          detailId: r.detailId,
          actualQuantity: Number(r.actualQuantity),
          locationId: r.locationId,
        })),
      });
      createMessage.success('上架成功，库存已入账并触发 ERP 回写');
      open.value = false;
      emit('success');
    } finally {
      submitting.value = false;
    }
  }
</script>

<style lang="less" scoped>
  .raw-putaway__alert {
    margin-bottom: 12px;
  }
</style>
