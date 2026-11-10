# -*- coding: utf-8 -*-
"""把《永鑫WMS软件蓝图原始文档.md》解析成结构化流程数据。

这个文档的约定（我看过全文后总结，不是猜的）：
  - 章节用中文数字：一、… 八、
  - 每条流程一段，由这几行组成，顺序固定：
        流程编号：YX-WMS-XX-00N
        名称：…
        （空行）
        流程说明：
        <一段说明，可能多行>
        （空行）
        流程图：
        <制表符分隔的表格>  首行是「步骤 + 泳道名…」，其后每行一个步骤
        （空行）
        异常处理：
        <一到多条，每条以「- 」或直接成句>
  - 每条流程的表格最后一列固定是「异常处理」

输出：flows.json（供后续出图与出 docx 使用）
"""
import json
import os
import re

SRC = r"D:\OTC-260906-yongxin_WMS\软件蓝图\永鑫WMS软件蓝图原始文档.md"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "flows.json")

CATEGORY_RE = re.compile(r"^([一二三四五六七八九十]+)、(.*)$")
NUM_RE = re.compile(r"^(\d+)\.(\d+)\s+(.*)$")


def clean(s):
    return s.replace("\u3000", " ").strip()


def parse():
    lines = open(SRC, encoding="utf-8").read().splitlines()

    doc = {"title": "", "meta": [], "categories": [], "summary_rows": []}
    # ---- 封面信息（开头到「一、」之前）----
    head = []
    for i, l in enumerate(lines):
        if CATEGORY_RE.match(clean(l)):
            break
        if clean(l):
            head.append(clean(l))
    doc["title"] = head[0] if head else ""
    doc["meta"] = head[1:]

    flows = []
    category = None
    i = 0
    n = len(lines)
    while i < n:
        line = clean(lines[i])
        m = CATEGORY_RE.match(line)
        if m:
            category = {"num": m.group(1), "title": m.group(2), "intro": [], "flows": []}
            doc["categories"].append(category)
            i += 1
            continue

        if line.startswith("流程编号："):
            flow = {
                "code": line.split("：", 1)[1].strip(),
                "name": "",
                "desc": "",
                "lanes": [],
                "rows": [],
                "exceptions": [],
                "category": category["title"] if category else "",
            }
            # 名称
            if i + 1 < n and clean(lines[i + 1]).startswith("名称："):
                flow["name"] = clean(lines[i + 1]).split("：", 1)[1].strip()
                i += 2
            else:
                i += 1
            # 流程说明
            while i < n and clean(lines[i]) != "流程说明：":
                if clean(lines[i]).startswith("流程编号："):
                    break
                i += 1
            if i < n and clean(lines[i]) == "流程说明：":
                i += 1
                buf = []
                while i < n and clean(lines[i]) and clean(lines[i]) != "流程图：":
                    buf.append(clean(lines[i]))
                    i += 1
                flow["desc"] = "\n".join(buf)
            # 流程图表格
            while i < n and clean(lines[i]) != "流程图：":
                if clean(lines[i]).startswith("流程编号："):
                    break
                i += 1
            if i < n and clean(lines[i]) == "流程图：":
                i += 1
                while i < n and not clean(lines[i]):
                    i += 1
                if i < n and "\t" in lines[i]:
                    header = [clean(c) for c in lines[i].split("\t")]
                    flow["lanes"] = header[1:]  # 去掉「步骤」列
                    i += 1
                    while i < n and clean(lines[i]) and "\t" in lines[i]:
                        cells = lines[i].split("\t")
                        step = clean(cells[0])
                        values = [clean(c) for c in cells[1:]]
                        flow["rows"].append({"step": step, "cells": values})
                        i += 1
            # 异常处理
            while i < n and clean(lines[i]) != "异常处理：":
                if clean(lines[i]).startswith("流程编号：") or CATEGORY_RE.match(clean(lines[i])):
                    break
                i += 1
            if i < n and clean(lines[i]) == "异常处理：":
                i += 1
                buf = []
                while i < n:
                    cur = clean(lines[i])
                    if not cur:
                        i += 1
                        continue
                    if cur.startswith("流程编号：") or CATEGORY_RE.match(cur) or NUM_RE.match(cur):
                        break
                    buf.append(cur)
                    i += 1
                flow["exceptions"] = buf
            flows.append(flow)
            if category:
                category["flows"].append(flow["code"])
            continue

        # 汇总表（八、流程汇总表）：制表符或空格分隔
        if category and "汇总" in category["title"] and "\t" in lines[i]:
            doc["summary_rows"].append([clean(c) for c in lines[i].split("\t")])
        i += 1

    doc["flows"] = flows
    return doc


def main():
    doc = parse()
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)

    print(f"标题: {doc['title']}")
    print(f"封面信息行: {doc['meta']}")
    print(f"章节数: {len(doc['categories'])}  流程数: {len(doc['flows'])}")
    print(f"汇总表行数: {len(doc['summary_rows'])}")
    bad = [f["code"] for f in doc["flows"]
           if not f["name"] or not f["desc"] or not f["lanes"] or not f["rows"]]
    print(f"字段缺失的流程: {bad if bad else '无'}")
    print("\n=== 逐条核对（编号 / 名称 / 泳道数 / 步骤数 / 异常条数 / 说明字数）===")
    for f in doc["flows"]:
        print(f"  {f['code']:<16} {f['name'][:22]:<24} 泳道{len(f['lanes'])} "
              f"步骤{len(f['rows']):<2} 异常{len(f['exceptions']):<2} 说明{len(f['desc'])}字")
    print("\n=== 抽一条完整打印（用来人工核对解析是否准确）===")
    f = doc["flows"][3]
    print(json.dumps(f, ensure_ascii=False, indent=2)[:1800])


if __name__ == "__main__":
    main()