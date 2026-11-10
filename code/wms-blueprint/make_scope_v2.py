# -*- coding: utf-8 -*-
"""项目范围收窄（2026-10-19）：移除「原材料线边柜 + 焊接线边仓」（方案 A），
只保留「半成品立体仓 + 成品敏捷仓」（方案 B）。

做三件事：
  1. 备份 V1 数据（flows.json / flows.mmd / mmd.json / map_report.json / capture.json / figs/ / out/）
     到 _backup_v1/，可整体回滚；
  2. 生成 V2 的 flows.json：去除 6 条方案 A 专属流程，改写 3 条混合流程
     （BD-002 改名「生产工单管理流程」、CK-001 改名「生产领料出库流程（半成品）」、
     BD-001 去掉辅料/格子柜表述），并微调保留流程中残留的线边柜措辞；
  3. 生成 V2 的 flows.mmd：过滤被移除流程的小节，重写 BD-002 / CK-001 的 Mermaid，
     并同步微调节点文本。

跑完本脚本后按流水线继续：parse_mmd.py → map_mmd.py → gen_wms_figs_mmd.py → build_wms_blueprint.py
"""
import json
import os
import shutil

HERE = os.path.dirname(os.path.abspath(__file__))
BACKUP = os.path.join(HERE, "_backup_v1")

REMOVE = [
    "YX-WMS-RK-001",  # 采购入库流程（原料/外购料）
    "YX-WMS-RK-003",  # 委外与退货入库流程
    "YX-WMS-RK-004",  # 线边柜自切料入库流程
    "YX-WMS-CK-003",  # 采购退货与报废出库流程
    "YX-WMS-KC-003",  # 格子库存调整流程（拆分/合并）
    "YX-WMS-SB-003",  # 开柜记录上报流程
]

# ---------------------------------------------------------------- 改写后的混合流程

BD002 = {
    "code": "YX-WMS-BD-002",
    "name": "生产工单管理流程",
    "desc": "计划员在WMS维护生产工单（工单号、物料、产品类型、计划数量、批次），工单可手工创建，也可由MES推送同步。工单审核后下达，作为生产入库与领料出库的关键索引，全流程按工单追溯。",
    "lanes": ["计划员", "WMS系统", "ERP/MES", "异常处理"],
    "rows": [
        {"step": "1", "cells": ["登录，进入基础管理", "", "", ""]},
        {"step": "2", "cells": ["新建生产工单（工单号/物料/产品类型/计划数量）", "校验工单号唯一性", "", "工单号重复→提示"]},
        {"step": "3", "cells": ["提交工单审核", "状态置为“待审核”", "", ""]},
        {"step": "4", "cells": ["审核通过", "状态置为“已下达”", "", ""]},
        {"step": "5", "cells": ["", "按工单号upsert", "同步工单至MES/接收MES推送", "同步失败→重试并告警"]},
        {"step": "6", "cells": ["查询工单执行情况", "展示工单关联的入库记录", "", "无记录→提示"]},
    ],
    "exceptions": [
        "工单号重复：提示“工单号已存在”。",
        "与MES同步失败：自动重试3次，仍失败则告警并记录日志。",
    ],
    "category": "基础数据",
}

RK005 = {
    "code": "YX-WMS-RK-005",
    "name": "不良品入库流程",
    "desc": "生产完成后录入不良品数量与原因，WMS直接写入废品仓默认库位库存（不做库位分配）；不良品台账由生产入库记录的bad_qty/defect_remark承载；随生产入库同步ERP/MES，失败自动重试并告警。",
    "lanes": ["操作员", "WMS系统", "ERP/MES", "异常处理"],
    "rows": [
        {"step": "1", "cells": ["扫码生产工单", "调出工单物料信息", "", "工单无效→提示"]},
        {"step": "2", "cells": ["录入不良数量与原因", "校验数量合理性", "", "数量超出工单范围→提示"]},
        {"step": "3", "cells": ["", "校验废品仓默认库位", "", "未配置→提示联系管理员"]},
        {"step": "4", "cells": ["", "写入废品仓默认库位库存", "", ""]},
        {"step": "5", "cells": ["", "记录不良品台账（生产入库记录）", "", ""]},
        {"step": "6", "cells": ["", "发送同步报文", "接收并更新库存", "同步失败→重试并告警"]},
    ],
    "exceptions": [
        "工单无效：提示“工单号无效”。",
        "数量超出工单范围：提示“数量超出工单范围”。",
        "废品仓未配置：提示联系管理员。",
        "同步失败：自动重试3次，仍失败则告警。",
    ],
    "category": "入库管理",
}

CK001 = {
    "code": "YX-WMS-CK-001",
    "name": "生产领料出库流程（半成品）",
    "desc": "领料人扫描生产工单发起领用，WMS调出需求物料与数量，检查半成品立库库存。库存足够时按需求数量分配立库货位，下发堆垛机任务出库。领料人取料确认后，按工单需求数量扣减库存、生成领用记录，同步ERP/MES/WMS。库存不足或异常时提示并转人工。",
    "lanes": ["领料人", "半成品立库", "WMS系统", "异常处理"],
    "rows": [
        {"step": "1", "cells": ["扫码生产工单", "调出工单需求物料与数量", "校验工单有效性", "工单不存在→提示"]},
        {"step": "2", "cells": ["", "检查立库库存", "校验库存是否足够", "库存不足→提示并转人工"]},
        {"step": "3", "cells": ["", "分配立库货位", "按工单需求数量分配", "库位异常→提示并转人工"]},
        {"step": "4", "cells": ["", "下发堆垛机出库任务", "调度设备", "设备故障→告警并转人工"]},
        {"step": "5", "cells": ["取料确认", "出库回执", "扣减库存，生成领用记录", ""]},
        {"step": "6", "cells": ["", "", "同步ERP/MES/WMS", "同步失败→重试并告警"]},
    ],
    "exceptions": [
        "工单不存在：提示“工单号无效”。",
        "库存不足：提示“库存不足，请转人工处理”。",
        "库位异常：提示并转人工。",
        "设备故障：告警并转人工。",
        "同步失败：自动重试，仍失败则告警。",
    ],
    "category": "出库管理",
}

# 保留流程中的措辞微调：(旧, 新)，作用于 desc 与每个单元格
WORDING = [
    ("新增库区（立库/敏捷仓/线边柜）", "新增库区（立库/敏捷仓）"),
    ("不良品存放规则（是否入线边柜）", "不良品存放规则（入废品仓）"),
    ("现场逐个格子/库位清点", "现场逐个库位清点"),
    ("出入库的格子标记重盘", "出入库的库位标记重盘"),
    ("逐个格子/库位清点", "逐个库位清点"),
    ("（物料/批次/格子/库位）", "（物料/批次/库位）"),
    ("系统检索柜内/立库库存", "系统检索立库/敏捷仓库存"),
    ("选择查询条件（物料/批次/格子）", "选择查询条件（物料/批次/库位）"),
    ("上架/入柜", "上架废品仓"),
    # BD-001：分类只留半成品/成品，去掉格子柜绑定表述
    ("维护物料分类（半成品、成品、辅料）", "维护物料分类（半成品、成品）"),
    ("。物料需与格子柜系统中的“格子与物料类型绑定”逻辑保持一致。", "。"),
    # SB-002：开柜记录/门锁是格子柜概念，改为任务执行状态
    ("、开柜记录、门锁状态", "与任务执行状态"),
    ("采集开柜记录、门锁状态", "采集任务执行进度"),
    ("开柜异常→告警", "执行异常→告警"),
    ("开柜异常：告警并留痕。", "任务执行异常：告警并留痕。"),
    ("采集开柜记录<br>门锁状态", "采集任务执行进度"),
    ("开柜异常告警", "执行异常告警"),
]

META_V2 = [
    "版本：V2.1",
    "编制日期：2026年10月20日",
    "编制单位：广东楠柏莞科技有限公司",
    "适用项目：永鑫精工1.5亿支铣刀技改扩能项目——半成品立体仓、成品敏捷仓",
]

MMD_BD002 = """### YX-WMS-BD-002 生产工单管理流程

```mermaid
flowchart TD
    subgraph 计划员
        A1[登录<br>进入基础管理]
        A2[新建生产工单<br>工单号/物料/产品类型/计划数量]
        A3[提交工单审核]
        A4[审核通过]
        A6[查询工单执行情况]
    end

    subgraph WMS系统
        S2{校验工单号<br>唯一性}
        S3[状态置为待审核]
        S4[状态置为已下达]
        S5[按工单号upsert]
        S6[展示工单关联的<br>入库记录]
    end

    subgraph ERP/MES
        E1[同步工单至MES<br>接收MES推送]
    end

    subgraph 异常处理
        X2[工单号重复提示]
        X3[同步失败<br>重试并告警]
        X4[无记录提示]
    end

    A1 --> A2 --> S2
    S2 -- 通过 --> A3 --> S3 --> A4 --> S4 --> E1
    S2 -- 不通过 --> X2
    E1 -- 成功 --> A6 --> S6
    E1 -- 失败 --> X3
    S6 -- 无记录 --> X4
```

"""

MMD_RK005 = """### YX-WMS-RK-005 不良品入库流程

```mermaid
flowchart TD
    subgraph 操作员
        A1[扫码生产工单]
        A2[录入不良数量与原因]
    end

    subgraph WMS系统
        S1[调出工单物料信息]
        S2{校验数量<br>合理性}
        S3{校验废品仓<br>默认库位}
        S4[写入废品仓<br>默认库位库存]
        S5[记录不良品台账<br>生产入库记录]
        S6[发送同步报文]
    end

    subgraph ERP/MES
        E1[接收并更新库存]
    end

    subgraph 异常处理
        X1[工单无效提示]
        X2[数量超出工单范围提示]
        X3[废品仓未配置<br>提示联系管理员]
        X4[同步失败<br>重试并告警]
    end

    A1 --> S1
    S1 -- 无效 --> X1
    S1 --> A2 --> S2
    S2 -- 异常 --> X2
    S2 -- 正常 --> S3
    S3 -- 未配置 --> X3
    S3 -- 已配置 --> S4 --> S5 --> S6 --> E1
    S6 -- 失败 --> X4
```

"""

MMD_CK001 = """### YX-WMS-CK-001 生产领料出库流程（半成品）

```mermaid
flowchart TD
    subgraph 领料人
        A1[扫码生产工单]
        A2[取料确认]
    end

    subgraph 半成品立库
        C1[调出工单需求物料与数量]
        C2[检查立库库存]
        C3[分配立库货位]
        C4[下发堆垛机<br>出库任务]
        C5[出库回执]
    end

    subgraph WMS系统
        S1{校验工单<br>有效性}
        S2{校验库存<br>是否足够}
        S3[按工单需求数量分配]
        S4[调度设备]
        S5[扣减库存<br>生成领用记录]
        S6[同步ERP/MES/WMS]
    end

    subgraph 异常处理
        E1[工单不存在提示]
        E2[库存不足<br>提示并转人工]
        E3[库位异常<br>提示并转人工]
        E4[设备故障<br>告警并转人工]
        E5[同步失败<br>重试并告警]
    end

    A1 --> S1
    S1 -- 无效 --> E1
    S1 -- 有效 --> C1 --> C2 --> S2
    S2 -- 不足 --> E2
    S2 -- 足够 --> C3 --> S3
    S3 -- 异常 --> E3
    S3 -- 正常 --> C4 --> S4
    S4 -- 故障 --> E4
    S4 -- 正常 --> A2 --> C5 --> S5 --> S6
    S6 -- 失败 --> E5
```

"""


def backup():
    # 只备份一次：重复运行不能把 V1 备份覆盖成 V2
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


def apply_wording(flow):
    def fix(s):
        if not s:
            return s
        for old, new in WORDING:
            s = s.replace(old, new)
        return s

    flow["desc"] = fix(flow["desc"])
    for row in flow["rows"]:
        row["cells"] = [fix(c) for c in row["cells"]]
    flow["exceptions"] = [fix(e) for e in flow.get("exceptions", [])]


def build_flows_v2():
    data = json.load(open(os.path.join(HERE, "flows.json"), encoding="utf-8"))
    kept = [f for f in data["flows"] if f["code"] not in REMOVE]

    out = []
    for f in kept:
        if f["code"] == "YX-WMS-BD-002":
            out.append(BD002)
        elif f["code"] == "YX-WMS-CK-001":
            out.append(CK001)
        elif f["code"] == "YX-WMS-RK-005":
            out.append(RK005)
        else:
            apply_wording(f)
            out.append(f)

    codes = {f["code"] for f in out}
    cats = []
    for c in data["categories"]:
        cats.append({**c, "flows": [x for x in c["flows"] if x in codes]})

    module_of = {}
    for c in data["categories"]:
        for code in c["flows"]:
            module_of[code] = c["title"]
    # 汇总表的「所属模块」用短名（去掉「类流程」等后缀与序号）
    def short(title):
        for k in ["基础数据与系统配置", "入库管理", "出库管理", "库存与盘点管理", "设备集成与接口", "辅助与异常处理"]:
            if k in title:
                return {"基础数据与系统配置": "基础数据"}.get(k, k.replace("管理", ""))
        return title

    summary = [["序号", "流程编号", "流程名称", "所属模块"]]
    for i, f in enumerate(out, 1):
        summary.append([str(i), f["code"], f["name"], short(module_of.get(f["code"], ""))])

    v2 = {
        "title": data["title"],
        "meta": META_V2,
        "categories": cats,
        "summary_rows": summary,
        "flows": out,
    }
    with open(os.path.join(HERE, "flows.json"), "w", encoding="utf-8") as fp:
        json.dump(v2, fp, ensure_ascii=False, indent=1)
    print("flows.json V2:", len(out), "flows")


def build_mmd_v2():
    src = open(os.path.join(HERE, "flows.mmd"), encoding="utf-8").read()
    # 按 "### " 切小节；头两行是注释与章节标题，保持原样
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

    replaced = {"YX-WMS-BD-002": MMD_BD002, "YX-WMS-CK-001": MMD_CK001,
                "YX-WMS-RK-005": MMD_RK005}
    kept_sections = []
    for sec in sections:
        code = sec[0].split()[1]
        if code in REMOVE:
            continue
        if code in replaced:
            kept_sections.append(replaced[code].splitlines(keepends=True))
            continue
        kept_sections.append(sec)

    def patch(lines_):
        text = "".join(lines_)
        for old, new in WORDING:
            mmd_old = old.replace("（", "").replace("）", "")
            text = text.replace(old, new)
            # Mermaid 里括号常被 <br> 或直接省略，做几种常见变体替换
            text = text.replace(old.replace("（", "<br>").replace("）", ""), new.replace("（", "<br>").replace("）", ""))
        return text

    out = "".join(header) + "".join(patch(s) for s in kept_sections)
    with open(os.path.join(HERE, "flows.mmd"), "w", encoding="utf-8") as fp:
        fp.write(out)
    print("flows.mmd V2:", len(kept_sections), "sections")


if __name__ == "__main__":
    backup()
    build_flows_v2()
    build_mmd_v2()
