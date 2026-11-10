# -*- coding: utf-8 -*-
"""解析用户提供的 Mermaid 流程图源，抽出「泳道 / 节点 / 连线（含分支标签）」。

为什么单独解析而不复用现有表格：Mermaid 是**权威的箭头拓扑**（用户提供的），
而原始文档的步骤表只给了格子、没给箭头。本脚本只取拓扑，节点文本与网格位置仍用表格的。

Mermaid 的链式写法要小心：
    S2 -- 通过 --> A4 --> S3
按 `-->` 切开后得到 ["S2 -- 通过", "A4", "S3"]，其中「通过」标注的是
**S2 → A4 这条边**（标签写在起点之后、箭头之前）。所以标签要归属于「当前 token 到下一个 token」。
"""
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "flows.mmd")
OUT = os.path.join(HERE, "mmd.json")

FLOW_RE = re.compile(r"^###\s+(YX-WMS-[A-Z]{2}-\d{3})\s+(.*)$")
NODE_DEF_RE = re.compile(r"^\s*([A-Za-z][A-Za-z0-9]*)\s*[\[\{](.*?)[\]\}]\s*$")
NODE_REF_RE = re.compile(r"^([A-Za-z][A-Za-z0-9]*)\s*(?:--\s*(.+?)\s*)?$")


def parse():
    lines = open(SRC, encoding="utf-8").read().splitlines()
    flows = {}
    cur = None
    lane = None
    in_mermaid = False
    problems = []

    for raw in lines:
        line = raw.rstrip()
        m = FLOW_RE.match(line)
        if m:
            cur = {"code": m.group(1), "name": m.group(2).strip(),
                   "lanes": [], "nodes": {}, "edges": []}
            flows[m.group(1)] = cur
            lane = None
            in_mermaid = False
            continue
        if line.strip().startswith("```"):
            in_mermaid = line.strip() == "```mermaid"
            continue
        if not in_mermaid or cur is None:
            continue
        s = line.strip()
        if not s or s.startswith("flowchart") or s.startswith("%%"):
            continue
        if s == "end":
            lane = None
            continue
        if s.startswith("subgraph"):
            lane = s[len("subgraph"):].strip()
            if lane not in cur["lanes"]:
                cur["lanes"].append(lane)
            continue

        # 连线行：含 -->
        if "-->" in s:
            prev, prev_label = None, ""
            for raw_tok in s.split("-->"):
                tok = raw_tok.strip()
                if not tok:
                    continue
                mm = NODE_REF_RE.match(tok)
                if not mm:
                    problems.append(f"{cur['code']}: 无法解析的连线片段 {tok!r}")
                    prev, prev_label = None, ""
                    continue
                nid, label = mm.group(1), (mm.group(2) or "").strip()
                # 要把节点登记进来（否则只被引用、从未定义的节点会漏）
                cur["nodes"].setdefault(nid, {"text": None, "lane": lane})
                if prev is not None:
                    cur["edges"].append({"source": prev, "target": nid, "label": prev_label})
                prev, prev_label = nid, label
            continue

        # 节点定义行
        md = NODE_DEF_RE.match(s)
        if md:
            nid, text = md.group(1), md.group(2)
            text = text.replace("<br>", "\n").replace("<br/>", "\n").strip()
            cur["nodes"][nid] = {"text": text, "lane": lane}
            continue

        # 裸 id（极少见）
        if re.match(r"^[A-Za-z][A-Za-z0-9]*$", s):
            cur["nodes"].setdefault(s, {"text": None, "lane": lane})
            continue

        problems.append(f"{cur['code']}: 未处理的 mermaid 行 {s!r}")

    return flows, problems


def main():
    flows, problems = parse()
    json.dump(flows, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    print(f"解析到 {len(flows)} 条流程")
    print(f"解析问题: {len(problems)}")
    for p in problems[:12]:
        print("   -", p)
    print()
    total_n = total_e = 0
    for code, f in flows.items():
        undef = [i for i, n in f["nodes"].items() if not n["text"]]
        total_n += len(f["nodes"]); total_e += len(f["edges"])
        print(f"  {code:<16} 泳道{len(f['lanes'])} 节点{len(f['nodes']):>2} 连线{len(f['edges']):>2}"
              f" 标签{sum(1 for e in f['edges'] if e['label'])}"
              f"{'  未定义文本:' + str(undef) if undef else ''}")
    print(f"\n合计 节点 {total_n} / 连线 {total_e}")
    print("\n=== 抽一条核对（BD-001）===")
    f = flows["YX-WMS-BD-001"]
    print("  泳道:", f["lanes"])
    print("  节点:", {k: v["text"].replace("\n", "/") for k, v in f["nodes"].items()})
    for e in f["edges"]:
        print(f"    {e['source']:>3} -> {e['target']:<3} {e['label'] or ''}")


if __name__ == "__main__":
    main()