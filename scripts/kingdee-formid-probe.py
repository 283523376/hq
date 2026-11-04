#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""金蝶云星空 formId 探测工具（V2.4 RK-001 前置）。

用途：在配 5 个 `kingdee.form.*` 之前，用测试账套**实测**每个 formId 是否真实存在，
避免「填了个看起来对的标识，Save 时才炸」。

判定方式：对每个 candidate 调 ExecuteBillQuery。
  - 业务对象不存在 → 该 formId 错（返回里会提到 业务对象/FormId/标识）
  - 字段不存在     → 对象存在但字段名不对（记为「对象存在、字段待核」）
  - 正常返回       → 对象与字段都对

用法：
  # 1) 探测内置候选清单（不依赖数据库）
  python scripts/kingdee-formid-probe.py --candidates

  # 2) 探测系统配置里已填的 5 个 formId（读 wms_system_config）
  python scripts/kingdee-formid-probe.py --configured

  # 3) 探单个（临时验证顾问给的标识）
  python scripts/kingdee-formid-probe.py --form-id STK_InStock

凭据（只从环境变量读，不落盘、不回显）：
  KINGDEE_ERP_BASE_URL / KINGDEE_ERP_ACCT_ID / KINGDEE_ERP_USERNAME
  KINGDEE_ERP_APP_ID   / KINGDEE_ERP_APP_SECRET
  KINGDEE_ERP_ALLOW_HTTP=true   # 测试环境是 HTTP，必须显式允许

退出码：0=全部命中；1=有未命中；2=凭据缺失或登录失败。
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

LOGIN_PATH = "/k3cloud/Kingdee.BOS.WebApi.ServicesStub.AuthService.LoginByAppSecret.common.kdsvc"
QUERY_PATH = "/k3cloud/Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.ExecuteBillQuery.common.kdsvc"

MYSQL = r"C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe"

# 5 个配置项 → (中文名, 内置候选)
CONFIG_KEYS = [
    ("kingdee.form.purchase-order", "采购订单", ["PUR_PurchaseOrder"]),
    ("kingdee.form.purchase-inbound", "采购入库单", ["STK_InStock", "PUR_ReceiveBill"]),
    ("kingdee.form.purchase-return", "采购退货单", ["PUR_MRB", "STK_ReturnStock"]),
    ("kingdee.form.consigned-order", "受托加工订单", ["SUB_SUBREQORDER", "SUB_SubReqOrder"]),
    ("kingdee.form.consigned-inbound", "受托加工材料入库单", ["SUB_ReceiveBill", "SUB_SubStock"]),
]

# 探测字段候选：FBillNo 是单据通用字段；个别单据没有时退回 FID
PROBE_FIELDS = ["FBillNo", "FID"]

# 错误文案里出现这些片段 → 判定为「业务对象不存在」
OBJECT_MISSING_HINTS = ("业务对象", "formId", "FormId", "标识为", "Could not find form", "不存在")


def env(name: str, default: str = "") -> str:
    return (os.environ.get(name) or default).strip()


def color(text: str, code: str) -> str:
    if os.environ.get("NO_COLOR") or not sys.stdout.isatty():
        return text
    return f"\033[{code}m{text}\033[0m"


def http_post(url: str, payload: dict, cookie: str | None = None) -> tuple[int, str, str | None]:
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    # 金蝶登录响应可能是 text/plain；按字节读再解码，避免按 Content-Type 判断失败
    if cookie:
        req.add_header("Cookie", cookie)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            body = resp.read().decode("utf-8", errors="replace")
            set_cookie = resp.headers.get("Set-Cookie")
            return resp.status, body, set_cookie
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace"), None
    except Exception as e:  # 网络/超时
        return -1, f"请求失败：{e}", None


def login(base_url: str) -> str | None:
    acct = env("KINGDEE_ERP_ACCT_ID")
    user = env("KINGDEE_ERP_USERNAME")
    app_id = env("KINGDEE_ERP_APP_ID")
    app_secret = env("KINGDEE_ERP_APP_SECRET")
    payload = {
        "acctID": acct,
        "username": user,
        "appid": app_id,
        "appsecret": app_secret,
        "lcid": 2052,
    }
    status, body, set_cookie = http_post(base_url.rstrip("/") + LOGIN_PATH, payload)
    if status != 200:
        print(color(f"登录请求失败：HTTP {status} {body[:200]}", "31"))
        return None
    try:
        result = json.loads(body)
    except Exception:
        print(color(f"登录响应无法解析：{body[:200]}", "31"))
        return None
    if int(result.get("LoginResultType", -1)) != 1:
        # 只回显对方 Message，绝不回显 appsecret
        print(color(f"登录失败：LoginResultType={result.get('LoginResultType')} "
                    f"Message={result.get('Message')}", "31"))
        return None
    if set_cookie:
        return set_cookie.split(";")[0]
    # 少数部署把会话放在响应体的 KDSVCSessionId 里
    session = result.get("KDSVCSessionId") or result.get("kdservice-sessionid")
    return f"kdservice-sessionid={session}" if session else None


def probe(base_url: str, cookie: str, form_id: str) -> tuple[str, str]:
    """返回 (结论, 说明)。结论 ∈ ok / field / missing / error。"""
    url = base_url.rstrip("/") + QUERY_PATH
    last_msg = ""
    for field in PROBE_FIELDS:
        payload = {
            "FormId": form_id,
            # TopRowCount=0 + Limit 是翻页正确写法；这里只要一条
            "TopRowCount": 0,
            "Limit": 1,
            "StartRow": 0,
            "FieldKeys": field,
            "FilterString": "",
        }
        status, body, _ = http_post(url, payload, cookie)
        text = body.strip()
        # 正常返回是 JSON 数组（可能为空数组）
        if status == 200 and text.startswith("["):
            return "ok", f"字段 {field} 可用"
        last_msg = text[:300]
        lowered = text.lower()
        if "字段" in text or "field" in lowered:
            # 对象在、字段不在：换下一个探测字段再试
            continue
        if any(h.lower() in lowered for h in OBJECT_MISSING_HINTS):
            return "missing", last_msg
        return "error", last_msg
    # 所有探测字段都不存在，但对象显然在（否则会走 missing 分支）
    return "field", last_msg


def read_configured() -> dict[str, str]:
    """从 wms_system_config 读已填的 formId；读不到就当未配置。"""
    sql = ("SELECT config_key, IFNULL(config_value,'') FROM wms_system_config "
           "WHERE config_key LIKE 'kingdee.form.%';")
    try:
        out = subprocess.run(
            [MYSQL, "-uroot", "-proot", "yongxin_wms", "--default-character-set=utf8mb4", "-N", "-e", sql],
            capture_output=True, text=True, timeout=20,
        )
        rows = {}
        for line in (out.stdout or "").splitlines():
            if "\t" in line:
                k, v = line.split("\t", 1)
                rows[k.strip()] = v.strip()
        return rows
    except Exception as e:
        print(color(f"读系统配置失败（{e}），改用 --candidates 模式", "33"))
        return {}


def report(results: list[tuple[str, str, str, str]], mode: str) -> int:
    print()
    print("=" * 78)
    print(f"结果（{mode}）")
    print("=" * 78)
    misses = 0
    for name, form_id, verdict, detail in results:
        if verdict == "ok":
            mark = color("✔ 对象存在、字段可用", "32")
        elif verdict == "field":
            mark = color("△ 对象存在、探测字段不在（需在控制台确认字段名）", "33")
        elif verdict == "missing":
            mark = color("✘ 业务对象不存在", "31")
            misses += 1
        else:
            mark = color("! 调用异常", "31")
            misses += 1
        print(f"{form_id:<24} {name:<22} {mark}")
        if verdict != "ok":
            print(f"    └─ {detail}")
    print()
    if misses:
        print(color(f"有 {misses} 个未命中，别急着填进系统配置。", "31"))
        print("下一步：用 BOS 设计器看业务对象「标识」，或在金蝶前台打开该单据后按 F12 看 formId 参数。")
        return 1
    print(color("全部命中，可以填入 系统设置 → WMS 系统配置 → 金蝶单据。", "32"))
    print("提醒：formId 只证明「对象找得到」，Save 的必填字段仍需另配（见验收报告 §7.1）。")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="金蝶云星空 formId 探测")
    ap.add_argument("--candidates", action="store_true", help="探测内置候选清单")
    ap.add_argument("--configured", action="store_true", help="探测系统配置里已填的 formId")
    ap.add_argument("--form-id", action="append", default=[], help="探测指定 formId（可重复）")
    args = ap.parse_args()

    if not (args.candidates or args.configured or args.form_id):
        ap.print_help()
        return 2

    base_url = env("KINGDEE_ERP_BASE_URL")
    missing_env = [k for k in ("KINGDEE_ERP_BASE_URL", "KINGDEE_ERP_ACCT_ID",
                               "KINGDEE_ERP_USERNAME", "KINGDEE_ERP_APP_ID",
                               "KINGDEE_ERP_APP_SECRET") if not env(k)]
    if missing_env:
        print(color("缺少凭据环境变量：" + "、".join(missing_env), "31"))
        print("设置后重跑（只从环境变量读，不要写进仓库）：")
        print("  export KINGDEE_ERP_BASE_URL=http://219.139.47.222:2021")
        print("  export KINGDEE_ERP_ALLOW_HTTP=true   # 测试环境走 HTTP")
        print("  export KINGDEE_ERP_ACCT_ID=<账套ID>")
        print("  export KINGDEE_ERP_USERNAME=<集成用户>")
        print("  export KINGDEE_ERP_APP_ID=<appId>")
        print("  export KINGDEE_ERP_APP_SECRET=<appSecret>")
        return 2
    if base_url.lower().startswith("http://") and env("KINGDEE_ERP_ALLOW_HTTP").lower() != "true":
        print(color("目标是 HTTP 但未设 KINGDEE_ERP_ALLOW_HTTP=true（与后端同款安全检查）", "31"))
        return 2

    print(f"目标：{base_url}  账套：{env('KINGDEE_ERP_ACCT_ID')}  用户：{env('KINGDEE_ERP_USERNAME')}")
    cookie = login(base_url)
    if not cookie:
        return 2
    print(color("登录成功", "32"))

    targets: list[tuple[str, str]] = []  # (中文名, formId)
    mode = ""
    if args.form_id:
        mode = "指定 formId"
        targets = [(fid, fid) for fid in args.form_id]
    if args.candidates:
        mode = (mode + " + ") if mode else ""
        mode += "内置候选"
        for _key, name, cands in CONFIG_KEYS:
            targets.extend((name, c) for c in cands)
    if args.configured:
        mode = (mode + " + ") if mode else ""
        mode += "系统已配置"
        configured = read_configured()
        for key, name, _cands in CONFIG_KEYS:
            value = configured.get(key, "")
            if value:
                targets.append((f"{name}（{key}）", value))
            else:
                print(color(f"系统配置 {key} 为空", "33"))

    if not targets:
        print(color("没有可探测的 formId", "33"))
        return 1

    results = []
    for name, form_id in targets:
        verdict, detail = probe(base_url, cookie, form_id)
        results.append((name, form_id, verdict, detail))
    return report(results, mode)


if __name__ == "__main__":
    sys.exit(main())
