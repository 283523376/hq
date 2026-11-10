# -*- coding: utf-8 -*-
"""V2.2（2026-10-21）：按评审意见改造流程图的数据源头。

三处口径：
  1. 工单来源统一改为「从 ERP 同步」而不是扫码工单：
     - BD-002 生产工单管理流程整条重写为 ERP（金蝶 PRD_MO）→ WMS 同步，
       WMS 不再手工新建 / 审核 / 下达；
     - RK-002 / RK-005 / CK-001 的首节点「扫码(生产)工单」改为「从ERP同步工单」。
  2. CK-002 成品销售出库：录入销售订单后自动同步给 ERP。
  3. CK-004 库存调拨：调拨单可在 WMS 创建，也可从 ERP 同步。

跑完本脚本后按流水线继续：
  parse_mmd.py → map_mmd.py → gen_wms_figs_mmd.py → build_wms_blueprint.py
"""
import json
import os
import shutil

HERE = os.path.dirname(os.path.abspath(__file__))
BACKUP = os.path.join(HERE, "_backup_v2")

META_V3 = [
    "版本：V2.2",
    "编制日期：2026年10月21日",
    "编制单位：广东楠柏莞科技有限公司",
    "适用项目：永鑫精工1.5亿支铣刀技改扩能项目——半成品立体仓、成品敏捷仓",
]

BD002 = {
    "code": "YX-WMS-BD-002",
    "name": "生产工单管理流程",
    "desc": "生产工单统一由ERP（金蝶PRD_MO）同步至WMS，WMS不再手工新建、审核或下达。"
            "系统按工单号校验并upsert（计划数量、状态、批次以ERP为准），同步成功后状态置为“已下达”，"
            "作为生产入库与领料出库的关键索引，全流程按工单追溯。",
    "lanes": ["计划员", "WMS系统", "ERP/MES", "异常处理"],
    "rows": [
        {"step": "1", "cells": ["登录，进入基础管理", "", "", ""]},
        {"step": "2", "cells": ["触发工单同步（按单号/时间范围）", "调用ERP工单接口",
                                 "返回生产订单（PRD_MO）", "接口异常→提示"]},
        {"step": "3", "cells": ["", "校验工单号与数据完整性", "", "字段缺失→提示"]},
        {"step": "4", "cells": ["", "按工单号upsert（计划量/状态/批次）", "", ""]},
        {"step": "5", "cells": ["", "状态置为“已下达”，可领料/入库", "", "同步失败→重试并告警"]},
        {"step": "6", "cells": ["查询工单执行情况", "展示工单关联的入库记录", "", "无记录→提示"]},
    ],
    "exceptions": [
        "接口异常：提示并记录日志，不生成工单。",
        "字段缺失：提示具体缺失字段，拒绝同步该工单。",
        "同步失败：自动重试3次，仍失败则告警并记录日志。",
        "无入库记录：提示“暂无入库记录”。",
    ],
    "category": "基础数据",
}

MMD_BD002 = """### YX-WMS-BD-002 生产工单管理流程

```mermaid
flowchart TD
    subgraph 计划员
        A1[登录<br>进入基础管理]
        A2[触发工单同步<br>按单号/时间范围]
        A3[查询工单执行情况]
    end

    subgraph WMS系统
        S1[调用ERP工单接口]
        S2{校验工单号<br>与数据完整性}
        S3[按工单号upsert<br>计划量/状态/批次]
        S4[状态置为已下达<br>可领料/入库]
        S5[展示工单关联的<br>入库记录]
    end

    subgraph ERP/MES
        E1[返回生产订单<br>PRD_MO]
    end

    subgraph 异常处理
        X1[接口异常提示]
        X2[字段缺失提示]
        X3[同步失败<br>重试并告警]
        X4[无记录提示]
    end

    A1 --> A2 --> S1
    S1 --> E1
    E1 -- 成功 --> S2
    E1 -- 失败 --> X1
    S2 -- 正常 --> S3
    S2 -- 异常 --> X2
    S3 --> S4
    S4 -- 成功 --> A3
    S4 -- 失败 --> X3
    A3 --> S5
    S5 -- 无记录 --> X4
```

"""

# 单元格 / 说明的逐条替换（只在命中时改，重复运行不会叠加）
CELL_RULES = {
    "YX-WMS-RK-002": [("扫码工单", "从ERP同步工单")],
    "YX-WMS-RK-005": [("扫码生产工单", "从ERP同步工单")],
    "YX-WMS-CK-001": [("扫码生产工单", "从ERP同步工单")],
    "YX-WMS-CK-002": [("录入销售订单", "从ERP获取销售订单信息")],
    "YX-WMS-CK-004": [("创建调拨单", "创建调拨单或从ERP同步")],
}

DESC_RULES = {
    "YX-WMS-RK-002": [
        ("生产完成后，操作员扫码工单，", "生产完成后，操作员从ERP同步工单，"),
    ],
    "YX-WMS-RK-005": [
        ("生产完成后录入不良品数量与原因，",
         "生产完成后，操作员从ERP同步生产工单，录入不良品数量与原因，"),
    ],
    "YX-WMS-CK-001": [
        ("领料人扫描生产工单发起领用，", "领料人从ERP同步生产工单发起领用，"),
    ],
    "YX-WMS-CK-002": [
        ("接到销售订单后，", "从ERP获取销售订单信息后，"),
    ],
    "YX-WMS-CK-004": [
        ("调拨需生成调拨单，记录调出与调入库位，",
         "调拨单可在WMS创建，也可从ERP同步；记录调出与调入库位，"),
    ],
}

MMD_CELL_RULES = {
    "YX-WMS-RK-002": [("A1[扫码工单]", "A1[从ERP同步工单]")],
    "YX-WMS-RK-005": [("A1[扫码生产工单]", "A1[从ERP同步工单]")],
    "YX-WMS-CK-001": [("A1[扫码生产工单]", "A1[从ERP同步工单]")],
    "YX-WMS-CK-002": [("A1[录入销售订单]", "A1[从ERP获取<br>销售订单信息]")],
    "YX-WMS-CK-004": [("A1[创建调拨单]", "A1[创建调拨单<br>或从ERP同步]")],
}


def backup():
    # 只备份一次：重复运行不能把 V2.1 备份覆盖成 V2.2
    if os.path.exists(os.path.join(BACKUP, "flows.json")):
        print("backup already exists, keep:", BACKUP)
        return
    os.makedirs(BACKUP, exist_ok=True)
    for name in ["flows.json", "flows.mmd", "mmd.json", "map_report.json", "capture.json"]:
        src = os.path.join(HERE, name)
        if os.path.exists(src):
            shutil.copyfile(src, os.path.join(BACKUP, name))
    for folder in ["figs", "out"]:
        src = os.path.join(HERE, folder)
        if os.path.isdir(src):
            dst = os.path.join(BACKUP, folder)
            if os.path.isdir(dst):
                shutil.rmtree(dst)
            shutil.copytree(src, dst)
    print("backup ->", BACKUP)


def apply_rules(flow):
    for old, new in CELL_RULES.get(flow["code"], []):
        # 先按原词替换；已替换过的单元格里不会再出现 old（新词不含 old）
        for row in flow["rows"]:
            row["cells"] = [c.replace(old, new) if c else c for c in row["cells"]]
    for old, new in DESC_RULES.get(flow["code"], []):
        flow["desc"] = flow["desc"].replace(old, new)


def build_flows():
    data = json.load(open(os.path.join(HERE, "flows.json"), encoding="utf-8"))
    out = []
    for f in data["flows"]:
        if f["code"] == "YX-WMS-BD-002":
            out.append(BD002)
            continue
        apply_rules(f)
        out.append(f)

    v3 = {
        "title": data["title"],
        "meta": META_V3,
        "categories": data["categories"],
        "summary_rows": data["summary_rows"],
        "flows": out,
    }
    with open(os.path.join(HERE, "flows.json"), "w", encoding="utf-8") as fp:
        json.dump(v3, fp, ensure_ascii=False, indent=1)
    print("flows.json V2.2:", len(out), "flows")


def build_mmd():
    src = open(os.path.join(HERE, "flows.mmd"), encoding="utf-8").read()
    lines = src.splitlines(keepends=True)
    header, sections, cur = [], [], None
    for line in lines:
        if line.startswith("### YX-WMS-"):
            cur = [line]
            sections.append(cur)
        elif cur is None:
            header.append(line)
        else:
            cur.append(line)

    out_sections = []
    for sec in sections:
        code = sec[0].split()[1]
        if code == "YX-WMS-BD-002":
            out_sections.append(MMD_BD002.splitlines(keepends=True))
            continue
        text = "".join(sec)
        for old, new in MMD_CELL_RULES.get(code, []):
            text = text.replace(old, new)
        out_sections.append([text])

    with open(os.path.join(HERE, "flows.mmd"), "w", encoding="utf-8") as fp:
        fp.write("".join(header) + "".join("".join(s) for s in out_sections))
    print("flows.mmd V2.2:", len(out_sections), "sections")


if __name__ == "__main__":
    backup()
    build_flows()
    build_mmd()
