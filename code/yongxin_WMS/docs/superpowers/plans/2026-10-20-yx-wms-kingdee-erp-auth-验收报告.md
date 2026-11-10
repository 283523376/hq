# 永鑫WMS 金蝶云星空 ERP 鉴权接入验收报告

日期：2026-10-20
依据：金蝶云星空开放接口「第三方系统登录授权」LoginByAppSecret（https://openapi.open.kingdee.com/ApiHome）
结论：**接入完成（代码级）**。登录、会话缓存、ERP/MES 请求附加 Cookie、401/403 自动重登已实现；
真实登录还缺数据中心地址、账套 ID、集成用户名三项，拿到后只需设置环境变量即可联调。

## 1. 鉴权流程

```
POST {base-url}/k3cloud/Kingdee.BOS.WebApi.ServicesStub.AuthService.LoginByAppSecret.common.kdsvc
Content-Type: application/json

{
  "acctID": "<账套ID>",
  "username": "<集成用户>",
  "appid": "<AppID>",
  "appsecret": "<AppSecret>",
  "lcid": 2052
}
```

- 响应 `LoginResultType=1` 为成功，同时返回 `KDSVCSessionId` 与 `Set-Cookie`
  （`kdservice-sessionid`、`ASP.NET_SessionId`）。
- 后续业务接口携带 `Cookie: kdservice-sessionid=...`。
- 会话按金蝶默认 30 分钟估算，本实现 **25 分钟主动刷新**；业务接口 401/403 时清空会话并重登重试一次。

## 2. 代码变更

| 文件 | 内容 |
|---|---|
| `sync/IWmsKingdeeAuthService` | `isConfigured / applyAuth / invalidate / maskedAppId` |
| `sync/WmsKingdeeAuthServiceImpl` | LoginByAppSecret 登录、会话缓存、HTTPS 强制、脱敏日志 |
| `sync/HttpWmsProductionInboundSyncGateway` | ERP 与 MES 目标自动附加金蝶 Cookie；401/403 重登重试；WMS 目标不附加 |
| `application-dev.yml` | 新增 `wms.sync.erp.kingdee.*`，凭据全部用环境变量占位 |

## 3. 安全落实

1. **服务端专用**：appId/appSecret 只在后端读取；前端无任何配置或接口暴露。
2. **HTTPS 强制**：`base-url` 不是 `https://` 开头时拒绝登录并记录 ERROR，不发请求。
3. **日志脱敏**：只打印脱敏 appId（如 `312525****`）；不打印 appSecret、请求体、会话 Cookie；
   登录失败只记录 `LoginResultType` 与对方 `Message`。
4. **不入库**：仓库内 `application-dev.yml` 只有环境变量占位符，真实凭据未写入代码库；
   凭据保存在本机 `.claude` 记忆目录（非仓库）。

**启动前需设置的环境变量：**

```bash
KINGDEE_ERP_BASE_URL=https://<数据中心地址>
KINGDEE_ERP_ACCT_ID=<账套ID>
KINGDEE_ERP_USERNAME=<集成用户>
KINGDEE_ERP_APP_ID=<AppID>
KINGDEE_ERP_APP_SECRET=<AppSecret>
```

## 4. 验证

- 新增 `WmsKingdeeAuthServiceImplTest`（6）：登录报文、会话缓存、登录失败、HTTP 拒绝、未配置放行、脱敏、失效重登。
- 新增 `HttpWmsProductionInboundSyncGatewayAuthTest`（2）：ERP 请求携带 `Cookie`；鉴权失败不发请求。
- wms 模块全量 **551/551** 通过。
- 未配置金蝶时 `applyAuth` 直接放行，不影响现有模拟同步与生产入库流程。
- 后端已重新 `install` 并重启。

## 5. 待提供 / 后续

1. **数据中心地址（base-url）、账套 ID（acct-id）、集成用户名（username）**：缺少这三项无法完成真实登录；
   AppID/AppSecret 已在项目记忆中。
2. 金蝶业务接口的**接口路径与表单 ID**（生产入库单 Save/Submit 等）仍需按开放平台文档确认，
   当前同步报文仍是占位契约，本次只完成鉴权与会话层。
3. 建议把 `KINGDEE_ERP_*` 环境变量配置到部署环境（systemd/容器 secret），不要写进任何提交文件。
