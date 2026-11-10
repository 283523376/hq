# -*- coding: utf-8 -*-
"""把解析出来的 WMS 流程数据变成泳道图 SVG（复用格子柜那套画法），并产出编辑器用的 CAPTURE。

复用方式（**不 import make_figs**）：make_figs.py 底部没有 __main__ 守卫，直接 import 会
执行它的驱动循环、把格子柜那 24 张图重写进 图表/。所以这里读出它的源码、**裁掉驱动段**再 exec，
并把模块级的 OUT 重定向到本目录 —— 对那个仓库零写入。

两处启发式（原始文档只给了「格子」，没给这些，必须声明出来以便人复核）：
  1. 节点类型：按泳道名推断（无法像人工 spec 那样逐节点赋予语义）；
  2. 连线走向：一条「主干」（逐行、行内从左到右）+ 异常节点作为分支挂在主干上。
用法：
  python gen_wms_figs.py            # 全部 22 条
  python gen_wms_figs.py 001 004    # 只做编号里含 001 / 004 的流程（出样张用）
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
FIGS = os.path.join(HERE, "figs")
MAKE_FIGS = r"D:\通用备份\永鑫格子柜\yongxin-prd-master\make_figs.py"
SRC_JSON = os.path.join(HERE, "flows.json")
CAPTURE_OUT = os.path.join(HERE, "capture.json")

# ---------------------------------------------------------------- 载入画法

def load_engine():
    os.environ["FLOW_JSON"] = "1"          # 让 make_figs 填充 CAPTURE
    src = open(MAKE_FIGS, encoding="utf-8").read()
    marker = "for spec, name in ["
    if marker not in src:
        raise SystemExit(f"make_figs.py 结构变了：找不到驱动段标记 {marker!r}")
    stripped = src[: src.index(marker)]     # 只裁到驱动段之前
    ns = {"__name__": "make_figs_engine", "__file__": MAKE_FIGS}
    exec(compile(stripped, MAKE_FIGS, "exec"), ns)
    ns["OUT"] = FIGS                        # 输出重定向到本目录
    os.makedirs(FIGS, exist_ok=True)
    return ns


# ---------------------------------------------------------------- 启发式 1：节点类型

def node_type(lane, text, is_last):
    """按泳道名推断节点类型；行尾（整条流程的终点）判为 ok。

    这是**推断**，不是原文档给的信息 —— 人工 spec 里每个节点的类型是逐个人工赋予语义的
    （同一泳道内也有 sys / dev / ok 混用），自动转换做不到那一层，只能按泳道粗分。
    """
    if is_last:
        return "ok"
    if "异常" in lane:
        return "warn"
    if re.search(r"ERP|MES|上位", lane):
        return "erp"
    if re.search(r"WCS|RMS|堆垛机|SCR|机器人|分拣|机械手|输送", lane):
        return "dev"
    if re.search(r"检验|质检", lane):
        return "dec"
    if re.search(r"WMS系统", lane):
        return "sys"
    if re.search(r"线边柜|立库|仓库|库区", lane):
        return "sys"
    if re.search(r"管理员|仓管员|操作员|领料人|收货员|现场|发货|计划", lane):
        return "user"
    if re.search(r"格子柜|设备", lane):
        return "dev"
    return "user"


# ---------------------------------------------------------------- 启发式 2：连线

def build_edges(rows_meta, lane_count):
    """返回 (edges, spine_ids)。

    rows_meta: [{step, nodes:[(lane_idx, node_id, text)]}] 只含非空单元
    主干 = 所有非异常节点按「逐行、行内从左到右」串起来（kind=ok）；
    异常节点（最后一列）**只进不出**：从本行最后一个主干节点进一条 err 边。
    即异常是主干上挂出去的**分支终点**。

    为什么出边不画：异常列在最右侧，它的出边若接回主干（在左侧）就会横穿整张图，
    视觉上是一堆长虚线；而且原文档只给了格子、没给「异常之后回到哪一步」的信息，
    硬连一条是编造。异常作为终点分支既不编造、也干净。
    """
    edges = []
    spine = []          # [(row_idx, lane_idx, node_id)]
    exceptions = []     # [(row_idx, lane_idx, node_id)]
    for ri, row in enumerate(rows_meta):
        for lane_idx, nid, _t in row["nodes"]:
            if lane_idx == lane_count - 1:      # 最后一列 = 异常处理
                exceptions.append((ri, lane_idx, nid))
            else:
                spine.append((ri, lane_idx, nid))

    for i in range(len(spine) - 1):
        edges.append((spine[i][2], spine[i + 1][2], "", "ok"))

    for ri, _lane_idx, nid in exceptions:
        # 进边：本行里最后一个主干节点；本行没有主干节点则取此前最后一个
        prev = [n for n in spine if n[0] <= ri]
        if prev:
            edges.append((prev[-1][2], nid, "", "err"))
    return edges, [s[2] for s in spine]


# ---------------------------------------------------------------- 组装 spec

def flow_grid(flow, letter):
    """「表格 → 网格节点」的公共部分。**节点编号（A01…）在这里确定**，
    出图与出编辑器 JSON 必须共用它，否则两边的编号会不一致。"""
    rows_meta = []
    counter = 0
    for row in flow["rows"]:
        nodes = []
        for lane_idx, text in enumerate(row["cells"]):
            if not text:
                continue
            counter += 1
            nodes.append((lane_idx, f"{letter}{counter:02d}", text))
        rows_meta.append({"step": row["step"], "nodes": nodes})
    return rows_meta


def make_spec(flow, letter, edges, last_id):
    """由网格 + 给定连线组装 swimlane 的 spec。edges 既可以是推导的，也可以来自 Mermaid。"""
    lanes = flow["lanes"]
    rows_meta = flow_grid(flow, letter)
    spec_rows = []
    for row in rows_meta:
        items = []
        for lane_idx, nid, text in row["nodes"]:
            items.append((lane_idx, nid, text, node_type(lanes[lane_idx], text, nid == last_id)))
        spec_rows.append(items)
    return {
        "title": "",
        "target_w": 34 + 40 + len(lanes) * 148,
        "lanes": lanes,
        "rows": spec_rows,
        "edges": edges,
    }


def flow_to_spec(flow, letter):
    """推导连线的版本（保留：出图引擎的默认路径）。
    终点 = **主干**的最后一个节点；不能取「阅读顺序最后一个节点」，
    那是最后一行的异常列单元，会被涂成表示成功完成的绿色，语义上是错的。"""
    rows_meta = flow_grid(flow, letter)
    edges_raw, spine_ids = build_edges(rows_meta, len(flow["lanes"]))
    last_id = spine_ids[-1] if spine_ids else None
    return make_spec(flow, letter, edges_raw, last_id)


# ---------------------------------------------------------------- 主流程

def main():
    only = [a for a in sys.argv[1:] if not a.startswith("-")]
    doc = json.load(open(SRC_JSON, encoding="utf-8"))
    engine = load_engine()
    swimlane = engine["swimlane"]

    specs = {}
    for idx, flow in enumerate(doc["flows"]):
        code_short = flow["code"].replace("YX-WMS-", "")
        if only and not any(o in code_short for o in only):
            continue
        letter = chr(ord("A") + idx)
        spec = flow_to_spec(flow, letter)
        fname = f"SWIM-{flow['code']}.svg"
        specs[fname] = {
            "code": flow["code"],
            "name": flow["name"],
            "desc": flow["desc"],
            "exceptions": flow["exceptions"],
            "category": flow["category"],
            "letter": letter,
        }
        swimlane(spec, fname)

    capture = engine["CAPTURE"] or {}
    json.dump({"specs": specs, "capture": capture}, open(CAPTURE_OUT, "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    print(f"\n生成 {len(specs)} 张 SVG -> {FIGS}")
    print(f"CAPTURE 条目 {len(capture)} 个 -> {CAPTURE_OUT}")


if __name__ == "__main__":
    main()