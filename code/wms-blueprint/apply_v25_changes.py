# -*- coding: utf-8 -*-
"""V2.5（2026-11-09）：V2.4 基础上新增单据打印模块（DY-001，单独立项）。

口径（与客户确认）：
  - 打印操作全部在 WMS；本地有单直接打，没有的从金蝶同步只读快照后打。
  - 浏览器打印预览（可另存 PDF）；样式待定，模板按单据类型可配置。
  - 本期不留打印记录，预留留痕扩展。

沿用 apply_v23_changes 的表格→Mermaid 生成器（mmd_for），保证 flows.mmd
与表格文本一致。跑完本脚本后按流水线继续：
  parse_mmd.py → map_mmd.py → gen_wms_figs_mmd.py → svg2png → build_wms_blueprint.py → gen_designer_docs.py
"""
import json
import os
import shutil

HERE = os.path.dirname(os.path.abspath(__file__))
BACKUP = os.path.join(HERE, "_backup_v2.4")

from flows_v25 import FLOWS, META_V25  # noqa: E402
from apply_v23_changes import mmd_for  # noqa: E402  表格→Mermaid（V2.4 同款，勿在此改动）


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


CAT_MAP = {
    "基础数据": "二、基础数据与系统配置类流程",
    "入库管理": "三、入库管理类流程",
    "出库管理": "四、出库管理类流程",
    "库存管理": "五、库存与盘点管理类流程",
    "设备集成": "六、设备集成与接口类流程",
    "辅助与异常处理": "七、辅助与异常处理流程",
}
# 新章插在「流程汇总表」之前，汇总表章顺延到九
DY_CHAPTER = {"num": "八", "title": "单据打印类流程", "intro": [], "flows": ["YX-WMS-DY-001"]}
SUMMARY_CHAPTER_TITLE = "流程汇总表"

module_label = {
    "基础数据": "基础数据", "入库管理": "入库", "出库管理": "出库",
    "库存管理": "库存与盘点", "设备集成": "设备集成与接口", "辅助与异常处理": "辅助与异常处理",
    "单据打印": "单据打印",
}


def build_categories(old_categories, order):
    """重排既有章的流程清单（顺带修正旧数据里章节缺 RK-001 的残留），
    追加「八、单据打印类流程」，「流程汇总表」章顺延为九。"""
    categories = []
    for cat in old_categories:
        title = cat["title"]
        if title == SUMMARY_CHAPTER_TITLE:
            continue  # 最后统一追加并改编号
        if title in CAT_MAP.values():
            key = next(k for k, v in CAT_MAP.items() if v == title)
            cat = dict(cat)
            cat["flows"] = [c for c in order
                            if next(f for f in FLOWS if f["code"] == c)["category"] == key]
        categories.append(cat)
    categories.append(dict(DY_CHAPTER))
    categories.append({"num": "九", "title": SUMMARY_CHAPTER_TITLE, "intro": [], "flows": []})
    return categories


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
    summary = [["序号", "流程编号", "流程名称", "所属模块"]]
    for i, f in enumerate(FLOWS, 1):
        summary.append([str(i), f["code"], f["name"], module_label.get(f["category"], f["category"])])

    v25 = {
        "title": data["title"],
        "meta": META_V25,
        "categories": build_categories(data["categories"], order),
        "summary_rows": summary,
        "flows": out,
    }
    with open(os.path.join(HERE, "flows.json"), "w", encoding="utf-8") as fp:
        json.dump(v25, fp, ensure_ascii=False, indent=1)
    print("flows.json V2.5:", len(out), "flows")


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
    print("flows.mmd V2.5:", len(FLOWS), "sections")


if __name__ == "__main__":
    backup()
    build_flows()
    build_mmd()
