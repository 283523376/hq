# -*- coding: utf-8 -*-
"""V2.3（2026-11-03）：按已落地设计重写 16 条流程与功能结构。

口径（取自 specs/2026-11-03-yx-wms-blueprint-v2.3-design.md）：
  - 按已落地设计修订；设计中未落地的环节也写入，只排除移出范围项与明确不做的（XT-001 退货）。
  - 不写「未实现/后续」字样。
  - flows.mmd 由本脚本按表格自动生成，保证两处文本一致（map_mmd 覆盖率 100%）。

跑完本脚本后按流水线继续：
  parse_mmd.py → map_mmd.py → gen_wms_figs_mmd.py → svg2png → build_wms_blueprint.py → gen_designer_docs.py
"""
import json
import os
import re
import shutil

HERE = os.path.dirname(os.path.abspath(__file__))
BACKUP = os.path.join(HERE, "_backup_v2.2")

META_V23 = [
    "版本：V2.4",
    "编制日期：2026年11月3日",
    "编制单位：广东楠柏莞科技有限公司",
    "适用项目：永鑫精工1.5亿支铣刀技改扩能项目——半成品立体仓、成品敏捷仓",
]


def F(code, name, category, lanes, rows, exceptions, desc):
    return {"code": code, "name": name, "category": category, "lanes": lanes,
            "rows": [{"step": str(i + 1), "cells": r} for i, r in enumerate(rows)],
            "exceptions": exceptions, "desc": desc}


from flows_v24 import FLOWS  # noqa: E402  V2.4 流程数据（含来源标注）

# 分支标签归一：只保留 gen_wms_figs_mmd 白名单里的词，避免线型静默走默认
_LABEL_RULES = [
    (("库存不足", "库位不足", "不足"), "不足"),
    (("设备离线",), "设备离线"),
    (("设备故障", "故障"), "故障"),
    (("工单不存在", "订单不存在", "不存在"), "不存在"),
    (("无记录",), "无记录"),
    (("无匹配",), "无匹配"),
    (("不一致",), "不一致"),
    (("超阈值",), "超阈值"),
    (("重复触发",), "重复触发"),
    (("有物料",), "有物料"),
    (("非法",), "非法"),
    (("无效",), "无效"),
    (("缺失",), "缺失"),
    (("失败",), "失败"),
    (("异常",), "异常"),
]


_OK_HINTS = ("跳过", "无差异", "正常", "无需")


def label_of(cell):
    if "→" not in cell:
        return "异常"
    raw = cell.split("→", 1)[0]
    if any(k in raw for k in _OK_HINTS):
        return "正常"
    for keys, out in _LABEL_RULES:
        if any(k in raw for k in keys):
            return out
    return "异常"


def mmd_for(flow):
    """由表格生成 Mermaid：同泳道首节点逐行串联，异常泳道作为分支终点。"""
    lanes = flow["lanes"]
    lane_letter = {}
    letters = "ASEWXMRT"
    nodes = []           # (lane_idx, id, text, is_exception)
    counters = {}
    for lane_idx, lane in enumerate(lanes):
        letter = letters[lane_idx] if lane_idx < len(letters) else "N"
        lane_letter[lane_idx] = letter
        counters[letter] = 0

    row_nodes = []
    for row in flow["rows"]:
        cur = []
        for lane_idx, text in enumerate(row["cells"]):
            if not text:
                continue
            letter = lane_letter[lane_idx]
            counters[letter] += 1
            nid = f"{letter}{counters[letter]}"
            is_exc = "异常" in lanes[lane_idx]
            nodes.append((lane_idx, nid, text, is_exc))
            cur.append((lane_idx, nid, text, is_exc))
        row_nodes.append(cur)

    # 泳道内节点清单
    per_lane = {i: [] for i in range(len(lanes))}
    for lane_idx, nid, text, _ in nodes:
        per_lane[lane_idx].append((nid, text))

    lines = ["```mermaid", "flowchart TD"]
    for lane_idx, lane in enumerate(lanes):
        lines.append(f"    subgraph {lane}")
        for nid, text in per_lane[lane_idx]:
            label = text.replace("→", "<br>")
            if "异常" in lane:
                lines.append(f"        {nid}[{label}]")
            elif re.search(r"校验|是否|差异比对|比对|判定", text):
                lines.append(f"        {nid}{{{label}}}")
            else:
                lines.append(f"        {nid}[{label}]")
        lines.append("    end")
        lines.append("")

    edges = []
    # 行内与行间主干：异常泳道单独处理
    for r_idx, cur in enumerate(row_nodes):
        mains = [n for n in cur if not n[3]]
        for i in range(len(mains) - 1):
            edges.append(f"    {mains[i][1]} --> {mains[i + 1][1]}")
        if r_idx + 1 < len(row_nodes):
            nxt = [n for n in row_nodes[r_idx + 1] if not n[3]]
            if mains and nxt:
                edges.append(f"    {mains[-1][1]} --> {nxt[0][1]}")
        for lane_idx, nid, text, is_exc in cur:
            if is_exc and mains:
                edges.append(f"    {mains[-1][1]} -- {label_of(text)} --> {nid}")
    lines.extend(dict.fromkeys(edges))
    lines.append("```")
    return "\n".join(lines) + "\n\n"


def backup():
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


def build_flows():
    data = json.load(open(os.path.join(HERE, "flows.json"), encoding="utf-8"))
    old_by_code = {f["code"]: f for f in data["flows"]}
    out = []
    for new in FLOWS:
        merged = dict(old_by_code.get(new["code"], {}))
        merged.update({
            "code": new["code"],
            "name": new["name"],
            "desc": new["desc"],
            "lanes": new["lanes"],
            "rows": new["rows"],
            "exceptions": new["exceptions"],
            "category": new["category"],
        })
        out.append(merged)

    order = [f["code"] for f in FLOWS]
    # 分类：按 FLOWS 顺序重排各模块下的流程编号（RK-001 插到 RK-002 前）
    cat_map = {
        "基础数据": "二、基础数据与系统配置类流程",
        "入库管理": "三、入库管理类流程",
        "出库管理": "四、出库管理类流程",
        "库存管理": "五、库存与盘点管理类流程",
        "设备集成": "六、设备集成与接口类流程",
        "辅助与异常处理": "七、辅助与异常处理流程",
    }
    categories = []
    for cat in data["categories"]:
        if cat["title"] in cat_map.values():
            key = next(k for k, v in cat_map.items() if v == cat["title"])
            cat = dict(cat)
            cat["flows"] = [c for c in order if next(f for f in FLOWS if f["code"] == c)["category"] == key]
        categories.append(cat)

    module_label = {
        "基础数据": "基础数据", "入库管理": "入库", "出库管理": "出库",
        "库存管理": "库存与盘点", "设备集成": "设备集成与接口", "辅助与异常处理": "辅助与异常处理",
    }
    summary = [["序号", "流程编号", "流程名称", "所属模块"]]
    for i, f in enumerate(FLOWS, 1):
        summary.append([str(i), f["code"], f["name"], module_label.get(f["category"], f["category"])])

    v23 = {
        "title": data["title"],
        "meta": META_V23,
        "categories": categories,
        "summary_rows": summary,
        "flows": out,
    }
    with open(os.path.join(HERE, "flows.json"), "w", encoding="utf-8") as fp:
        json.dump(v23, fp, ensure_ascii=False, indent=1)
    print("flows.json V2.3:", len(out), "flows")


def build_mmd():
    src = open(os.path.join(HERE, "flows.mmd"), encoding="utf-8").read()
    lines = src.splitlines(keepends=True)
    header, cur = [], None
    for line in lines:
        if line.startswith("### YX-WMS-"):
            cur = True
        elif cur is None:
            header.append(line)

    body = []
    for f in FLOWS:
        body.append(f"### {f['code']} {f['name']}\n\n")
        body.append(mmd_for(f))
    with open(os.path.join(HERE, "flows.mmd"), "w", encoding="utf-8") as fp:
        fp.write("".join(header) + "".join(body))
    print("flows.mmd V2.3:", len(FLOWS), "sections")


if __name__ == "__main__":
    backup()
    build_flows()
    build_mmd()
