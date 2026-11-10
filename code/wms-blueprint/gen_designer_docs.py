# -*- coding: utf-8 -*-
"""把 V2 蓝图的 16 条流程转成 wms-logicflow-designer 的 FlowDoc JSON。

数据源：flows.json（表格网格 = 节点位置权威）+ mmd.json（Mermaid = 连线权威）。
映射逻辑与 gen_wms_figs_mmd.py 完全一致（同用 map_mmd 的文本匹配），保证
设计器里看到的连线与 docx 插图是同一套。

输出：D:/yongxin-WMS/wms-logicflow-designer/data/YX-WMS-*.json
用法：设计器「流程图」模式 → 导入 JSON → 选文件。
"""
import json
import os
import sys
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import map_mmd as mm  # noqa: E402
import gen_wms_figs as gw  # noqa: E402
import gen_wms_figs_mmd as gwm  # noqa: E402

OUT_DIR = r"D:/OTC-260906-yongxin_WMS/code/wms-logicflow-designer/data"
COLORS = ["blue", "orange", "green", "purple"]
GRID = {"headerHeight": 44, "stepColumnWidth": 64, "rowHeight": 122, "lanePadX": 14, "lanePadY": 10}


def lane_color(name, idx, total):
    if "异常" in name:
        return "red"
    return COLORS[idx % len(COLORS)]


def build_doc(flow, letter, mmd_flow):
    items = gwm.grid_items(flow, letter)
    by_id = {it["id"]: it for it in items}

    # 节点映射（与 gen_wms_figs_mmd 同一套匹配）
    mapping = {}
    for nid, nd in mmd_flow["nodes"].items():
        cand, _how = mm.match_one(nid, nd["text"], nd["lane"], items, flow["lanes"])
        if cand:
            mapping[nid] = cand

    # 连线映射（复用 gen_wms_figs_mmd 的 map_edges）
    edges, problems, _dup = gwm.map_edges(mmd_flow, mapping, items, len(flow["lanes"]))

    lanes = []
    for i, name in enumerate(flow["lanes"]):
        lanes.append({"id": f"lane-{i}", "name": name,
                      "color": lane_color(name, i, len(flow["lanes"])),
                      "width": 200 if "异常" in name else 210})

    nodes = [{"id": it["id"], "text": it["text"], "laneId": f"lane-{it['lane']}",
              "step": it["row"] + 1, "kind": "process"} for it in items]

    fedges = []
    for src, dst, label, kind in edges:
        # gen_wms_figs_mmd 的线型：err=失败分支（红色虚线），ok=主流程（棕色实线）
        fedges.append({"id": f"e-{src}-{dst}-{len(fedges)}", "source": src, "target": dst,
                       "label": label or "", "kind": "exception" if kind == "err" else "main"})

    return {
        "schemaVersion": 1,
        "id": flow["code"],
        "name": f"{flow['code']} {flow['name']}",
        "updatedAt": datetime.now().strftime("%Y-%m-%dT%H:%M:%S"),
        "grid": dict(GRID),
        "lanes": lanes,
        "stepCount": max(it["row"] for it in items) + 1,
        "nodes": nodes,
        "edges": fedges,
    }, problems


def main():
    flows = json.load(open(os.path.join(HERE, "flows.json"), encoding="utf-8"))["flows"]
    mmd = json.load(open(os.path.join(HERE, "mmd.json"), encoding="utf-8"))
    os.makedirs(OUT_DIR, exist_ok=True)

    total_edges, total_problems = 0, 0
    for idx, flow in enumerate(flows):
        letter = chr(ord("A") + idx)
        doc, problems = build_doc(flow, letter, mmd[flow["code"]])
        out = os.path.join(OUT_DIR, f"{flow['code']}.json")
        with open(out, "w", encoding="utf-8") as fp:
            json.dump(doc, fp, ensure_ascii=False, indent=2)
        n_exc = sum(1 for e in doc["edges"] if e["kind"] == "exception")
        total_edges += len(doc["edges"])
        total_problems += len(problems)
        print(f"{flow['code']}  节点{len(doc['nodes']):3d}  连线{len(doc['edges']):3d}（异常{n_exc:2d}）"
              f"  问题{len(problems)}  -> {out}")
    print(f"\n合计 {len(flows)} 条流程，连线 {total_edges} 条，未解决问题 {total_problems} 个")


if __name__ == "__main__":
    main()
