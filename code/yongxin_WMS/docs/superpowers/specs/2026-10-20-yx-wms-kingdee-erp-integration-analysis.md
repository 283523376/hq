# 永鑫WMS 对接金蝶云星空 ERP · 接入分析

日期：2026-10-20
环境：金蝶云星空「内部测试开发」
参考：https://openapi.open.kingdee.com/ApiHome

## 1. 结论先行

| 问题 | 结论 |
|---|---|
| 后端用什么登录？ | **WebAPI `LoginByAppSecret`**：`POST {host}/k3cloud/.../AuthService.LoginByAppSecret.common.kdsvc`，body 为 `acctID / username / appid / appsecret / lcid` |
| 用户给的 SSO 长/短链接做什么用？ | **只用于浏览器 HTML5 单点登录**（`/K3Cloud/html5/index.aspx?ud=...`），不是后端数据同步的鉴权方式 |
| 测试环境的 `signeddata` 能直接用吗？ | 不能作为稳定凭据。用现有 appSecret/timestamp 本地复算 **不等于** 给定的 `signeddata`，且时间戳会过期；且它属于浏览器 SSO 参数，不是 WebAPI 会话 |
| 测试环境地址 | `http://219.139.47.222:2021/k3cloud`（**HTTP**）、账套 `6a9aaaadaf7ee6`、集成用户 `WMS` |
| 生产安全 | 测试环境是明文 HTTP，与「全程 HTTPS」规则冲突。测试需明确授权；生产必须 HTTPS |
| 业务单据能否直接同步？ | 鉴权层已就绪；**已确认 `PRD_MO`（生产订单）与 `PRD_INSTOCK`（生产入库单）**，字段 Key 已探测；Save/Submit 参数与不良品字段仍待逐单确认 |

## 2. 鉴权层（已实现）

```
POST {host}/k3cloud/Kingdee.BOS.WebApi.ServicesStub.AuthService.LoginByAppSecret.common.kdsvc
Content-Type: application/json

{
  "acctID": "6a9aaaadaf7ee6",
  "username": "WMS",
  "appid": "<AppID>",
  "appsecret": "<AppSecret>",
  "lcid": 2052
}
```

- 成功：`LoginResultType=1`，响应体 `KDSVCSessionId` + `Set-Cookie: kdservice-sessionid=...`。
- 后续所有 `.common.kdsvc` 业务调用携带 `Cookie: kdservice-sessionid=...; ASP.NET_SessionId=...`。
- 会话按 30 分钟估算，WMS 端 **25 分钟主动刷新**；业务接口 401/403 时清会话、重登并重试一次。
- 代码位置：`org.jeecg.modules.wms.sync.WmsKingdeeAuthServiceImpl`；配置 `wms.sync.erp.kingdee.*`；
  凭据只从环境变量 `KINGDEE_ERP_*` 读取，日志只输出脱敏 appId。
- 地址归一化：`http(s)://host:port/k3cloud` 与 `http(s)://host:port` 两种写法都支持，不会出现 `/k3cloud/k3cloud`。
- HTTP 开关：`wms.sync.erp.kingdee.allow-http` 默认 **false**；测试环境如确需 HTTP，必须显式置 true（会打 WARN），生产禁止。

**真实握手结果（2026-10-20，需求方已授权测试环境 HTTP）**：

- `LoginByAppSecret` 返回 HTTP 200、`LoginResultType=1`，成功拿到 `kdservice-sessionid` 会话；
- 使用同一会话调用 `ExecuteBillQuery` 成功：
  - `PRD_MO`（生产订单）返回样例：`WORK00072 / 2024-06-19 / C / 物料 148085 / 2000`；
  - `PRD_INSTOCK`（生产入库单）返回样例：`SCRK00000022 / 2024-09-21 / C / 物料 148209 / 实收 1570、370 / 批号 157275、157276 / 仓库 101932`；
- `QueryBusinessInfo` 可返回完整元数据（`PRD_MO` 171KB、`PRD_INSTOCK` 66KB）；
- `STK_Inventory`、`BD_MATERIAL`、`BD_STOCK` 表单存在，但 `FBillNo` 不是它们的字段（需按各自元数据取键）。

> 凭据只存放在本地 gitignored 环境变量文件中，未写入仓库；本次握手未在日志中输出 appSecret/Cookie。

## 3. SSO 长/短链接的定位

- 短链：`{"dbid":"...","IdentifyID":"...","origintype":"SimPas"}` → `/K3Cloud/html5/index.aspx?ud=<Base64>`。
- 长链：`dbid/username/appid/signeddata/timestamp/lcid/origintype/...` → 同样以 Base64 放进 `ud`。
- 生成器的签名规则：`SHA256(sort([dbid, username, appid, appsecret, timestamp]).join(''))`。
- 用途：让用户从 WMS 点击链接直接进入金蝶（免登录）。**不建议**用于后端同步：
  - 时间戳会过期，链接是短时有效的；
  - 需要把 appSecret 放在生成端（服务端），前端只能拿生成好的链接；
  - 与 WebAPI 会话是两套东西。
- 若产品确实需要「打开金蝶」按钮：由后端用 appSecret 生成新时间戳的 `ud`，返回短时链接给前端；
  绝不把 appSecret 下发前端。

## 4. 业务对接设计

### 4.1 对象与方向（建议）

| WMS 对象 | 金蝶对象（已确认 FormId） | 方向 | 说明 |
|---|---|---|---|
| `wms_work_order` 生产工单 | **`PRD_MO` 生产订单** | ERP → WMS（查询/推送） | 工单号、物料、计划数量、批次 |
| `wms_production_inbound` 生产入库 | **`PRD_INSTOCK` 生产入库单** | WMS → ERP | 入库单号、工单号、实收/不良数量、批次、仓库 |
| `wms_inventory` 库存 | `STK_Inventory`（表单存在，字段键待确认） | WMS → ERP | 按物料+仓库+批次聚合；是否回写由业务确认 |
| `wms_material` / `wms_warehouse` | `BD_MATERIAL` / `BD_STOCK`（表单存在，字段键待确认） | ERP → WMS（主数据） | 编码映射，避免手工维护两套 |
| MES（ERP 子模块） | ERP 内 MES 对应单据/接口 | 双向 | 与 ERP 共用一套登录与会话 |

> `PRD_MO` / `PRD_INSTOCK` 已在测试环境实测可查；`STK_Inventory` / `BD_MATERIAL` / `BD_STOCK` 存在但需按各自元数据取键。

### 4.2 API 调用模式

- 查询：`POST .../DynamicFormService.ExecuteBillQuery.common.kdsvc`
  ```json
  {"data":{"FormId":"<FormId>","FieldKeys":"FBillNo,FDate,...",
           "FilterString":"FBillNo='...'","OrderString":"","TopRowCount":100}}
  ```
- 保存：`POST .../DynamicFormService.Save.common.kdsvc`
  ```json
  {"data":{"NeedUpDateFields":[],"IsDeleteEntry":true,"Model":{"FBillNo":"...", "...":"..."}}}
  ```
- 提交/审核：`POST .../DynamicFormService.Submit.common.kdsvc` / `Audit.common.kdsvc`
  ```json
  {"data":{"Numbers":["<FBillNo>"]}}
  ```
- 所有调用复用同一个 `kdservice-sessionid` 会话；批量接口注意单次数据量与并发。
- 幂等：以 WMS `inbound_no` / `work_order_no` 作为业务唯一键，先查后存；保存重复时按金蝶返回的错误码处理。
- 错误处理：解析 `Result.ResponseStatus.IsSuccess`、`Errors[]`（`FieldName`/`Message`/`ErrorCode`）；
  瞬时错误重试，业务错误转人工；错误日志不得包含 appSecret/Cookie。

### 4.3 字段映射（初始草案，待金蝶元数据确认）

| WMS | 金蝶（实测字段） |
|---|---|
| `work_order_no` | `PRD_MO.FBillNo` |
| `material_code` | `FMaterialId.FNumber`（物料内码/编码需按元数据转换） |
| `batch_no` | `FLot`（批号） |
| `plan_qty` | `PRD_MO` 分录 `FQty` |
| `inbound_no` | `PRD_INSTOCK.FBillNo` |
| `work_order_no`（入库） | `PRD_INSTOCK.FMoBillNo`（生产订单号） |
| `good_qty` | `PRD_INSTOCK.FRealQty`（实收数量） |
| `bad_qty` | **待确认**：`PRD_INSTOCK` 元数据中未见明确的不良数量字段，需要业务/顾问确认是否走自定义字段或另一张单 |
| `warehouse_code` | `FStockId`（仓库内码/编码需按元数据转换） |
| `work_order_status` | `FDocumentStatus`（A=创建、B=审核中、C=已审核等，按金蝶枚举） |
| `carrier_code` / `ident_code` | 备注 / 自定义字段（RFID、二维码） |

## 5. 实施步骤

1. **网络与安全**：确认测试环境是否可提供 HTTPS（或书面授权 HTTP 测试）；生产切 HTTPS。
2. **真实登录**：设置 `KINGDEE_ERP_BASE_URL/ACCT_ID/USERNAME/APP_ID/APP_SECRET`，
   验证 `LoginByAppSecret` 返回 `LoginResultType=1` 并拿到 `kdservice-sessionid`。
3. **元数据确认**：让金蝶实施顾问提供/查询目标单据的 `FormId`、字段 Key、必填项、保存/提交约束。
4. **工单同步**：先做 ERP → WMS 的 `ExecuteBillQuery`，按工单号 upsert，打通只读链路。
5. **生产入库回写**：WMS → ERP 的 `Save/Submit`，以 `inbound_no` 幂等，先单条再批量。
6. **库存/MES**：按业务确认是否回写即时库存；MES 复用 ERP 会话。
7. **补偿与监控**：沿用现有 `sync_status/retry_count/last_sync_error` + Quartz 补偿；
   日志脱敏，告警走站内消息。

## 6. 风险

1. 测试环境 HTTP：密钥/密码明文传输风险；生产必须 HTTPS，测试建议 VPN/内网。
2. 测试账号与 appSecret 已在聊天/本地记忆出现：建议联调完成后轮换。
3. 给定的 SSO `signeddata` 与现有 appSecret 不匹配：不要把它当 API 凭据。
4. 金蝶会话 30 分钟超时、并发风控：已做 25 分钟刷新与 401/403 重登，仍需观察真实环境。
5. FormId/字段 Key 未确认：在拿到元数据前，现有同步报文只是占位契约，不能直接上线。

---

## 7. WebAPI 能力边界（2026-11-02 实测补充）

联调与清理测试数据时反复踩到的接口限制，**动手前先看这一节，能省一轮试错**。

### 7.1 已提交（B）单据：WebAPI 删不掉，也没有撤销

`PRD_INSTOCK`（生产入库单）与 `STK_Miscellaneous`（其他入库单）在
「WebAPI 在线测试台」的操作列表里**只有「删除」一项**，没有撤销/反提交。

| 尝试 | 结果 |
| --- | --- |
| `Delete` | 生产入库：「单据状态为暂存、创建或重新审核的数据才允许删除」<br>其他入库：「只能删除创建、暂存、重新审核状态的数据」 |
| `Cancel`（作废） | 生产入库：「状态为创建或重新审核才允许作废」<br>其他入库：「已提交或审核的单据不允许作废」 |
| `UnSubmit` / `CancelSubmit` / `Reset`（`ExcuteOperation`） | 「没有此接口」 |

**结论**：B 状态单据只能在金蝶**前台**「撤销」→ 变回创建态 → 再删除。

**影响可控**：B 状态不参与即时库存计算、不生成账务凭证，留着不污染账务，可以晚点清。

### 7.2 生产订单（`PRD_MO`）：作废受「下游」限制

「下游」= **用料清单 `PRD_PPBOM` + 下级生产/委外订单**，两者都算：

- 只删 `PPBOM` 不够，还要确认没有下级工单
- 删单时金蝶会明确报「行号为1的分录关联了下级生产/委外订单 XXX」，这是定位下游最快的线索
- 新建单只要不下推、删掉自动生成的 `PPBOM`，A 状态即可作废

详见 `plans/2026-11-02-yx-wms-xt-002-验收报告.md` §3.5。

### 7.3 作废状态（`FCancelStatus`）是系统字段

`Update` 直接写 `FCancelStatus='B'` 报 `response_error: Unknown Error`——
只能通过 `Cancel` 业务操作产生，不能绕过业务校验伪造。

### 7.4 按组织覆盖的配置不会自动回落

`stock-code-by-org.<org>` 只要该组织块存在，就必须**显式配全**每个键
（`stereo`/`agile`/`scrap`），代码不会回落到通用 `stock-code.*`。
2026-11-02 实测：101 组织漏配 `scrap`，混合入库单的不良品回写直接失败。
