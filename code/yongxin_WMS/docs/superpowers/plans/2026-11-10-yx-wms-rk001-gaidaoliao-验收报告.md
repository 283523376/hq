# RK-001「大改小」改「改刀料」验收报告

- 日期：2026-11-10
- 对应 spec：`docs/superpowers/specs/2026-11-10-yx-wms-rk001-gaidaoliao-design.md`
- 结论：**蓝图 V2.6 已生成、代码已改、单测与 UI 验收全绿。**

## 1. 需求与落地对照

| 需求 | 落地 | 证据 |
| --- | --- | --- |
| 「大改小」全部改为「改刀料」 | 蓝图 RK-001 + 三个前端文件 + 后端常量/文案 | 新 docx 第 8 页无「大改小」；UI 弹窗选项为「改刀料（CONVERT）」 |
| 改刀料与段差棒同口径，直接入半成品立库 | 后端 `receive()` 去除 CONVERT 分支，两类一律「待分配 + 待同步」 | 单测 20/20 通过；`receiveConvertRegistersAsPendingAllocateAndAllowsPutaway` |
| 去掉「大改小」「生成改制单」步骤 | 蓝图删两行（连带「车间」泳道、「投入不足」异常）；改制单菜单逻辑删除 | 新图 4 泳道 8 步；菜单实测无「改制单」 |

## 2. 蓝图 V2.6

- 新增 `wms-blueprint/flows_v26.py`（RK-001 重写，其余 17 条继承 V2.5）+ `apply_v26_changes.py`（备份到 `_backup_v2.5`）。
- 管线：`apply_v26` → `parse_mmd`（282/282 = 100%，0 处需人工关注）→ `map_mmd` → `gen_wms_figs_mmd`（问题 0 条）→ `svg2png` → `build_wms_blueprint`（18 条流程）→ `gen_designer_docs`（18 条、264 节点、未匹配 0）。
- 产物：`软件蓝图\永鑫WMS软件蓝图V2.6（评审中）-20261110.docx`（4.4 MB）。
- 页内校验：RK-001 页（第 8 页）「大改小/改制单/投入不足/车间」全部为 False，「改刀料/段差棒」为 True。
- 图校验：`figs/SWIM-YX-WMS-RK-001.png` 4 泳道（仓管员/WMS系统/堆垛机/异常处理）、8 步、无加工节点。

## 3. 代码变更

| 文件 | 变更 |
| --- | --- |
| `WmsRawInboundServiceImpl` | 删除 CONVERT「登记即终态」分支：两类一律 `待分配` + `待同步`；外购（含改刀料）强制供应商（要回写采购收料单）；两处历史保护分支保留并注明「V2.6 前的历史大改小」 |
| `WmsConstants` | `INBOUND_HANDLING_CONVERT` 注释改为改刀料语义；`CONVERSION_NO_PREFIX` 标注 deprecated（值不变，历史数据不迁移） |
| `raw/raw.data.ts` | 选项「改刀料（CONVERT）」；`isConvertOrder` → `isLegacyConvertOrder`（只标识历史行）；处理方式列对新单显示「—」（后端不落库该字段） |
| `raw/index.vue` | 提示文案改为同口径说明；上架按钮条件放开给所有「待分配」；删除「口径说明」弹窗 |
| `raw/components/RawReceiveModal.vue` | 口径说明文案、成功提示、供应商必填条件（外购一律必填） |
| `V3.9.5_57` | 改制单菜单 status=0（**实测无效**，平台菜单不按 status 过滤，见 §5 缺陷） |
| `V3.9.5_58` | 改制单菜单/按钮 `del_flag=1` 逻辑删除 + 清授权行（有效） |

## 4. 验收

| 项 | 结果 |
| --- | --- |
| 模块单测 | **1576 运行 / 0 失败**（`mvn -P dev test -pl jeecg-boot-module-wms`） |
| 定向单测 | `WmsRawInboundServiceImplTest` 20/20（含新用例：改刀料登记为待分配且可上架入库+回写） |
| 前端 lint | 4 个改动文件无 eslint 错误 |
| Flyway | `3.9.5.57 success=1`、`3.9.5.58 success=1`，启动日志 flyway 失败 0 条 |
| 权限三方对齐 | `wms-perms-check.py` exit=0（活跃权限点 93→89，改制 4 点已逻辑删除并纳入停用域豁免） |
| UI 冒烟 | 无错误提示；提示语正确；弹窗选项「改刀料（CONVERT）」；菜单无「改制单」；页面无「大改小」字样 |

## 5. 过程中的问题与处置

1. **外部图形引擎目录已搬家**：`D:\永鑫格子柜\yongxin-prd-master` 不存在 → 实际在 `D:\通用备份\永鑫格子柜\...`。
   已改 `build_wms_blueprint.py` / `gen_wms_figs.py` / `gen_wms_logicflow.py` 三处路径并记入根 `CLAUDE.md`。
2. **V57 用 status 停用菜单无效**：平台菜单 SQL 只认授权行与 `del_flag`，不按 `status` 过滤，UI 实测仍显示。
   追加 `V3.9.5_58` 改 `del_flag=1`（与线边柜方案 A 同惯例）后生效；V57 保留（已记账、作为附加标记无害）。
3. **验证期环境脏**：8081 被旧 JVM 占用（`TaskStop` 只杀了 shell 包装器），且旧 JVM 在 jar 被替换运行中
   触发 `NoClassDefFoundError: WmsMaterialGroupMapping`。清理 3 个残留 JVM 后单实例重启，错误消失。

## 6. 历史数据口径（未做迁移，需知悉）

`wms_inbound_order` 中 V2.6 之前登记的 CONVERT 单据保持原状（「已审核」+「无需同步」、不产生库存行）——
它们对应的原材料确实直送车间且已入 ERP 账，改状态会凭空造库存。列表里这类单在「处理方式」列显示
「改刀料（历史）」，不显示上架按钮，重试回写仍被明确拒绝（提示「V2.6 前的大改小登记单」）。
