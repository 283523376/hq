# BD-002 生产工单 ERP 同步（P0）验收报告

日期：2026-10-22
依据：`docs/superpowers/specs/2026-10-21-yx-wms-bd-002-production-work-order-design.md`（评审通过待排期，2026-10-22 修订）
计划：`docs/superpowers/plans/2026-10-22-yx-wms-bd-002-production-work-order.md`
状态：**代码与自动化验收通过；真实金蝶联调待补（环境缺凭据）**

## 1. 交付范围

| 项 | 内容 |
|---|---|
| 工单来源 | 仅金蝶 `PRD_MO` 同步；C→已下达、A/B→待生产、D/未知整单跳过 |
| 双状态字段 | `erp_document_status`（原始值）+ `work_order_status`（可执行状态） |
| 手工入口 | `/add`、`/edit`、`DELETE /{id}` 已下线（返回“路径不存在”） |
| MES 兼容 | `/push` 保留；不覆盖 ERP 来源、不重开已关闭、不允许外部置“已关闭” |
| 同步任务 | `WmsWorkOrderSyncJob`：增量窗口逐日拉取 + 失败单重试 + 连续失败告警 + 未配置跳过 |
| 同步日志 | `wms_sync_log`：脱敏/截断/连续失败计数/成功清账；`V3.9.5_20` 补齐审计列 |
| 下游 | RK-002/RK-005/CK-001 接受“已下达”；CK-001 本地无单自动 ERP 回源 |
| 前端 | 工单页去 CRUD；同步弹窗（单号/日期窗口/上限，回显实际窗口）；详情抽屉（关联入库记录） |

## 2. 提交记录

- `14f98dde` 迁移（同步列/同步日志/菜单迁移/Job 种子）
- `7cb89de4` 实体与常量
- `7fb45b1d` push 状态 rank-max 合并与来源保护
- `a2d896f8` 同步日志服务
- `c69c1fa5` 金蝶同步门槛/窗口/多批次/留痕
- `eb3afd13` 下线手工端点 + ERP 同步/详情接口
- `0335620e` RK-002/CK-001 门禁与回源
- `896b1722` 工单同步 Job
- `74580d67` 前端工单页
- `c2dda6f8` 同步状态回写与同步日志审计列
- `1b7a50d3` 最终评审修复（重试收敛/告警/并发/日期校验/令牌脱敏/Job 跳过未配置）

## 3. 验收证据

### 3.1 自动化

| 项 | 命令 | 结果 |
|---|---|---|
| 后端全模块单测 | `bash /d/tools/m.sh -P dev test -DskipTests=false -pl jeecg-boot-module/jeecg-boot-module-wms` | **698/698 pass** |
| 前端类型构建 | `pnpm build`（jeecgboot-vue3） | **成功** |
| 单测重点 | 门槛 C/A/B/D、rank-max 不回退、来源保护、已关闭终态、同步状态回写、失败计数、并发重复插入、日期注入、脱敏、Job 三阶段/配置跳过 | 全部覆盖 |

### 3.2 迁移与运行态（MySQL `yongxin_wms`）

- `flyway_schema_history`：`3.9.5.19`、`3.9.5.20` 均 `success=1`；启动日志「flyway执行sql脚本失败」= **0**。
- 历史工单回填：17 行 `sync_status='无需同步'`（MES/手工来源）。
- 菜单：工单 `1910000000000000201` parent=`1910000000000000001`、sort=2.00；入库管理 redirect=`/wms/production/inbound`。
- 按钮：`211/212/213` del_flag=1 且已清角色授权；`214`（`wms:work-order:sync`）存在并授权 admin。
- Job：`1920000000000000340` 已注册，cron=`0 1/5 * * * ? *`；手动 execute 后日志「工单ERP同步任务跳过：金蝶未配置」，`wms_sync_log` 行数 5→5（未污染）。
- `/wms/work-order/add` 带登录态返回 `{"code":404,"message":"路径不存在…"}`；`/list` 返回新字段 `source/syncStatus/erpDocumentStatus/lastSyncTime`。
- 手动同步失败（金蝶未配置）返回 `金蝶工单查询失败：调用金蝶异常：URI is not absolute`，并落一条 `ERP_TO_WMS/KINGDEE/同步失败` 日志（脱敏可查）。

## 4. 未完成项与风险

| 项 | 说明 | 处理 |
|---|---|---|
| **真实金蝶联调** | 本机未配置 `KINGDEE_ERP_BASE_URL`/`appId`/`appSecret`，`PRD_MO` 拉取无法实际执行；Task 10 的「同步 FG001173 → 入库 → 再同步」未做 | 在配置齐全的测试环境执行一次，补录报文与截图；在此之前**不得对外宣称已完成上线验收** |
| 金蝶 `FDate` 过滤 | 逐日窗口依赖 `FDate` 支持范围过滤；仅通过单测/构造验证，未对真实表单验证 | 联调时先按单号同步，再开日期窗口 |
| `D` 状态语义 | 按评审 B1 整单跳过；`D` 的准确含义待金蝶顾问确认 | 确认后如需放行再改 `gateStatus` |
| `V3.9.5_19` 回滚注释 | 已应用迁移不可改（Flyway checksum），回滚段缺少角色授权恢复 INSERT | 已在脚本头说明；如需完整回滚，另写运维手册 |
| RK-002 正向断言 | `releasedWorkOrder_passesGate` 只断言“不是门槛异常”，全链路正向依赖 E2E | 金蝶联调时补生产入库全链路用例 |
| 前端浏览器实测 | 仅 `pnpm build` + ESLint；未启动浏览器逐页点击 | 上线前用真实浏览器走一遍工单页/详情抽屉 |

## 5. 回滚

1. 代码：`git revert 1b7a50d3..14f98dde`（按提交逆序）。
2. 数据：
   ```sql
   UPDATE wms_work_order SET sync_status='待同步' WHERE source IN ('MES','手工');
   ALTER TABLE wms_work_order
     DROP INDEX idx_wms_work_order_sync,
     DROP COLUMN erp_document_status, DROP COLUMN sync_status, DROP COLUMN retry_count,
     DROP COLUMN sync_fail_count, DROP COLUMN last_sync_error, DROP COLUMN last_sync_time;
   DROP TABLE IF EXISTS wms_sync_log;
   ```
3. 菜单/权限/Job：按 `V3.9.5_19` 脚本头注释回滚（角色授权需手工补回）。
4. 后端重启后必须 grep「flyway执行sql脚本失败」=0，并确认 `/wms/work-order/add` 恢复可达（仅回滚场景）。

## 6. 结论

代码、迁移、菜单/权限、自动化测试与运行态冒烟均通过；**真实金蝶联调是唯一未闭环项**，属环境凭据缺失，不是代码失败。补齐凭据并按 §4 执行后，可转入 V2.2 的下一流程（CK-002/CK-004）。
