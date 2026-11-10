# 外协出库（骨架）与样品出库 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在既有出库模块上加两个出库类型：外协出库（委外订单头同步为只读草稿，拣货与回写接口预留）与样品出库（建单→审批→拣货→扣库存，金蝶回写走适配器占位）。

**Architecture:** 两者都复用既有 `wms_outbound_order/detail/allocation` 骨架，仅靠 `outbound_type` 与两个 ERP 关联列区分；委外同步仿 `WmsKingdeeWorkOrderSyncServiceImpl` 的「常量 + filter 拼装 + syncLog 留痕」模式；金蝶回写抽成适配器接口，本期空实现，形态确认（E5）后接入。

**Tech Stack:** Spring Boot（jakarta）+ MyBatis-Plus + Flyway + Quartz + JUnit 5 + Mockito；前端 Vue 3 + Vben。

**Spec:** `docs/superpowers/specs/2026-10-26-yx-wms-outbound-subcontract-sample-stocktake-design.md` §5.1 / §5.2 / §6

## Global Constraints

- Maven 一律 `bash /d/tools/m.sh -P dev ...`；改 wms 模块后 `install -DskipTests` + 重启后端，否则新端点 404。
- Flyway 失败不拦启动：迁移后 grep 启动日志「flyway执行sql脚本失败」=0，且 `flyway_schema_history` 行 `success=1`。
- 出库类型常量：`OUTBOUND_TYPE_SUBCONTRACT = "外协出库"`、`OUTBOUND_TYPE_SAMPLE = "样品出库"`；**不得**与既有 `生产领料` / `销售出库` 混淆。
- 外协**不展开 BOM 子项、不拣货、不回写**（spec §5.1，原材料尚未纳入）。
- 金蝶回写适配器在本期**空实现**：未配置时必须明确报错「未配置」，不得静默成功。
- 提交：Conventional Commits `wms` scope，从 `D:\yongxin-WMS\JeecgBoot` 仓库根提交，结尾 `Co-Authored-By: Claude Code <noreply@anthropic.com>`。

## Review Focus

1. **组织过滤缺失导致灌入 org 100 历史委外单**（200+ 张）→ Task 3 测试：`org-filter` 为空时拒绝同步、零查询。
2. **重复同步产生重复草稿** → Task 3 测试：同 `FBillNo` 二次同步走更新而非新增。
3. **样品出库误触发销售出库回写**（既有网关按类型兜底走 `SAL_OUTSTOCK`）→ Task 5 测试：样品单不调用销售 Writer。
4. **空实现被当成成功** → Task 6 测试：未配置 `sample.stock-code` 时返回明确失败。
5. **草稿可被业务误操作推进**（草稿态本应只读）→ Task 3/7 测试：草稿不允许分配。

---

### Task 1: Flyway V3.9.5_25（出库 ERP 关联列）

**Files:**
- Create: `jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/V3.9.5_25__wms_subcontract_sample_outbound.sql`

**Interfaces:**
- Produces: `wms_outbound_order.erp_sub_order_no varchar(50) NULL`、`erp_mis_bill_no varchar(50) NULL`、索引 `idx_wms_outbound_erp_sub(erp_sub_order_no)`。

- [ ] **Step 1: 写迁移脚本**

```sql
-- 内容：外协/样品出库（spec 2026-10-26 §4.1）：出库单增加金蝶委外订单号与回写单据号
ALTER TABLE `wms_outbound_order`
    ADD COLUMN `erp_sub_order_no` varchar(50) NULL COMMENT '金蝶委外订单号（SUB_SUBREQORDER.FBillNo）',
    ADD COLUMN `erp_mis_bill_no` varchar(50) NULL COMMENT '回写生成的金蝶单据号',
    ADD INDEX `idx_wms_outbound_erp_sub` (`erp_sub_order_no`);
```

- [ ] **Step 2: 应用并验证**（重启后端后）

```bash
"/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe" -uroot -proot yongxin_wms -e \
"SELECT version,success FROM flyway_schema_history WHERE version='3.9.5.25'; SHOW COLUMNS FROM wms_outbound_order LIKE 'erp%';"
```
Expected: `success=1`；两列存在。
- [ ] **Step 3: Commit** `feat(wms): 出库单增加委外订单号与回写单据号列 (V3.9.5_25)`

### Task 2: 常量与配置

**Files:**
- Modify: `.../wms/constant/WmsConstants.java`
- Modify: `.../wms/sync/WmsKingdeeProperties.java`
- Test: `src/test/java/org/jeecg/modules/wms/constant/WmsConstantsOutboundTypesTest.java`

**Interfaces:**
- Produces:
  - `WmsConstants.OUTBOUND_TYPE_SUBCONTRACT = "外协出库"`、`OUTBOUND_TYPE_SAMPLE = "样品出库"`、`OUTBOUND_STATUS_DRAFT = "草稿"`；
  - `WmsKingdeeProperties.Subcontract`（`@Data static class`）：`String orgFilter;`、`String pickStatusDone;`；挂载 `private Subcontract subcontract = new Subcontract();`
  - `WmsKingdeeProperties.Sample`：`String stockCode;`；挂载 `private Sample sample = new Sample();`

- [ ] **Step 1: 失败测试**——断言 3 个常量字面值；断言 `new WmsKingdeeProperties().getSubcontract().getOrgFilter() == null` 且 `getSample().getStockCode() == null`（默认未配置）。
- [ ] **Step 2: 运行确认失败**（编译错误「找不到符号」）
  `bash /d/tools/m.sh -P dev test -DskipTests=false -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest=WmsConstantsOutboundTypesTest`
- [ ] **Step 3: 实现**（常量 + 两个嵌套配置类；`Subcontract`/`Sample` 均为空默认）
- [ ] **Step 4: 通过 + Commit** `feat(wms): 外协/样品出库常量与配置`

### Task 3: 委外订单头同步（只读草稿）

**Files:**
- Create: `.../service/IWmsSubcontractOrderSyncService.java`、`.../service/impl/WmsSubcontractOrderSyncServiceImpl.java`
- Create: `.../vo/WmsSubcontractSyncResultVO.java`（`int queried; int created; int updated; int voided; List<String> messages = new ArrayList<>();`）
- Test: `src/test/java/org/jeecg/modules/wms/service/impl/WmsSubcontractOrderSyncServiceImplTest.java`

**Interfaces:**
- Consumes: Task 2 配置；`WmsKingdeeFormClient.executeBillQuery(formId, fieldKeys, filter, top)`；`IWmsOutboundService`（`listPage` 不用，直接 `WmsOutboundOrderMapper` 查既有草稿 + `save/updateById`）。
- Produces: `WmsSubcontractSyncResultVO syncFromKingdee(int top)` ——
  - `subcontract.orgFilter` 为空 → **不查询**，`messages` 加「委外同步未配置组织过滤（外部依赖 E4），跳过」，返回；
  - 否则 filter = `<orgFilter> AND FDocumentStatus='C'`；`FORM_ID="SUB_SUBREQORDER"`，`FIELD_KEYS="FBillNo,FDocumentStatus,FDocumentType,FDate,FMaterialId.FNumber,FQty,FPickMtrlStatus"`；
  - 按 `FBillNo` upsert 一条 `wms_outbound_order`：`outboundType=外协出库`、`status=草稿`、`erpSubOrderNo=FBillNo`、`operator="SYSTEM"`、`requirementSource="ERP委外订单"`；**不写明细**；
  - `pickStatusDone` 非空且行 `FPickMtrlStatus` 等于该值 → 把对应草稿置 `status=已关闭`、`closeReason="ERP 已领料完成"`，`voided++`；
  - 每次同步写 `syncLog`（direction/target 用 `WmsConstants` 既有常量）。

- [ ] **Step 1: 失败测试**（mock formClient + orderMapper + syncLogService）：
  - `orgFilter` 空 → `formClient` 零调用、`queried==0`、messages 含「组织过滤」；
  - filter 含 `FDocumentStatus='C'` 与配置的 org 片段；
  - 新单号 → `save` 一次，捕获实体断言 `outboundType=外协出库`、`status=草稿`、`erpSubOrderNo` 正确、**明细未写**；
  - 已存在同 `erpSubOrderNo` 的草稿 → 走 `updateById` 不新增；
  - `pickStatusDone="3"` 且行状态 `3` → 该草稿置 `已关闭` 且 `voided==1`。
- [ ] **Step 2: 确认失败 → Step 3 实现 → Step 4 通过**
- [ ] **Step 5: Commit** `feat(wms): 委外订单同步为外协出库只读草稿`

### Task 4: 委外回写适配器（接口预留）

**Files:**
- Create: `.../sync/IWmsKingdeeSubcontractWriter.java`、`.../sync/WmsKingdeeSubcontractWriterStub.java`
- Test: `src/test/java/org/jeecg/modules/wms/sync/WmsKingdeeSubcontractWriterStubTest.java`

**Interfaces:**
- Produces: `WmsOrderSyncResult writePickMtrl(WmsOutboundOrder order, List<WmsOutboundDetail> details)`、`WmsOrderSyncResult writeReturnMtrl(WmsOutboundOrder order, List<WmsOutboundDetail> details)`——Stub 一律返回 `WmsOrderSyncResult` 失败，message=`"委外回写未实现：待原材料纳入项目范围（外部依赖 E6）"`。

- [ ] **Step 1: 失败测试**——两个方法均返回 `success=false` 且 message 含「E6」。
- [ ] **Step 2-4: 确认失败 → 实现 → 通过**
- [ ] **Step 5: Commit** `feat(wms): 委外领料/退料回写适配器（占位，E6 后实现）`

### Task 5: 样品出库建单

**Files:**
- Create: `.../dto/WmsOutboundSampleCreateDTO.java`（`String materialCode; BigDecimal qty; String batchNo; String remark;`）
- Modify: `.../service/IWmsOutboundService.java`（加 `WmsOutboundOrder createSample(WmsOutboundSampleCreateDTO dto);`）
- Modify: `.../service/impl/WmsOutboundServiceImpl.java`
- Test: `src/test/java/org/jeecg/modules/wms/service/impl/WmsOutboundSampleCreateTest.java`

**Interfaces:**
- Consumes: `WmsInventoryMapper`（选批次/库位可选）、既有 `WmsOutboundOrder`/`WmsOutboundDetail` 实体。
- Produces: `createSample` 建一张 `outboundType=样品出库`、`status=待分配` 的单 + 1 条明细（`requiredQty=qty`、`sourceEntryId=null`）；**不设置 `workOrderNo`/`salesOrderNo`**。

- [ ] **Step 1: 失败测试**：调用 `createSample` 后捕获 `save` 的订单实体，断言 `outboundType=样品出库`、`status=待分配`、`workOrderNo==null`；捕获明细断言 `materialCode`、`requiredQty` 与入参一致；断言**未调用**销售出库同步（`IWmsOutboundSyncService` 零交互）。
- [ ] **Step 2-4: 确认失败 → 实现（仿 `createPick` 结构）→ 通过**
- [ ] **Step 5: Commit** `feat(wms): 样品出库建单`

### Task 6: 样品回写适配器

**Files:**
- Create: `.../sync/IWmsSampleOutboundErpWriter.java`、`.../sync/WmsSampleOutboundErpWriterImpl.java`
- Test: `src/test/java/org/jeecg/modules/wms/sync/WmsSampleOutboundErpWriterImplTest.java`

**Interfaces:**
- Produces: `WmsOrderSyncResult write(WmsOutboundOrder order, List<WmsOutboundDetail> details)`——`properties.getSample().getStockCode()` 为空 → 返回失败，message=`"样品回写未配置 sample.stock-code（外部依赖 E5）"`；非空 → 仍返回失败 `"样品回写单据形态待客户确认（外部依赖 E5）"`（本期不真正发金蝶请求）。

- [ ] **Step 1: 失败测试**：未配置 → `success=false` 且 message 含 `E5`、`formClient` 零交互；配置了 `YF.01` → 仍 `success=false` 且 message 含「形态」。
- [ ] **Step 2-4: 确认失败 → 实现 → 通过**
- [ ] **Step 5: Commit** `feat(wms): 样品出库金蝶回写适配器（E5 前明确报错）`

### Task 7: 端点与前端

**Files:**
- Modify: `.../controller/WmsOutboundController.java`（加 `POST /sync-subcontract`、`POST /sample`）
- Modify: `jeecgboot-vue3/src/views/wms/outbound/outbound.data.ts`（`OUTBOUND_TYPE_OPTIONS` 增加两个类型）
- Modify: `jeecgboot-vue3/src/views/wms/outbound/outbound.api.ts`（两个新接口）
- Modify: `jeecgboot-vue3/src/views/wms/outbound/index.vue`（工具栏「同步委外订单」「新建样品出库」）
- Test: `src/test/java/org/jeecg/modules/wms/controller/WmsOutboundControllerSubcontractTest.java`

**Interfaces:**
- Consumes: Task 3 `syncFromKingdee(int)`、Task 5 `createSample(dto)`。
- Produces: `POST /wms/outbound/sync-subcontract?top=50` → `Result<WmsSubcontractSyncResultVO>`；`POST /wms/outbound/sample`（body `WmsOutboundSampleCreateDTO`）→ `Result<WmsOutboundOrder>`。

- [ ] **Step 1: 失败测试**：控制器方法存在、委外同步转发 `top`、样品建单转发 DTO（MockMvc 或直接调方法，照既有 controller 测试风格）。
- [ ] **Step 2-4: 确认失败 → 实现 → 通过**
- [ ] **Step 5: 前端 lint**：`cd jeecgboot-vue3 && npx eslint src/views/wms/outbound/`，Expected: 0 error
- [ ] **Step 6: Commit** `feat(wms): 外协/样品出库端点与前端入口`

### Task 8: 文档

**Files:**
- Create: `docs/wms/外协与样品出库说明.md`

- [ ] **Step 1: 写文档**——两个出库类型的行为边界（外协只建草稿、样品全流程但不回写）、配置项（`wms.sync.erp.kingdee.subcontract.org-filter` / `pick-status-done`、`wms.outbound.sample.stock-code`）、E4/E5/E6 就位后的启用步骤、验证方法。
- [ ] **Step 2: Commit** `docs(wms): 外协与样品出库说明`

---

## 执行注意

- Task 3/5/6 完成后 `install -DskipTests` + 重启后端，再做下一任务。
- 每个任务后跑模块全量：`bash /d/tools/m.sh -P dev test -DskipTests=false -pl jeecg-boot-module/jeecg-boot-module-wms`；已知 2 例既有环境失败（`WmsMaterialControllerTest.importExcel_*`，GBK 清单被 UTF-8 读），不计入本次回归判断。
