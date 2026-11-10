#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""状态枚举一致性体检：后端 WmsConstants 定义了哪些状态，前端筛选下拉必须都能选到。

为什么需要
----------
2026-10-30 实错：生产入库页的状态下拉漏了「草稿」，而库里 551 条草稿占绝大多数，
仓管员根本筛不出来；出库页同理漏了外协草稿。这类缺失不报错、不白屏，只是筛不到，
靠人工点页面很难发现。

用法
----
    python scripts/wms-status-options-check.py
    python scripts/wms-status-options-check.py --api   # 正则解析 WmsConstants，新增常量也会被检查

退出码 0 = 全部覆盖；1 = 有缺口（打印缺哪些）
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
CONSTANTS = REPO / "jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/constant/WmsConstants.java"
VIEWS = REPO / "jeecgboot-vue3/src/views/wms"

# 后端常量前缀 → 前端 options 所在文件与数组名
# 新增一个状态域时在这里登记一行，脚本才能覆盖到它
PAIRS: list[tuple[str, str, str]] = [
    ("PROD_STATUS", "production/production.data.ts", "PRODUCTION_STATUS_OPTIONS"),
    ("TASK_STATUS", "production/production.data.ts", "TASK_STATUS_OPTIONS"),
    ("SYNC_STATUS", "production/production.data.ts", "SYNC_STATUS_OPTIONS"),
    ("OUTBOUND_STATUS", "outbound/outbound.data.ts", "OUTBOUND_STATUS_OPTIONS"),
    ("TRANSFER_STATUS", "transfer/transfer.data.ts", "TRANSFER_STATUS_OPTIONS"),
    ("WORK_ORDER_STATUS", "workOrder/workOrder.data.ts", "WORK_ORDER_STATUS_OPTIONS"),
    ("SYNC_RUN_STATUS", "sync/sync.data.ts", "SYNC_RUN_STATUS_OPTIONS"),
    ("SNAPSHOT_MATCH", "sync/sync.data.ts", "SNAPSHOT_MATCH_STATUS_OPTIONS"),
    ("DIFF_TYPE", "sync/sync.data.ts", "DIFF_TYPE_OPTIONS"),
    ("DIFF_STATUS", "sync/sync.data.ts", "DIFF_STATUS_OPTIONS"),
]

# 这些后缀是「同一状态域的不同取值」，归到同一个 base
SUFFIXES = [
    "_DRAFT", "_PENDING_PUTAWAY", "_EXECUTING", "_INBOUND", "_EXCEPTION", "_CLOSED",
    "_NOT_DISPATCHED", "_ACCEPTED", "_FINISHED", "_FAILED", "_PENDING", "_RUNNING",
    "_SUCCESS", "_NOT_REQUIRED", "_PENDING_ALLOCATE", "_ALLOCATED", "_PENDING_REVIEW",
    "_REVIEW", "_REVIEWING", "_COMPLETED", "_PENDING_ERP", "_ADJUSTED", "_ARCHIVED",
    "_COUNTING", "_DIFF_CONFIRMED", "_RELEASED", "_IN_PROGRESS", "_PENDING_AUDIT",
    "_AUDITED", "_REJECTED", "_DISPATCHED",
]

# 中文状态才需要前端下拉；英文/编码类（KCZT01_SYS 等）跳过
CJK = re.compile(r"[一-鿿]")


def backend_values(prefix: str) -> set[str]:
    text = CONSTANTS.read_text(encoding="utf-8")
    values: set[str] = set()
    for match in re.finditer(r'String\s+(\w+)\s*=\s*"([^"]+)"', text):
        name, value = match.group(1), match.group(2)
        if not CJK.search(value):
            continue
        base = name
        for suffix in SUFFIXES:
            if base.endswith(suffix):
                base = base[: -len(suffix)]
                break
        if base == prefix or name.startswith(prefix + "_"):
            values.add(value)
    return values


def frontend_values(rel_path: str, array_name: str) -> set[str]:
    path = VIEWS / rel_path
    if not path.exists():
        return set()
    text = path.read_text(encoding="utf-8")
    idx = text.find(array_name)
    if idx < 0:
        return set()
    tail = text[idx: idx + 2000]
    # 只取该数组字面量内的字符串（到第一个 `]` 为止）
    end = tail.find("]")
    body = tail[: end if end > 0 else len(tail)]
    return set(re.findall(r"'([^']+)'", body))


def main() -> int:
    if not CONSTANTS.exists():
        print(f"找不到常量文件：{CONSTANTS}", file=sys.stderr)
        return 2

    problems = 0
    for prefix, rel_path, array_name in PAIRS:
        back = backend_values(prefix)
        front = frontend_values(rel_path, array_name)
        missing = sorted(back - front)
        extra = sorted(front - back)
        if missing:
            problems += 1
            print(f"❌ {array_name}（{rel_path}）缺少下拉项：{'、'.join(missing)}")
        elif extra:
            print(f"⚠ {array_name} 有多余项（后端无对应常量）：{'、'.join(extra)}")
        else:
            print(f"✅ {array_name}：{len(back)} 项全覆盖")

    if problems:
        print("\n修复：在对应 *.data.ts 的 OPTIONS 数组里补上缺失值（顺序按状态机走）。")
        return 1
    print("\n全部状态域的前端筛选下拉都覆盖了后端常量。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
