/**
 * 永鑫 WMS 功能结构数据。
 *
 * 范围说明（2026-10-19）：方案 A（原材料线边柜 + 焊接线边仓）已移出项目范围。
 * 本文件中采购入库、供应商管理、生产订单、辅料仓、智能柜等节点已停用，
 * 保留作历史结构参考；当前产品入口以 WMS 菜单为准（采购/检验/供应商/订单菜单已收回）。
 *
 * 这里只描述结构本身（文本 + 子节点）。节点 id 不在这里维护：同一层级允许出现
 * 重名节点（如「采购入库」同时挂在入库管理和成品入库下），手写 id 极易冲突，
 * 因此 id 由 layout 层按层级路径自动生成，见 `src/layout/mindmap.ts`。
 */
export interface WmsNode {
  text: string
  children?: WmsNode[]
}

export const wmsData: WmsNode = {
  text: '永鑫WMS系统功能结构',
  children: [
    {
      text: '基础管理',
      children: [
        {
          text: '权限管理',
          children: [
            { text: '菜单权限' },
            { text: '用户权限' },
          ],
        },
        { text: '用户管理' },
        {
          text: '物料分类',
          children: [
            { text: '半成品（标准/定制）' },
            { text: '成品' },
            { text: '辅料（含料箱/周转盘）' },
          ],
        },
        { text: '物料信息' },
        { text: '供应商管理' },
        {
          text: '生产订单管理',
          children: [
            { text: '公司订单' },
            { text: '采购订单' },
            { text: '委外订单' },
          ],
        },
      ],
    },

    {
      text: '仓库管理',
      children: [
        {
          text: '仓库列表',
          children: [
            { text: '半成品仓' },
            { text: '成品仓' },
            { text: '辅料仓' },
          ],
        },
        {
          text: '库区/库位管理',
          children: [
            { text: '立库货位编码' },
            { text: '平库货位编码' },
            { text: '货位状态实时更新' },
            { text: '库位属性管理' },
          ],
        },
        {
          text: '库存预警管理',
          children: [
            { text: '最高库存预警' },
            { text: '最低库存预警' },
            { text: '安全库存预警' },
            { text: '多级库存预警' },
          ],
        },
        { text: '库存批次管理' },
        {
          text: '库存调拨',
          children: [
            { text: '成品调拨（支持跨仓库/跨区域调拨）' },
          ],
        },
        {
          text: '仓库盘点',
          children: [
            { text: '盘点数据：生成盈亏数据报表' },
          ],
        },
        { text: '库存查询' },
      ],
    },

    {
      text: '入库管理',
      children: [
        { text: '采购入库' },
        {
          text: '生产入库',
          children: [
            { text: '半成品入库' },
            { text: '原料退料入库' },
            { text: '委外原料退料入库' },
          ],
        },
        {
          text: '成品入库',
          children: [
            { text: '成品入库' },
            { text: '退货入库' },
            { text: '采购入库' },
            { text: '委外成品入库' },
          ],
        },
        {
          text: '其他入库',
          children: [
            { text: '五金入库' },
            { text: '报废入库' },
          ],
        },
      ],
    },

    {
      text: '出库管理',
      children: [
        { text: '采购退货' },
        {
          text: '生产出库',
          children: [
            { text: '生产领料' },
            { text: '生产补料' },
            { text: '委外生产领料' },
            { text: '委外生产补料' },
            { text: '半成品出库' },
          ],
        },
        {
          text: '成品出库',
          children: [
            { text: '销售出库' },
            { text: '采购退货出库' },
            { text: '报废出库' },
            { text: '委外发出出库' },
          ],
        },
        {
          text: '其他出库',
          children: [
            { text: '五金出库' },
            { text: '报废出库' },
          ],
        },
      ],
    },

    {
      text: '报表管理',
      children: [
        { text: '入库报表' },
        { text: '出库报表' },
        { text: '库存报表' },
        { text: '其他报表' },
      ],
    },

    { text: '智能柜管理' },

    {
      text: '立库/AGV/堆垛机集成',
      children: [
        { text: 'SCR机器人任务' },
        { text: 'WCS/RMS接口' },
      ],
    },
  ],
}