# -*- coding: utf-8 -*-
"""生成《永鑫WMS系统软件蓝图（需求对接与方案确认书）》docx。

版式复用 `格子柜软件蓝图_V1.0.docx` 背后的构建脚本 build_locker_blueprint.py：
读出它的源码、**裁到「封面」分界之前**再 exec，拿到全部排版助手与「一条流程怎么排」
的 PROC / cSTEP / cSUB / PROCIMGPIC。理由与 make_figs 相同：那个脚本没有 __main__ 守卫，
直接 import 会执行它的内容段、生成格子柜的 docx。裁掉之后是**只读复用**，
并把 FIGDIR / OUT 重定向到本目录 —— 对那个仓库零写入（已实测确认）。

内容全部来自 flows.json（由 parse_md.py 解析《永鑫WMS软件蓝图原始文档.md》）。
用法：
  python build_wms_blueprint.py --sample        # 样张：完整封面+汇总表，只放 2 条流程
  python build_wms_blueprint.py                 # 全量 22 条
"""
import json
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
FIGS = os.path.join(HERE, "figs")
LK = r"D:\通用备份\永鑫格子柜\yongxin-prd-master\build_locker_blueprint.py"
CUT = "# ============================ 封面 ============================"

DOC_TITLE = "永鑫WMS系统软件蓝图"
DOC_SUBTITLE = "永鑫WMS系统　需求对接与方案确认书"
SAMPLE = "--sample" in sys.argv
OUT = os.path.join(HERE, "out",
                   "永鑫WMS软件蓝图（样张）.docx" if SAMPLE else "永鑫WMS软件蓝图.docx")
SAMPLE_FLOWS = ["YX-WMS-BD-001", "YX-WMS-RK-001"]

# ---------------------------------------------------------------- 载入版式

def load_layout():
    src = open(LK, encoding="utf-8").read()
    if CUT not in src:
        raise SystemExit(f"build_locker_blueprint.py 结构变了：找不到 {CUT!r}")
    ns = {"__name__": "lk_layout", "__file__": LK}
    exec(compile(src[: src.index(CUT)], LK, "exec"), ns)
    ns["FIGDIR"] = FIGS          # 图片从本目录读
    ns["OUT"] = OUT              # 保险：万一有代码引用 OUT，也不指向那个仓库
    return ns


def main():
    ns = load_layout()
    doc = ns["doc"]
    (H1, P, IMG, PAGEBREAK) = (ns["H1"], ns["P"], ns["IMG"], ns["PAGEBREAK"])
    (TBL, TBL_LINK, fill_cell, set_cell_bg, set_tbl_width) = (
        ns["TBL"], ns["TBL_LINK"], ns["fill_cell"], ns["set_cell_bg"], ns["set_tbl_width"])
    (style_run, fmt_par) = (ns["style_run"], ns["fmt_par"])
    (PROC, cSTEP, cSUB, cKVL, CELL, PROCIMGPIC) = (
        ns["PROC"], ns["cSTEP"], ns["cSUB"], ns["cKVL"], ns["CELL"], ns["PROCIMGPIC"])
    (C_H1, C_H2, C_GRAY, FONT, PAGE_W) = (ns["C_H1"], ns["C_H2"], ns["C_GRAY"], ns["FONT"], ns["PAGE_W"])
    WD_ALIGN_PARAGRAPH = ns["WD_ALIGN_PARAGRAPH"]
    WD_TABLE_ALIGNMENT = ns["WD_TABLE_ALIGNMENT"]
    RGBColor, Cm, Pt = ns["RGBColor"], ns["Cm"], ns["Pt"]
    bm_name, link_run = ns["bm_name"], ns["link_run"]

    data = json.load(open(os.path.join(HERE, "flows.json"), encoding="utf-8"))
    meta = {k: v for k, v in (m.split("：", 1) for m in data["meta"])}
    flows = data["flows"]
    chosen = [f for f in flows if (not SAMPLE or f["code"] in SAMPLE_FLOWS)]

    # 图 1 用资料里已有的功能结构图（与本仓库思维导图模式的导出同源）
    src_png = r"D:\OTC-260906-yongxin_WMS\资料\永鑫WMS系统功能结构.png"
    fig1 = "WMS-功能结构.png"
    if os.path.exists(src_png):
        shutil.copyfile(src_png, os.path.join(FIGS, fig1))

    # ============================ 封面 ============================
    for _ in range(5):
        fmt_par(doc.add_paragraph(), before=0, after=0, line=1.0)
    p = doc.add_paragraph()
    style_run(p.add_run("软 件 蓝 图"), size=34, bold=True, color=C_H1)
    fmt_par(p, before=0, after=6, line=1.1, align=WD_ALIGN_PARAGRAPH.CENTER)
    p = doc.add_paragraph()
    style_run(p.add_run(DOC_SUBTITLE), size=16, color=C_H2)
    fmt_par(p, before=0, after=34, align=WD_ALIGN_PARAGRAPH.CENTER)

    info = [
        ("项目名称", meta.get("适用项目", "【待填写】")),
        ("文档名称", DOC_TITLE + "（需求对接与方案确认书）"),
        ("文档编号", "【待填写】"),
        ("版本号", meta.get("版本", "V1.0")),
        ("编制单位", meta.get("编制单位", "【待填写】")),
        ("编制人 / 日期", f"【待填写】　/　{meta.get('编制日期', '【日期】')}"),
        ("审核人 / 日期", "【待填写】　/　【YYYY 年 MM 月 DD 日】"),
        ("批准人 / 日期", "【待填写】　/　【YYYY 年 MM 月 DD 日】"),
        ("文档状态", "☑ 草稿　　□ 评审中　　□ 已确认"),
    ]
    t = doc.add_table(rows=0, cols=2)
    t.style = doc.styles['Table Grid']
    t.alignment = WD_TABLE_ALIGNMENT.CENTER
    t.autofit = False
    for k, v in info:
        tr = t.add_row()
        c0, c1 = tr.cells[0], tr.cells[1]
        fill_cell(c0, k, bold=True, size=10, color=RGBColor(0x1F, 0x38, 0x64))
        set_cell_bg(c0, "DCE6F1")
        fill_cell(c1, v, size=10)
        c0.width = Cm(5.0)
        c1.width = Cm(11.6)
    t.columns[0].width = Cm(5.0)
    t.columns[1].width = Cm(11.6)
    set_tbl_width(t, PAGE_W)
    for _ in range(3):
        fmt_par(doc.add_paragraph(), before=0, after=0, line=1.2)
    P(f"本文档为 {DOC_TITLE} 的需求对接与方案确认用文档，须由甲乙双方共同评审确认。",
      size=9.5, color=C_GRAY, align=WD_ALIGN_PARAGRAPH.CENTER)
    PAGEBREAK()

    # ============================ 系统功能与流程 ============================
    H1("系统功能与流程")
    if os.path.exists(os.path.join(FIGS, fig1)):
        IMG(fig1, "图 1　永鑫WMS系统功能结构图", max_h=22.5)
    # 流程图阅读约定：动作写到可执行粒度，信息来源统一用（来源：X）标注
    P("流程图中所有节点均按「谁、用什么、做什么」描述操作动作；括号内为该动作的信息来源。",
      size=9.5, color=C_GRAY, align=WD_ALIGN_PARAGRAPH.LEFT)
    P("来源口径：登录账号＝WMS登录账号绑定的员工身份；设备台账＝WMS设备档案；ERP＝金蝶云星空；MES＝金蝶智慧车间；"
      "WMS库存＝系统实时库存；实盘＝现场清点；上位单据＝ERP下发的业务单据。",
      size=9.5, color=C_GRAY, align=WD_ALIGN_PARAGRAPH.LEFT)
    PAGEBREAK()

    # 汇总表：编号列渲染为可跳转到对应流程小节的链接（沿用模板的做法）
    rows = [[r[0], r[1], r[2], r[3]] for r in data["summary_rows"][1:]] if data["summary_rows"] else \
           [[str(i + 1), f["code"], f["name"], f["desc"][:40]] for i, f in enumerate(flows)]
    t = TBL(["序号", "流程编号", "名称", "说明"], rows, weights=[0.9, 2.0, 3.4, 6.4], size=8.5)
    for ri in range(1, len(t.rows)):
        cell = t.rows[ri].cells[1]
        pid = cell.text.strip()
        if pid.startswith("YX-WMS-"):
            cell.text = ""
            p2 = cell.paragraphs[0]
            p2._p.append(link_run(bm_name(pid), pid, size=8.5))
            fmt_par(p2, before=1, after=1, line=1.25, align=WD_ALIGN_PARAGRAPH.CENTER)

    # ============================ 每条流程 ============================
    for idx, flow in enumerate(chosen):
        PROC(flow["code"], flow["name"])
        # 流程说明：按句号切成多段，贴合格子柜模板「一句一行」的观感
        for sent in [s for s in flow["desc"].replace("\n", "").split("。") if s.strip()]:
            cSTEP(CELL, sent.strip() + "。")
        if flow["exceptions"]:
            cSUB(CELL, "异常处理")
            for ex in flow["exceptions"]:
                cSTEP(CELL, ex.lstrip("- ").strip())
        figno = idx + 2 if SAMPLE else flows.index(flow) + 2
        PROCIMGPIC(CELL, f"SWIM-{flow['code']}.png",
                   f"图 {figno}　{flow['code']}　{flow['name']}流程图")

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    doc.save(OUT)
    print("SAVED:", OUT)
    print("流程数:", len(chosen), " 汇总表行数:", len(rows))


if __name__ == "__main__":
    main()