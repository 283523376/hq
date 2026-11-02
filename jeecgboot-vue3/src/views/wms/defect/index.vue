<template>
  <div :class="`${prefixCls}`">
    <BasicTable @register="registerTable">
      <template #action="{ record }">
        <TableAction :actions="getActions(record)" />
      </template>
      <template #tableTitle>
        <span class="tip">废品仓当前不良品；报废后不可撤销，请核对数量与原因</span>
      </template>
    </BasicTable>

    <a-modal v-model:open="scrapOpen" title="报废出库" :confirmLoading="submitting" @ok="submitScrap">
      <a-form layout="vertical">
        <a-form-item label="物料">
          <span>{{ current?.materialCode }} {{ current?.materialName }}</span>
        </a-form-item>
        <a-form-item label="批次 / 库位">
          <span>{{ current?.batchNo || '—' }} / {{ current?.locationCode || '—' }}</span>
        </a-form-item>
        <a-form-item label="可报废数量">
          <span>{{ current?.availableQty }}</span>
        </a-form-item>
        <a-form-item label="本次报废数量" required>
          <a-input-number v-model:value="scrapQty" :min="0.001" :max="Number(current?.availableQty || 0)" style="width: 100%" />
        </a-form-item>
        <a-form-item label="报废原因" required>
          <a-textarea v-model:value="scrapReason" :rows="3" placeholder="如：来料报废 / 生产报废 / 超期待处理" />
        </a-form-item>
        <a-alert type="warning" show-icon message="报废将立即扣减废品仓库存并生成报废出库单，不可撤销" />
      </a-form>
    </a-modal>
  </div>
</template>

<script lang="ts" name="wms-defect" setup>
  import { ref } from 'vue';
  import { BasicTable, TableAction } from '/@/components/Table';
  import { useListPage } from '/@/hooks/system/useListPage';
  import { useDesign } from '/@/hooks/web/useDesign';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { columns, searchFormSchema } from './defect.data';
  import { list, scrap } from './defect.api';

  const { prefixCls } = useDesign('wms-defect');
  const { createMessage, createConfirm } = useMessage();

  const { tableContext } = useListPage({
    tableProps: {
      api: list,
      columns,
      rowKey: 'inventoryId',
      formConfig: { schemas: searchFormSchema },
      actionColumn: { width: 100 },
    },
  });
  const [registerTable, { reload }] = tableContext;

  const scrapOpen = ref(false);
  const submitting = ref(false);
  const current = ref<any>(null);
  const scrapQty = ref<number>(1);
  const scrapReason = ref('');

  function getActions(record: any) {
    return [
      {
        label: '报废',
        auth: 'wms:defect:scrap',
        disabled: Number(record.availableQty) <= 0,
        onClick: () => openScrap(record),
      },
    ];
  }

  function openScrap(record: any) {
    current.value = record;
    scrapQty.value = Number(record.availableQty) || 0;
    scrapReason.value = '';
    scrapOpen.value = true;
  }

  async function submitScrap() {
    if (!scrapReason.value.trim()) {
      createMessage.warning('请填写报废原因');
      return;
    }
    submitting.value = true;
    try {
      const result: any = await scrap({
        inventoryId: current.value.inventoryId,
        quantity: scrapQty.value,
        reason: scrapReason.value.trim(),
      });
      scrapOpen.value = false;
      createMessage.success(
        `报废成功：${result.outboundNo}，剩余 ${result.remainingQty}`,
      );
      reload();
    } catch (e: any) {
      // defHttp 已统一弹错误提示
    } finally {
      submitting.value = false;
    }
  }
</script>

<style lang="less" scoped>
  .tip {
    color: @text-color-secondary;
  }
</style>
