# 实施计划：半成品生产入库改「生产订单下推」

- 设计：`specs/2026-10-27-yx-wms-production-inbound-push-design.md`（已批准 2026-10-27）
- 目标：让启用 BOM 版本管理的半成品能回写 `PRD_INSTOCK`

## Task 1 配置项

- `WmsKingdeeProperties` 增加：`pushInboundEnabled`(默认 true)、
  `pushFlowBillTypeMap`(Map，键 `__empty__` 表示空流程)、`defaultBusinessFlow`
- `application-dev.yml` 增加对应键与中文注释
- 单测：绑定默认值、Map 正常绑定（沿用 `WmsKingdeePropertiesBindingTest` 风格）

## Task 2 FormClient.push

- 新增 `push(String formId, Map<String,Object> data)`：报文裸 `{FormId, Data}`，解析 `SuccessEntitys[0].Id`
- 单测覆盖：成功取到目标单 Id、业务失败透传原文

## Task 3 Writer 改造

- 查 `PRD_MO` 补 `FBFLowId.FName`
- 流程规范化（空 → `__empty__`）→ 查映射表；缺失则 fail 并**原样**带出金蝶允许值说明
- `pushInboundEnabled && 物料启用BOM`（用已有判断或按产品类型=半成品）→ push → 以 FID 做 Save（补批号/数量/仓库）→ Submit
- 开关关闭或非半成品 → 走现有 Save 路径
- 单测 4 例：调用顺序、未知流程拒绝、push 失败不继续、开关降级

## Task 4 清理 BOM 专用标记

- 后端删 `WmsConstants.SYNC_BLOCK_BOM_PENDING` 及写入器里的前缀分支
- 前端删 `isBomBlocked/bomBlockedReason` 的 BOM 语义与置灰逻辑，恢复常规「同步失败→重试」
- 失败原因仍按金蝶原文展示（`last_sync_error` 截断保留首段）

## Task 5 文档

- `docs/wms/` 补充「半成品入库回写下推」一节：配置、前置条件、常见报错含义

## Task 6 真机联调（用户已授权）

1. 101 组织造测试生产订单（业务流程=生产直接入库流程）→ 审核 →（界面）执行至开工 → 领料
2. 触发 WMS 半成品入库回写，校验金蝶单据生成
3. 清理：删下推草稿 → 反执行至计划确认 → 反审核 → 删测试工单；查询确认为空

## 验收标准

- 876+ 单测全绿
- 真机：半成品入库单成功写进金蝶且状态可查
- 未知业务流程场景返回金蝶原文而非「BOM版本不能为空」
