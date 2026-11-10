/**
 * 永鑫 WMS 功能结构数据（V2.5，2026-11-09）。
 *
 * 口径：功能域视角，不是菜单截图。设计中有、尚未落地的功能（如设备集成）也保留；
 * 只排除已移出项目范围的节点（供应商、采购、检验、线边柜/智能柜、原材料/五金/包材）。
 * V2.5 追加「单据打印」域（DY-001，单独立项）：12 类单据按库存/生产/委外/销售四组。
 *
 * 这里只描述结构本身（文本 + 子节点）。节点 id 不在这里维护：同一层级允许出现
 * 重名节点，手写 id 极易冲突，因此 id 由 layout 层按层级路径自动生成，
 * 见 `src/layout/mindmap.ts`。
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
            { text: '角色权限' },
          ],
        },
        {
          text: '物料分类',
          children: [
            { text: '半成品' },
            { text: '成品' },
            { text: '辅料' },
          ],
        },
        {
          text: '物料信息',
          children: [
            { text: '编码/名称/规格' },
            { text: '金蝶编码映射' },
            { text: '批量导入导出' },
          ],
        },
        {
          text: '生产工单',
          children: [
            { text: 'ERP同步' },
            { text: '工单作废' },
          ],
        },
        {
          text: '库区库位管理',
          children: [
            { text: '立库货位编码' },
            { text: '敏捷仓料箱位' },
            { text: '库位状态' },
          ],
        },
        {
          text: '设备管理',
          children: [
            { text: '设备绑定' },
            { text: '设备状态' },
            { text: '告警阈值' },
          ],
        },
        {
          text: '系统配置',
          children: [
            { text: '库存预警阈值' },
            { text: '同步范围' },
            { text: '不良品默认仓' },
            { text: '保留期' },
          ],
        },
      ],
    },
    {
      text: '入库管理',
      children: [
        {
          text: '外购/受托原材料入库',
          children: [
            { text: '改刀料（直入立库）' },
            { text: '段差棒直入' },
          ],
        },
        {
          text: '生产入库',
          children: [
            { text: '半成品入库' },
            { text: '成品入库' },
            { text: '混合单不良品' },
          ],
        },
        {
          text: '不良品入库',
          children: [{ text: '废品仓默认库位' }],
        },
      ],
    },
    {
      text: '出库管理',
      children: [
        { text: '生产领料出库' },
        { text: '成品销售出库' },
        { text: '报废出库' },
      ],
    },
    {
      text: '库存管理',
      children: [
        {
          text: '库存查询',
          children: [
            { text: '库存台账' },
            { text: '全局关键词' },
            { text: '异步导出' },
          ],
        },
        { text: '批次追溯' },
        {
          text: '库存预警',
          children: [
            { text: '最低库存' },
            { text: '安全库存' },
            { text: '最高库存' },
          ],
        },
        {
          text: '库存调拨',
          children: [
            { text: '同库区移库' },
            { text: '跨库由ERP执行' },
          ],
        },
        {
          text: '仓库盘点',
          children: [
            { text: '定期盘点' },
            { text: '周期盘点' },
            { text: '差异复核' },
          ],
        },
      ],
    },
    {
      text: '不良品管理',
      children: [
        {
          text: '不良品台账',
          children: [
            { text: '报废处置' },
            { text: '处置留痕' },
          ],
        },
      ],
    },
    {
      text: '上位系统同步',
      children: [
        {
          text: '对账执行',
          children: [
            { text: '全量库存' },
            { text: '今日变动' },
            { text: '指定仓库' },
          ],
        },
        {
          text: '待推送包',
          children: [
            { text: '库存快照' },
            { text: '出入库流水' },
          ],
        },
        {
          text: '差异清单',
          children: [
            { text: '标记处理' },
            { text: '发起盘点' },
          ],
        },
        { text: '同步流水' },
        { text: '同步日志' },
      ],
    },
    {
      text: '操作留痕',
      children: [
        {
          text: '操作日志',
          children: [
            { text: '查询' },
            { text: '导出' },
            { text: '保留期清理' },
          ],
        },
      ],
    },
    {
      text: '设备集成',
      children: [
        {
          text: '任务下发',
          children: [
            { text: '入库任务' },
            { text: '出库任务' },
            { text: '移库任务' },
          ],
        },
        {
          text: '状态采集',
          children: [
            { text: '在线' },
            { text: '离线' },
            { text: '故障' },
          ],
        },
        { text: '设备告警' },
      ],
    },
    {
      text: '单据打印',
      children: [
        {
          text: '库存管理类',
          children: [
            { text: '盘亏单' },
            { text: '盘盈单' },
            { text: '其他出库单' },
            { text: '其他入库单' },
          ],
        },
        {
          text: '生产管理类',
          children: [
            { text: '生产补料单' },
            { text: '生产领料单' },
            { text: '生产退料单' },
          ],
        },
        {
          text: '委外管理类',
          children: [
            { text: '委外补料单' },
            { text: '委外领料单' },
            { text: '委外退料单' },
          ],
        },
        {
          text: '销售管理类',
          children: [
            { text: '销售出库单' },
            { text: '销售退货单' },
          ],
        },
      ],
    },
  ],
}
