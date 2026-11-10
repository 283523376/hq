# -*- coding: utf-8 -*-
"""V2.6（2026-11-10）：重写 RK-001——「大改小」改「改刀料」，与段差棒同口径直入半成品立库。

变更点（客户口径）：
  - 大改小 → 改刀料；
  - 改刀料与段差棒处理方式一致：直接入半成品立库；
  - 删除「大改小加工」「生成改制单」两个步骤（连带删「车间」泳道、「投入不足」异常）；
  - 回写统一：外购采购收料单 / 受托受托材料入库单。

其余 17 条流程原样继承 V2.5。跑完按流水线继续：
  parse_mmd.py → map_mmd.py → gen_wms_figs_mmd.py → svg2png → build_wms_blueprint.py → gen_designer_docs.py
"""
import json
import os
import shutil

HERE = os.path.dirname(os.path.abspath(__file__))
BACKUP = os.path.join(HERE, "_backup_v2.5")

from flows_v26 import FLOWS, META_V26  # noqa: E402
from apply_v23_changes import mmd_for  # noqa: E402  表格→Mermaid（沿用，勿改）

SUMMARY_CHAPTER_TITLE = "流程汇总表"

module_label = {
    "基础数据": "基础数据", "入库管理": "入库", "出库管理": "出库",
    "库存管理": "库存与盘点", "设备集成": "设备集成与接口", "辅助与异常处理": "辅助与异常处理",
    "单据打印": "单据打印",
}


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

    # 本次没有增删流程、分类归属也没变（RK-001 仍属「入库管理」），
    # 章节结构与各章流程清单原样沿用 V2.5，只换 RK-001 的卡片内容。
    categories = data["categories"]

    summary = [["序号", "流程编号", "流程名称", "所属模块"]]
    for i, f in enumerate(FLOWS, 1):
        summary.append([str(i), f["code"], f["name"], module_label.get(f["category"], f["category"])])

    v26 = {
        "title": data["title"],
        "meta": META_V26,
        "categories": categories,
        "summary_rows": summary,
        "flows": out,
    }
    with open(os.path.join(HERE, "flows.json"), "w", encoding="utf-8") as fp:
        json.dump(v26, fp, ensure_ascii=False, indent=1)
    print("flows.json V2.6:", len(out), "flows")


def build_mmd():
    src = open(os.path.join(HERE, "flows.mmd"), encoding="utf-8").read()
    lines = src.splitlines(keepends=True)
    header = []
    cur = None
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
    print("flows.mmd V2.6:", len(FLOWS), "sections")


if __name__ == "__main__":
    backup()
    build_flows()
    build_mmd()
