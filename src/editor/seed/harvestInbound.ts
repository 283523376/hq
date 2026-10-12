/**
 * 示例图：WMS 收货入库流程（参考图转录）。
 *
 * 这是本项目的验收基线。节点表来自设计文档 §9；连线表是逐条读图的结果，
 * 标注 ⚠待核对 的几条在读数时存在歧义（尤其长距离绕行与回边的起点），
 * 请在阶段 A 验收时对着原图重点核这几条。
 */
import type { FlowDoc } from '../types'

const MAIN = 'main' as const
const EXCEPTION = 'exception' as const

export const harvestInboundDoc: FlowDoc = {
  schemaVersion: 1,
  id: 'seed-harvest-inbound',
  name: 'WMS 收货入库流程',
  updatedAt: '2026-10-12T00:00:00.000Z',
  grid: {
    headerHeight: 44,
    stepColumnWidth: 64,
    rowHeight: 122,
    lanePadX: 14,
    lanePadY: 10,
  },
  stepCount: 12,
  lanes: [
    { id: 'lane-receiver', name: '收货员', color: 'blue', width: 210 },
    { id: 'lane-prep', name: '待切头/柄料', color: 'blue', width: 210 },
    { id: 'lane-inspect', name: '来料检验', color: 'orange', width: 190 },
    { id: 'lane-cabinet', name: '原材料线边柜', color: 'green', width: 230 },
    { id: 'lane-semi', name: '半成品立库', color: 'purple', width: 190 },
    { id: 'lane-exception', name: '异常处理', color: 'red', width: 200 },
  ],
  nodes: [
    { id: 'A01', text: '外购料收货单\n扫码/输入订单号', laneId: 'lane-receiver', step: 1, kind: 'process' },
    { id: 'A02', text: '调出订单信息', laneId: 'lane-cabinet', step: 1, kind: 'process' },
    { id: 'A03', text: '核对订单信息\n物料类型/规格\n重量/数量', laneId: 'lane-receiver', step: 2, kind: 'process' },
    { id: 'A04', text: '选择入库物料\n物料种类=1时\n自动选择', laneId: 'lane-cabinet', step: 2, kind: 'process' },
    { id: 'A05', text: '实物与订单不符\n提示核对', laneId: 'lane-exception', step: 2, kind: 'process' },
    { id: 'A06', text: '来料检验', laneId: 'lane-inspect', step: 3, kind: 'process' },
    { id: 'A07', text: '称重·计数', laneId: 'lane-exception', step: 3, kind: 'process' },
    { id: 'A08', text: '称重·计数', laneId: 'lane-prep', step: 4, kind: 'process' },
    { id: 'A09', text: '外购不合格品\n退回供应商', laneId: 'lane-exception', step: 4, kind: 'process' },
    { id: 'A10', text: '在原材料线边柜\n输入待切头/柄料\n良品/不良品数量', laneId: 'lane-prep', step: 5, kind: 'process' },
    { id: 'A11', text: '检查格子数量', laneId: 'lane-cabinet', step: 5, kind: 'process' },
    { id: 'A12', text: '格子数量不足\n提示是否仅收货', laneId: 'lane-exception', step: 5, kind: 'process' },
    { id: 'A13', text: '改刀料\n称重·计数\n插盘', laneId: 'lane-prep', step: 6, kind: 'process' },
    { id: 'A14', text: '上料并关门', laneId: 'lane-prep', step: 6, kind: 'process' },
    { id: 'A15', text: '自动开启柜门\n等待上料', laneId: 'lane-cabinet', step: 6, kind: 'process' },
    { id: 'A16', text: '生成批次号', laneId: 'lane-cabinet', step: 7, kind: 'process' },
    { id: 'A17', text: '上报良品与不良品\n数量/重量', laneId: 'lane-cabinet', step: 8, kind: 'process' },
    { id: 'A18', text: '更新柜内库存\n收货入库完成', laneId: 'lane-cabinet', step: 9, kind: 'process' },
    { id: 'A19', text: '同步\nERP/MES/WMS', laneId: 'lane-cabinet', step: 10, kind: 'process' },
    { id: 'A20', text: '提示\n是否继续入库', laneId: 'lane-cabinet', step: 11, kind: 'process' },
    { id: 'A21', text: '改刀料入\n半成品立库', laneId: 'lane-semi', step: 12, kind: 'process' },
  ],
  edges: [
    // --- 主流程（棕色实线）---
    { id: 'e-01-02', source: 'A01', target: 'A02', kind: MAIN },
    { id: 'e-01-03', source: 'A01', target: 'A03', kind: MAIN },
    { id: 'e-02-03', source: 'A02', target: 'A03', kind: MAIN },
    { id: 'e-03-04', source: 'A03', target: 'A04', kind: MAIN },
    { id: 'e-04-06', source: 'A04', target: 'A06', kind: MAIN },
    // ⚠待核对：A06 分出「合格待切头/柄料」进 A08、「合格改刀料」进 A13
    { id: 'e-06-08', source: 'A06', target: 'A08', kind: MAIN, label: '合格待切头/柄料' },
    { id: 'e-06-13', source: 'A06', target: 'A13', kind: MAIN, label: '合格改刀料' },
    { id: 'e-08-10', source: 'A08', target: 'A10', kind: MAIN },
    { id: 'e-10-11', source: 'A10', target: 'A11', kind: MAIN },
    { id: 'e-11-15', source: 'A11', target: 'A15', kind: MAIN, label: '数量足够' },
    { id: 'e-15-14', source: 'A15', target: 'A14', kind: MAIN },
    { id: 'e-14-16', source: 'A14', target: 'A16', kind: MAIN },
    { id: 'e-16-17', source: 'A16', target: 'A17', kind: MAIN },
    { id: 'e-17-18', source: 'A17', target: 'A18', kind: MAIN },
    { id: 'e-18-19', source: 'A18', target: 'A19', kind: MAIN },
    { id: 'e-19-20', source: 'A19', target: 'A20', kind: MAIN, label: '仍有待入库物料' },
    { id: 'e-20-19', source: 'A20', target: 'A19', kind: MAIN, label: '继续入库' },
    // ⚠待核对：A13 一路向下接到末步 A21（原图左侧那条长竖线）
    { id: 'e-13-21', source: 'A13', target: 'A21', kind: MAIN },
    // --- 异常流程（红色虚线）---
    // ⚠待核对：这条红虚线的起点我读作 A03（核对订单信息 → 实物与订单不符），也可能是 A04
    { id: 'e-03-05', source: 'A03', target: 'A05', kind: EXCEPTION },
    { id: 'e-06-07', source: 'A06', target: 'A07', kind: EXCEPTION, label: '不合格品' },
    { id: 'e-07-09', source: 'A07', target: 'A09', kind: EXCEPTION },
    { id: 'e-11-12', source: 'A11', target: 'A12', kind: EXCEPTION, label: '数量不足' },
    { id: 'e-12-19', source: 'A12', target: 'A19', kind: EXCEPTION, label: '仅收货，暂不入库' },
  ],
}