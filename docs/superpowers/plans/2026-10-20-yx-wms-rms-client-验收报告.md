# 永鑫WMS RMS 客户端（入库任务 V1.6）验收报告

日期：2026-10-20
依据：`RMS任务接口文档-V1.6.pdf`（`D:\资料\米塔机器人资料\`）
结论：**验收通过**。客户端、两个入库接口封装与错误码处理均已落地；真实 RMS 地址未接入，占位模式与本地假服务双向验证通过。

## 1. 交付内容

| 类 | 职责 |
|---|---|
| `sync/WmsRmsClient` | HTTP/POST + JSON，连接/读取超时默认 **30s**（`wms.sync.rms.timeout-ms` 可覆盖）；封装 `/api/v2/task/inbound`、`/api/v2/task/inbound/force`、`/api/v2/task/cancel`；统一解析 `code/msg`、HTTP 状态与传输异常，不向上抛 |
| `sync/WmsRmsResponse` | 一次调用的结构化结果（code/message/httpStatus/transportError/configured/rawBody） |
| `sync/WmsRmsErrorCode` | V1.6 错误码中文描述与分类：300000 可重试；300001（入库）/300012（强制入库）幂等成功 |
| `sync/HttpWmsRmsGateway` | 业务网关：未配置模拟受理；300000 退避重试；业务错误码带中文描述返回 |
| `service/IWmsProductionTaskService#forceInbound` + `WmsProductionController` | `POST /wms/production/{id}/force-inbound` 工作站强制入库；仅成品（RMS）可用；成功回写 `待上架/已受理` |
| `application-dev.yml` | `wms.sync.rms.timeout-ms: 30000`；地址/工作站编码保持空占位 |

## 2. 接口与报文（本地假服务实测）

**入库任务下发** `POST /api/v2/task/inbound`

```json
{
  "warehouse": "MITA",
  "taskNo": "RMS2112351907170066434",
  "stationCode": "MITA-STATION-01",
  "container": { "containerCode": "MITA-PROOF-001" },
  "priority": 1
}
```

**工作站强制入库** `POST /api/v2/task/inbound/force`

```json
{
  "stationCode": "MITA-STATION-01",
  "taskNo": "RMS2112351907170066434",
  "containers": ["MITA-PROOF-001"]
}
```

## 3. 验证结果

**自动化测试**：`jeecg-boot-module-wms` 全量 **538/538** 通过；新增
`WmsRmsErrorCodeTest`（4）、`WmsRmsClientTest`（7）、`HttpWmsRmsGatewayTest`（8），
并补 `WmsProductionTaskServiceImplTest` 强制入库分支与控制器测试。

**本地假服务（127.0.0.1:18080）实测**

| 场景 | 结果 |
|---|---|
| 正常下发 `MITA-PROOF-001` | RMS 收到正确报文；返回 0 → `待上架/已受理` |
| `300006` 容器编码已存在 | 记录转 `异常待人工`，`task_error=下发失败：300006 容器编码已存在` |
| `300001` 任务号已存在 | 按幂等成功处理 → `待上架/已受理`，不转人工 |
| 强制入库正常 | `POST /force` 报文正确；HTTP 200 / `success=true` |
| 强制入库 `300006` | `强制入库失败：容器编码已存在`（HTTP 200 + Result.code 500） |
| 未配置 RMS 地址 | 返回 `mocked=true` 的模拟受理，流程不中断 |

**proofshot 可视化验证**

- 页面：`rms-proof-run.html`（本地静态页，驱动真实 WMS 接口并回读 RMS 假服务请求）
- 截图：`rms-proof-top.png`（前 4 项 PASS）、`rms-proof-bottom.png`（300001 幂等、两段报文断言、RMS 实收报文）
- 结果：**7/7 PASS**；控制台无业务错误
- 说明：proofshot 的会话打包（`proofshot stop`）因 `agent-browser` 会话在首次启动时未正常建立而未能生成 SUMMARY；截图与接口证据已保存，`proofshot-artifacts/` 保留了会话元数据。

## 4. 约束与遗留

1. 真实 RMS 地址、工作站编码、仓库名仍需现场配置后联调；当前 `wms.sync.rms.url` 为空，走模拟受理。
2. 超时按需求方口径设为 **30s**；RMS 文档同时给出「数据返回超时默认 60s」，如需按文档口径可把 `timeout-ms` 调回 60000。
3. 强制入库仅对成品（RMS）开放；半成品走 WCS，调用会返回「仅成品（RMS）支持强制入库」。
4. `inbound/force` 的 `300012`（任务已呼叫机器人对接）按幂等成功处理；`300001` 在强制入库场景不视为成功（文档未把它列为 force 的成功码）。
