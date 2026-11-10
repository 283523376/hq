# -*- coding: utf-8 -*-
"""按 Mermaid 的权威拓扑出图：**布局与节点编号沿用表格，连线换成 Mermaid 给的**。

为什么这么切分：
  - 原始文档的步骤表给了步骤号与网格位置 → docx 正文的步骤表与图上的步骤号必须对得上；
  - Mermaid 只给拓扑（谁连谁、分支标签）→ 正好补上「表格没给箭头」这个缺口。
所以只替换 spec["edges"]，rows 仍由 gen_wms_figs.flow_grid 生成（编号 A01… 与既有图一致）。

多对多的映射（Mermaid 把两处「编码重复」合并成一个异常节点 E1）：
  一条 (源, 目标) 边映射到「源候选 × 目标候选」时，按**行距最近**配对，
  且只在约束更强的一侧配对（单源→取最近目标；多源单目标→取最近源），
  避免产生「单源 × 所有目标」的虚假边。
"""
import json
import os
import re

import gen_wms_figs as gw
import map_mmd as mm

HERE = os.path.dirname(os.path.abspath(__file__))

# ---- 分支标签 → 线型：**逐个显式归类**（不猜关键词），未收录的会报警告 ----
ERR_LABELS = {
    "失败", "保存失败", "异常", "不通过", "不存在", "订单不存在", "故障", "设备离线",
    "不足", "库位不足", "库存不足", "非法", "损坏", "不合格", "无效", "无匹配", "无记录",
    "格式错误", "数量不符", "不一致", "不可行", "重复触发", "缺失", "超阈值", "有物料",
    "存在差异", "不完整",
}
OK_LABELS = {
    "正常", "通过", "存在", "成功", "库位充足", "充足", "足够", "够用", "合法", "合格",
    "有效", "有匹配", "格式正确", "设备在线", "数量一致", "一致", "可行", "无差异", "完整",
}
_warned = set()


def kind_of(label):
    if not label:
        return "ok"                      # 无标签的边按主流程处理
    if label in ERR_LABELS:
        return "err"
    if label in OK_LABELS:
        return "ok"
    if label not in _warned:             # 说明 mmd 换词了：报出来，别悄悄走默认
        _warned.add(label)
        print(f"  !! 未归类的分支标签 {label!r} —— 暂按实线处理，请补进 ERR_LABELS/OK_LABELS")
    return "ok"


def grid_items(flow, letter):
    """带 id 的网格节点（与出图用的编号完全同源）"""
    items = []
    for ri, row in enumerate(gw.flow_grid(flow, letter)):
        for lane_idx, nid, text in row["nodes"]:
            items.append({"id": nid, "lane": lane_idx, "lane_name": flow["lanes"][lane_idx],
                          "row": ri, "text": text})
    return items


def map_edges(mmd_flow, mapping, items, lane_count):
    """Mermaid 的边 → 网格节点之间的边。返回 [(src, dst, label, kind)] 与统计"""
    by_id = {it["id"]: it for it in items}
    edges, problems = [], []

    def rowdist(a, b):
        return (abs(a["row"] - b["row"]), a["row"])

    for e in mmd_flow["edges"]:
        srcs, dsts = mapping.get(e["source"], []), mapping.get(e["target"], [])
        if not srcs or not dsts:
            problems.append(f"连线映射失败 {e['source']}->{e['target']}（源{len(srcs)} 目标{len(dsts)}）")
            continue
        if len(srcs) == 1:
            pairs = [(srcs[0], min(dsts, key=lambda d: rowdist(d, srcs[0])))]
        elif len(dsts) == 1:
            pairs = [(min(srcs, key=lambda s: rowdist(s, dsts[0])), dsts[0])]
        else:
            pairs = [(s, min(dsts, key=lambda d: rowdist(d, s))) for s in srcs]
        for s, d in pairs:
            edges.append((s["id"], d["id"], e["label"], kind_of(e["label"])))

    # 去重（合并后可能出现重复对）
    seen, uniq = set(), []
    dup = 0
    for s, d, lab, k in edges:
        if (s, d) in seen:
            dup += 1
            continue
        seen.add((s, d))
        uniq.append((s, d, lab, k))
    return uniq, problems, dup


def pad_bottom(path, pad=12):
    """给画布底部补 pad 像素。

    原因：swimlane 给画布留的底部余量（M=20）装不下 20.5px 高的分支标签胶囊，
    实测有 4 张图的最后一个标签超出下边界 3.7px 被裁掉。只改 svg 的 height / viewBox
    与背景矩形（背景是文档里的**第一个** rect，所以 count=1 命中它而不会碰到节点框）。
    """
    s = open(path, encoding="utf-8").read()
    s = re.sub(r'(<svg[^>]*?)height="(\d+)"',
               lambda m: f'{m.group(1)}height="{int(m.group(2)) + pad}"', s, count=1)
    s = re.sub(r'viewBox="0 0 ([\d.]+) (\d+)"',
               lambda m: f'viewBox="0 0 {m.group(1)} {int(m.group(2)) + pad}"', s, count=1)
    s = re.sub(r'(<rect width="[\d.]+" height=")(\d+)(" fill=")',
               lambda m: f'{m.group(1)}{int(m.group(2)) + pad}{m.group(3)}', s, count=1)
    open(path, "w", encoding="utf-8").write(s)


def main():
    flows = json.load(open(os.path.join(HERE, "flows.json"), encoding="utf-8"))["flows"]
    mmd = json.load(open(os.path.join(HERE, "mmd.json"), encoding="utf-8"))
    engine = gw.load_engine()
    swimlane = engine["swimlane"]

    stats, all_problems = [], []
    for idx, flow in enumerate(flows):
        code = flow["code"]
        letter = chr(ord("A") + idx)
        m = mmd[code]
        items = grid_items(flow, letter)

        # 1) 节点映射：Mermaid 节点 → 网格节点（可能一对多）
        mapping = {}
        unmapped = []
        for nid, nd in m["nodes"].items():
            cand, how = mm.match_one(nid, nd["text"], nd["lane"], items, flow["lanes"])
            mapping[nid] = cand
            if not cand:
                unmapped.append(f"{nid}「{(nd['text'] or '')[:14]}」{how}")
        if unmapped:
            all_problems.append(f"{code} 未映射节点: {unmapped}")

        # 2) 连线映射
        edges, problems, dup = map_edges(m, mapping, items, len(flow["lanes"]))
        all_problems += [f"{code}: {p}" for p in problems]

        # 3) 终点：出边**只**指向异常泳道（或没有出边）、自身不在异常泳道、行号最大者。
        #    不能要求「完全没有出边」：流程末尾的节点通常还有一条挂向异常列的分支
        #    （如 S5 -- 无记录 --> X4），那种节点在语义上仍是终点。
        exc_lane = len(flow["lanes"]) - 1
        by_id = {it["id"]: it for it in items}
        leaves_flow = {s for s, d, _l, _k in edges if by_id[d]["lane"] != exc_lane}
        tail = [it for it in items if it["lane"] != exc_lane and it["id"] not in leaves_flow]
        last_id = max(tail, key=lambda it: it["row"])["id"] if tail else None

        spec = gw.make_spec(flow, letter, edges, last_id)
        fname = f"SWIM-{code}.svg"
        swimlane(spec, fname)
        pad_bottom(os.path.join(gw.FIGS, fname))
        stats.append((code, len(items), len(edges), sum(1 for e in edges if e[3] == "err"),
                      len(edges) - sum(1 for e in edges if e[3] == "err"), last_id, dup))

    json.dump({"capture": engine["CAPTURE"] or {}, "specs": {}},
              open(os.path.join(HERE, "capture.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)

    print(f"\n=== 出图完成 {len(stats)} 条 ===")
    print(f"{'流程':<17}{'节点':>4}{'连线':>5}{'红虚线':>7}{'实线':>6}  终点")
    for code, n, e, err, ok, last, dup in stats:
        print(f"  {code:<17}{n:>4}{e:>5}{err:>7}{ok:>6}  {last or '（无，流程为环）'}"
              f"{f'  去重{dup}' if dup else ''}")
    print(f"\n问题 {len(all_problems)} 条:")
    for p in all_problems[:15]:
        print("  -", p)


if __name__ == "__main__":
    main()