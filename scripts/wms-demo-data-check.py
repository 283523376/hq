#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""演示数据自查：跑一遍，打印当前库里「确实能演示」的工单号 / 入库单号 / 批号 / 物料。

为什么需要它
------------
演示文档里写的示例单号会随测试数据清理而失效；而「库里有这条数据」不等于
「页面上搜得到」——工单页和生产入库页是两套数据（工单存在不代表有入库单）。
所以本脚本两件事都做：查库（全量口径）+ 调接口（页面真实搜索结果）。

用法
----
    python scripts/wms-demo-data-check.py
    python scripts/wms-demo-data-check.py --json     # 只输出 JSON，便于其他脚本消费

依赖
----
- MySQL 客户端（本机不在 PATH，走绝对路径；可用 --mysql 覆盖）
- 后端在跑（默认 http://localhost:8081/jeecg-boot）；没起时自动跳过接口校验并提示
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime

DEFAULT_MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DEFAULT_DB = "yongxin_wms"
DEFAULT_API = "http://localhost:8081/jeecg-boot"
DEMO_USER = "admin"
DEMO_PASSWORD = "123456"


# ---------------------------------------------------------------- 数据库

def query(mysql_exe: str, db: str, sql: str) -> list[list[str]]:
    """执行 SQL，返回二维列表（首行为数据，不含表头）。"""
    result = subprocess.run(
        [mysql_exe, "-uroot", "-proot", "--default-character-set=utf8mb4", "-N", "-B", db, "-e", sql],
        capture_output=True,
    )
    if result.returncode != 0:
        raise RuntimeError("MySQL 查询失败：" + result.stderr.decode("utf-8", "replace")[:300])
    text = result.stdout.decode("utf-8", "replace").strip()
    if not text:
        return []
    return [line.split("\t") for line in text.splitlines()]


def collect_db(mysql_exe: str, db: str) -> dict:
    def count(sql: str) -> int:
        rows = query(mysql_exe, db, sql)
        return int(rows[0][0]) if rows else 0

    data: dict = {
        "counts": {
            "工单": count("SELECT COUNT(*) FROM wms_work_order WHERE deleted_at=0"),
            "生产入库": count("SELECT COUNT(*) FROM wms_production_inbound WHERE deleted_at=0"),
            "生产入库_草稿": count(
                "SELECT COUNT(*) FROM wms_production_inbound WHERE deleted_at=0 AND status='草稿'"),
            "库存行": count("SELECT COUNT(*) FROM wms_inventory"),
            "物料": count("SELECT COUNT(*) FROM wms_material WHERE deleted_at=0"),
            "仓库": count("SELECT COUNT(*) FROM wms_warehouse WHERE deleted_at=0"),
            "库位": count("SELECT COUNT(*) FROM wms_location WHERE deleted_at=0"),
            "设备": count("SELECT COUNT(*) FROM wms_device WHERE deleted_at=0"),
            "调拨单": count("SELECT COUNT(*) FROM wms_transfer_order WHERE deleted_at=0"),
            "盘点单": count("SELECT COUNT(*) FROM wms_stocktake_order WHERE deleted_at=0"),
            "预警规则": count("SELECT COUNT(*) FROM wms_warning_rule WHERE deleted_at=0"),
            "出库单": count("SELECT COUNT(*) FROM wms_outbound_order WHERE deleted_at=0"),
        },
    }

    # 工单示例：取 3 条已下达的
    linked = query(mysql_exe, db,
                   "SELECT w.work_order_no, w.material_name, w.plan_qty, w.work_order_status "
                   "FROM wms_work_order w WHERE w.deleted_at=0 AND w.work_order_status='已下达' "
                   "AND EXISTS (SELECT 1 FROM wms_production_inbound p "
                   "            WHERE p.work_order_no = w.work_order_no AND p.deleted_at=0) "
                   "ORDER BY w.work_order_no LIMIT 3")
    # 优先给「既工单页搜得到、又有入库单」的号码：站 1 讲完能直接切到站 2 看同一张单
    if not linked:
        linked = query(mysql_exe, db,
                       "SELECT work_order_no, material_name, plan_qty, work_order_status "
                       "FROM wms_work_order WHERE deleted_at=0 AND work_order_status='已下达' "
                       "ORDER BY work_order_no LIMIT 3")
    data["workOrders"] = [
        {"no": r[0], "material": r[1], "planQty": r[2], "status": r[3]} for r in linked
    ]

    # 入库单示例：草稿一条 + 闭环一条（已入库且同步成功），优先挑有对应工单的
    data["inbounds"] = [
        {"kind": "草稿", "inboundNo": r[0], "workOrderNo": r[1], "status": r[2],
         "taskStatus": r[3], "syncStatus": r[4]}
        for r in query(mysql_exe, db,
                       "SELECT inbound_no, work_order_no, status, task_status, sync_status "
                       "FROM wms_production_inbound WHERE deleted_at=0 AND status='草稿' "
                       "ORDER BY update_time DESC LIMIT 1")
    ] + [
        {"kind": "闭环", "inboundNo": r[0], "workOrderNo": r[1], "status": r[2],
         "taskStatus": r[3], "syncStatus": r[4]}
        for r in query(mysql_exe, db,
                       "SELECT inbound_no, work_order_no, status, task_status, sync_status "
                       "FROM wms_production_inbound WHERE deleted_at=0 AND status='已入库' "
                       "AND sync_status='同步成功' ORDER BY update_time DESC LIMIT 1")
    ]

    # 批号示例
    data["batches"] = [
        r[0] for r in query(mysql_exe, db,
                            "SELECT batch_no FROM wms_production_inbound "
                            "WHERE deleted_at=0 AND batch_no IS NOT NULL AND batch_no <> '' "
                            "GROUP BY batch_no ORDER BY MAX(update_time) DESC LIMIT 3")
    ]

    # 出库单示例：分类型统计 + 取 2 条（站 7 用来展示类型/状态筛选）
    data["outbounds"] = [
        {"no": r[0], "type": r[1], "status": r[2],
         "source": "—" if r[3] in ("NULL", "") else r[3]}
        for r in query(mysql_exe, db,
                       "SELECT outbound_no, outbound_type, status, source_bill_no "
                       "FROM wms_outbound_order WHERE deleted_at=0 "
                       "ORDER BY create_time DESC LIMIT 2")
    ]
    data["outboundTypes"] = [
        {"type": r[0], "count": int(r[1])}
        for r in query(mysql_exe, db,
                       "SELECT outbound_type, COUNT(*) FROM wms_outbound_order "
                       "WHERE deleted_at=0 GROUP BY outbound_type")
    ]

    # 物料示例：取一个有规格的自制物料
    data["material"] = [
        {"code": r[0], "name": r[1]} for r in query(
            mysql_exe, db,
            "SELECT material_code, material_name FROM wms_material "
            "WHERE deleted_at=0 AND material_code LIKE 'ZA.%' LIMIT 1")
    ]
    return data


# ---------------------------------------------------------------- 接口

def api_token(api: str) -> str | None:
    body = json.dumps({"username": DEMO_USER, "password": DEMO_PASSWORD,
                       "captcha": "1234", "checkKey": "1700000000000"}).encode()
    req = urllib.request.Request(api + "/sys/login", data=body,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            payload = json.loads(resp.read().decode("utf-8", "replace"))
        return payload["result"]["token"] if payload.get("success") else None
    except Exception:
        return None


def api_search(api: str, token: str, path: str, params: dict) -> tuple[int | None, list[str]]:
    qs = "&".join(f"{k}={urllib.parse.quote(str(v))}" for k, v in params.items())
    req = urllib.request.Request(api + path + "?" + qs, headers={"X-Access-Token": token})
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            payload = json.loads(resp.read().decode("utf-8", "replace"))
    except Exception:
        return None, []
    result = payload.get("result") or {}
    total = result.get("total")
    sample = []
    for rec in (result.get("records") or [])[:1]:
        sample.append(rec.get("workOrderNo") or rec.get("inboundNo") or "")
    return total, sample


def verify_by_api(api: str, data: dict) -> str:
    """返回状态说明；能校验就就地补上「页面搜得到吗」。"""
    token = api_token(api)
    if not token:
        return "后端未启动或登录失败，跳过接口校验（下面的示例仍来自数据库）"

    for wo in data["workOrders"]:
        total, _ = api_search(api, token, "/wms/work-order/list",
                              {"pageNo": 1, "pageSize": 5, "workOrderNo": wo["no"]})
        wo["pageHit"] = total

    for ib in data["inbounds"]:
        total, _ = api_search(api, token, "/wms/production/list",
                              {"pageNo": 1, "pageSize": 5, "workOrderNo": ib["workOrderNo"]})
        ib["pageHitByWorkOrder"] = total
        total2, _ = api_search(api, token, "/wms/production/list",
                               {"pageNo": 1, "pageSize": 5, "inboundNo": ib["inboundNo"]})
        ib["pageHitByInboundNo"] = total2
    return "已用接口校验「页面上是否搜得到」"


# ---------------------------------------------------------------- 输出

def render(data: dict, api_note: str) -> str:
    c = data["counts"]
    lines = []
    lines.append("=" * 68)
    lines.append("永鑫WMS 演示数据自查  " + datetime.now().strftime("%Y-%m-%d %H:%M"))
    lines.append("=" * 68)
    lines.append("")
    lines.append("数据量：" + " | ".join(f"{k} {v}" for k, v in c.items()))
    lines.append("")
    if c["库存行"] == 0:
        lines.append("⚠ 库存为 0：站 5「库存台账」导出行数会是 0，属正常展示。")
    if c["调拨单"] == 0 or c["盘点单"] == 0:
        lines.append("⚠ 调拨/盘点无单据：对应页面只做空态展示。")
    if c["出库单"] > 0:
        lines.append(f"ℹ 出库单 {c['出库单']} 张（多为自动生成的领料待办）：站 7 可演示类型筛选，别点任何写操作按钮。")
    lines.append("")

    lines.append("【站 1 生产工单】搜「工单号」：")
    for wo in data["workOrders"]:
        hit = wo.get("pageHit")
        mark = "" if hit is None else ("  ✅ 搜到" if hit else "  ❌ 搜不到")
        lines.append(f"  {wo['no']}  {wo['material'][:24]}  计划 {wo['planQty']}  {wo['status']}{mark}")
    lines.append("")

    lines.append("【站 2 生产入库】搜「工单号」或「入库单号」：")
    for ib in data["inbounds"]:
        byWo, byNo = ib.get("pageHitByWorkOrder"), ib.get("pageHitByInboundNo")
        mark = ""
        if byNo is not None:
            mark = "  ✅ 按入库单号搜到" if byNo else "  ❌ 按入库单号搜不到"
        lines.append(f"  [{ib['kind']}] {ib['inboundNo']}  工单 {ib['workOrderNo']}  "
                     f"{ib['status']}/{ib['taskStatus']}/{ib['syncStatus']}{mark}")
        if byWo is not None and not byWo:
            lines.append("        ⚠ 该工单在入库页搜不到（工单存在不代表有入库单），请按入库单号搜")
    lines.append("")

    if data["batches"]:
        lines.append("【站 6 批次追溯】可用批号：" + "、".join(b for b in data["batches"] if b))
    else:
        lines.append("【站 6 批次追溯】无可用批号（入库单缺批号），本站跳过")
    lines.append("")

    if data["material"]:
        m = data["material"][0]
        prefix = m["code"].rsplit(".", 1)[0] if "." in m["code"] else m["code"]
        lines.append(f"【站 4 物料】搜「{prefix}」→ {m['name']}（完整编码 {m['code']}）")
    lines.append("")

    if data["outbounds"]:
        types = "、".join(f"{t['type']} {t['count']} 张" for t in data["outboundTypes"])
        lines.append(f"【站 7 出库单】当前分布：{types}")
        for ob in data["outbounds"]:
            lines.append(f"  {ob['no']}  {ob['type']}  {ob['status']}  来源单 {ob['source']}")
        lines.append("  演示：用「出库类型」下拉筛选即可，列表非空比空态更有说服力")
    else:
        lines.append("【站 7 出库单】无出库单：本站只做空态 + 下拉展示")
    lines.append("")

    lines.append("说明：" + api_note)
    lines.append("把上面的单号/批号替换进《前端演示手工测试用例》对应站点即可。")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="永鑫WMS 演示数据自查")
    parser.add_argument("--mysql", default=DEFAULT_MYSQL, help="mysql 客户端绝对路径")
    parser.add_argument("--db", default=DEFAULT_DB, help="数据库名")
    parser.add_argument("--api", default=DEFAULT_API, help="后端地址（含 context-path）")
    parser.add_argument("--json", action="store_true", help="只输出 JSON")
    args = parser.parse_args()

    try:
        data = collect_db(args.mysql, args.db)
    except Exception as exc:  # noqa: BLE001
        print("读取数据库失败：" + str(exc), file=sys.stderr)
        print("提示：MySQL 客户端不在 PATH，确认 --mysql 指向的路径存在。", file=sys.stderr)
        return 1

    note = verify_by_api(args.api, data)
    if args.json:
        print(json.dumps({"data": data, "apiNote": note}, ensure_ascii=False, indent=2))
    else:
        print(render(data, note))
    return 0


if __name__ == "__main__":
    sys.exit(main())
