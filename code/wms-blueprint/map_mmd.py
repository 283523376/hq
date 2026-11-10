# -*- coding: utf-8 -*-
"""把 Mermaid 的节点映射到「表格网格」的节点上，并报告覆盖率。

为什么要映射而不是直接用 Mermaid 重建布局：
  - 原始文档的步骤表给了**步骤号**，docx 正文里的步骤表与图上的步骤号要对得上；
  - Mermaid 只给拓扑，丢了步骤号。所以布局仍用表格，只用 Mermaid 的连线。
代价是要做一次文本匹配 —— 本脚本就是量这件事到底成不成。

匹配策略（按优先级）：
  1. 同泳道内、归一化后完全相等；
  2. 同泳道内、difflib 相似度 ≥ 阈值（默认 0.70）；
  3. 全泳道范围内同样的模糊匹配（跨泳道兜底，会单独标出来）。
归一化会去掉空白与中英文标点（含 → ），所以
「维护物料信息（编码/名称/规格）」与「维护物料信息␤编码/名称/规格」视为同一串。
"""
import difflib
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
THRESHOLD = 0.70
PUNCT = r'[\s，。、：；！？（）()\[\]【】《》“”‘’""\'\'／/·—\-→⇒\\|]+'


def norm(s):
    return re.sub(PUNCT, "", (s or "")).strip()


def contains_either(a, b, min_len=4):
    """归一化后是否存在包含关系；被包含的一方至少 min_len 个字。
    min_len 是必要的：否则「提示」这类短词会把一堆节点都拉进来。"""
    if len(a) < min_len or len(b) < min_len:
        return False
    return a in b or b in a


def lane_norm(s):
    """泳道名归一化：先去掉括号注释再按标点归一。
    例：表格的「上位系统（ERP/MES/WMS）」与 Mermaid 的「上位系统」应视为同一泳道。"""
    return norm(re.sub(r"[（(][^）)]*[）)]", "", s or ""))


def mmtext(nid, m):
    return (m["nodes"].get(nid, {}).get("text") or "").replace("\n", "/")[:16]


def build_grid(flows):
    """每条流程的网格节点：id / lane_idx / row_idx（0-based）/ text"""
    grid = {}
    for f in flows:
        items = []
        for ri, row in enumerate(f["rows"]):
            for li, text in enumerate(row["cells"]):
                if text:
                    items.append({"id": None, "lane": li, "lane_name": f["lanes"][li],
                                  "row": ri, "text": text})
        grid[f["code"]] = {"lanes": f["lanes"], "items": items}
    return grid


def match_one(mnode, mtext, mlane, items, lane_names):
    """返回 (候选网格节点列表, 匹配方式)。

    注意：必须返回「精确 ∪ 模糊」的**全集**，不能精确命中就返回。
    Mermaid 会把表格里两处同名异常合并成一个节点（BD-001 的 E1 同时对应
    「编码重复→提示重新输入」与「编码重复→提示」两格），若精确命中就短路，
    另一格会漏掉，那条 `不通过` 边就会跨行连错。
    """
    t = norm(mtext)
    if not t:
        return [], "空文本"
    mlane_n = lane_norm(mlane)

    def ratio(x):
        return difflib.SequenceMatcher(None, t, norm(x["text"])).ratio()

    def dedup(seq):
        seen, out = set(), []
        for it in seq:
            if it["text"] + str(it.get("lane")) in seen:
                continue
            seen.add(it["text"] + str(it.get("lane")))
            out.append(it)
        return out

    same = [it for it in items if lane_norm(it["lane_name"]) == mlane_n]
    exact = [it for it in same if norm(it["text"]) == t]
    # 补充候选用**包含关系**，不用相似度阈值。
    # 反例（阈值方案的失败）：S2「校验分类编码唯一性」与 A08「校验物料编码唯一性」
    # 只差一个字、相似度 0.9，会被 0.7 的阈值误纳；而 E1「编码重复提示重新输入」与
    # A09「编码重复提示」是真的一对多关系（前者包含后者）。包含关系能把两者分开。
    contains = [it for it in same if contains_either(t, norm(it["text"]))]
    cands = dedup(exact + contains)
    if cands:
        how = "exact" if len(cands) == len(exact) and exact else (
            "exact+包含" if exact else "包含")
        return cands, how
    fuzzy_any = dedup([it for it in items if ratio(it) >= THRESHOLD])
    if fuzzy_any:
        return fuzzy_any, "fuzzy-cross-lane"
    best = sorted(items, key=lambda it: -ratio(it))[:2]
    return [], f"未匹配(最高分 {[ (round(ratio(b),2), b['lane_name'], b['text'][:14]) for b in best ]})"


def main():
    flows = json.load(open(os.path.join(HERE, "flows.json"), encoding="utf-8"))["flows"]
    mmd = json.load(open(os.path.join(HERE, "mmd.json"), encoding="utf-8"))
    grid = build_grid(flows)

    report = {}
    total_nodes = matched_nodes = 0
    total_edges = mapped_edges = 0
    issues = []
    for f in flows:
        code = f["code"]
        g = grid[code]
        m = mmd[code]
        lane_names = g["lanes"]
        mapping = {}          # mmd 节点 id -> [网格节点]
        ways = {}
        for nid, nd in m["nodes"].items():
            cand, how = match_one(nid, nd["text"], nd["lane"], g["items"], lane_names)
            mapping[nid] = cand
            ways[nid] = how
            total_nodes += 1
            if cand:
                matched_nodes += 1
            else:
                issues.append(f"{code} 节点 {nid} 「{nd['text'][:16]}」泳道「{nd['lane']}」→ {how}")
        # 泳道名是否一致（按归一化比较：表格的「上位系统（ERP/MES/WMS）」与 Mermaid 的「上位系统」算同一泳道）
        known = {lane_norm(l) for l in lane_names}
        mismatch_lane = [l for l in m["lanes"] if lane_norm(l) not in known]
        if mismatch_lane:
            issues.append(f"{code} Mermaid 泳道名在表格里找不到对应: {mismatch_lane}  表格: {lane_names}")
        for nid, how in ways.items():
            if how == "fuzzy-cross-lane":
                hits = [f"{c['lane_name']}:{c['text'][:12]}" for c in mapping[nid]]
                issues.append(f"{code} 节点 {nid}（{mmtext(nid, m)}）靠跨泳道文本匹配到 {hits}"
                              f" —— 布局不受影响（布局用表格），但请确认这条边连对了人")
        report[code] = {"ways": ways, "mapping": {k: [c["id"] for c in v] for k, v in mapping.items()}}
        total_edges += len(m["edges"])

    print(f"=== 覆盖率 ===")
    print(f"节点: {matched_nodes}/{total_nodes} = {matched_nodes/total_nodes:.1%}")
    print(f"（连线映射在生成阶段统计）")
    print(f"\n=== 匹配方式分布 ===")
    from collections import Counter
    c = Counter(w for r in report.values() for w in r["ways"].values())
    for k, v in c.most_common():
        print(f"  {v:>4}  {k}")
    print(f"\n=== 需要人工关注 ({len(issues)}) ===")
    for i in issues[:25]:
        print("  -", i)
    json.dump(report, open(os.path.join(HERE, "map_report.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()