#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""WMS 权限三方对齐体检：代码注解 ↔ 数据库权限点 ↔ 角色授权。

为什么需要
----------
2026-11-10 实错：V3.9.5_46 插设备管理菜单时只插了权限点、漏了给 admin 角色授权，
设备管理的新增/编辑按钮对 admin 也不可见——这类缺口不报错、只静默少功能，
上线前必须脚本兜底。本脚本与 V3.9.5_54 RBAC 角色一起交付（spec §3.3）。

三方
----
A 代码集：wms 模块全部 @RequiresPermissions(...) 的权限码（常量引用会先解析，
          解析不到的常量列为 WARN，不算失败）
B 权限点：sys_permission 里 perms LIKE 'wms:%' 的全集
C 角色集：admin（平台超管）与 wms_admin（RBAC 系统管理员）各自拿到的授权

检查规则（退出码 1 = 有缺口）
  A−B      为空：每个注解码都有权限点
  B−admin  为空：admin 全量
  B−wms_admin 为空：RBAC 管理员全量
  B−A      只打印不失败（DB 有码但代码未引用，通常是预留/页面权限）

用法
----
    python scripts/wms-perms-check.py
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
WMS_JAVA = REPO / "jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/src/main/java"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB_ARGS = ["-uroot", "-proot", "yongxin_wms", "--default-character-set=utf8mb4", "-N", "-B"]

ADMIN_ROLE_ID = "f6817f48af4fb3af11b9e8bf182f618b"
WMS_ADMIN_ROLE_ID = "191000000000009001"

# 已停用域（2026-10-19 原材料线边柜移出：采购入库/订单，代码保留、权限点随菜单撤除，
# 见 docs/superpowers/specs/2026-10-19-yx-wms-scope-reduction-line-side-design.md）。
# 这些注解码**没有**对应权限点是预期状态；恢复该域时应同时删除这里的豁免。
DEACTIVATED_PREFIXES = ("wms:inbound:", "wms:order:")

ANNOTATION = re.compile(r'@RequiresPermissions\(([^)]*)\)')
QUOTED = re.compile(r'"([\w.:-]+)"')
CONST_DEF = re.compile(r'String\s+([A-Z_][A-Z_0-9]*)\s*=\s*"([^"]+)"')


def mysql_query(sql: str) -> list[str]:
    out = subprocess.run(
        [MYSQL, *DB_ARGS, "-e", sql],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if out.returncode != 0:
        print(f"查询失败：{out.stderr.strip()}", file=sys.stderr)
        sys.exit(2)
    return [line for line in out.stdout.splitlines() if line]


def collect_constants() -> dict[str, str]:
    consts: dict[str, str] = {}
    for path in WMS_JAVA.rglob("*.java"):
        for m in CONST_DEF.finditer(path.read_text(encoding="utf-8", errors="replace")):
            consts.setdefault(m.group(1), m.group(2))
    return consts


def code_annotations() -> tuple[set[str], list[str]]:
    consts = collect_constants()
    codes: set[str] = set()
    warns: list[str] = []
    for path in WMS_JAVA.rglob("*.java"):
        text = path.read_text(encoding="utf-8", errors="replace")
        # 去掉注释，避免把 javadoc 里的 {@code @RequiresPermissions} 当注解
        text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
        text = re.sub(r"^\s*//.*$", "", text, flags=re.M)
        for m in ANNOTATION.finditer(text):
            args = m.group(1)
            # 数组 value={"a","b"}、logical=OR、单值、常量引用全部兼容：先提全部带引号串
            codes.update(QUOTED.findall(args))
            # 裸标识符（常量引用）→ 查常量表
            for tok in re.findall(r'(?<!["\w])([A-Z_][A-Z_0-9]*)(?!["\w])', args):
                if tok in ('VALUE', 'LOGICAL', 'OR'):  # 注解属性名/枚举值，非法权限码
                    continue
                if tok in consts:
                    codes.add(consts[tok])
                else:
                    warns.append(f"{path.name}: 常量 {tok} 解析不到字符串值")
    return codes, warns


def main() -> int:
    # Windows 控制台默认 GBK，emoji/中文会 UnicodeEncodeError，先切 UTF-8
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")

    code_set, warns = code_annotations()

    db_perms = {
        line.strip()
        for line in mysql_query(
            "SELECT perms FROM sys_permission WHERE perms LIKE 'wms:%' AND del_flag=0"
        )
    }
    role_perms = {
        role: {
            line.strip()
            for line in mysql_query(
                f"SELECT p.perms FROM sys_role_permission rp "
                f"JOIN sys_permission p ON p.id=rp.permission_id "
                f"WHERE rp.role_id='{role_id}' AND p.perms LIKE 'wms:%'"
            )
        }
        for role, role_id in (("admin", ADMIN_ROLE_ID), ("wms_admin", WMS_ADMIN_ROLE_ID))
    }

    if not role_perms["wms_admin"]:
        print("❌ wms_admin 角色不存在或授权为空（先应用 V3.9.5_54）")

    problems = 0
    miss_db = sorted(
        c for c in code_set - db_perms
        if not any(c.startswith(p) for p in DEACTIVATED_PREFIXES)
    )
    exempted = sorted(
        c for c in code_set - db_perms
        if any(c.startswith(p) for p in DEACTIVATED_PREFIXES)
    )
    if exempted:
        print(f"ℹ 停用域注解码（预期无权限点，不判失败）：{'、'.join(exempted)}")
    if miss_db:
        problems += 1
        print(f"❌ 代码注解了但库中没有权限点：{'、'.join(miss_db)}")
    else:
        print(f"✅ 代码注解 {len(code_set)} 个权限码，全部有权限点")

    for role in ("admin", "wms_admin"):
        miss = sorted(db_perms - role_perms[role])
        if miss:
            problems += 1
            print(f"❌ {role} 角色缺 {len(miss)} 个权限点：{'、'.join(miss)}")
        else:
            print(f"✅ {role} 角色：{len(db_perms)} 个权限点全量")

    # report-only：库里码比代码注解多（预留/页面权限，不算缺口）
    extra_db = sorted(db_perms - code_set)
    if extra_db:
        print(f"ℹ 库中有而代码未注解（不判失败）：{'、'.join(extra_db)}")
    for w in warns:
        print(f"⚠ {w}")
    if warns:
        print("⚠ 存在解析不到的常量引用，A 集可能不全——请人工确认上面的 WARN")

    if problems:
        print("\n修复：新增权限点的迁移里同时授 admin + wms_admin（+wms_manager，主数据管理码除外）。")
        return 1
    print("\n三方对齐：代码注解 ↔ 权限点 ↔ 角色授权 无缺口。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
