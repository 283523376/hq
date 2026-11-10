# -*- coding: utf-8 -*-
"""把 WMS 的 22 张泳道图写成 LogicFlow JSON，放进 8080 编辑器的 data/。

复用 gen_logicflow.py 的 convert_swimlane：它把 make_figs 的 CAPTURE 转成编辑器要的
graphData（步骤列 / 泳道节点 / 流程节点 / 连线折点全是那一套）。做法同样是**裁源码 + exec**，
只是额外把它顶部的 `import make_figs as mf` 换成我这里已经 exec 好的引擎命名空间，
避免执行那个模块的驱动段。

写盘策略（那一份 data/ 里有别人手工编辑过的 23 个 GZG 图，README 明确警告不要覆盖）：
  - **只新增** YX-WMS-*.json；
  - manifest.json **合并**（保留原有全部条目）而不是覆盖，且**先备份**。
"""
import json
import os
import shutil
import time

HERE = os.path.dirname(os.path.abspath(__file__))
GEN_LC = r"D:\通用备份\永鑫格子柜\yongxin-prd-master\gen_logicflow.py"
DATA = r"D:\通用备份\永鑫格子柜\yongxin-prd-master\logicflow\data"


class EngineShim:
    """把 exec 出来的引擎命名空间包一层，让它支持 mf.GRID / mf.TYPE 这样的**属性**访问。

    为什么需要：make_figs 是个模块，convert_swimlane 里写的是 mf.GRID（属性）；
    而我这边 exec 出来的是 dict（键）。垫一层比改他们的源码干净。
    """

    def __init__(self, ns):
        self._ns = ns

    def __getattr__(self, key):
        try:
            return self._ns[key]
        except KeyError as exc:
            raise AttributeError(key) from exc


def load_converter(engine):
    src = open(GEN_LC, encoding="utf-8").read()
    src = src.replace('os.environ["FLOW_JSON"] = "1"', "")
    if "import make_figs as mf" not in src:
        raise SystemExit("gen_logicflow.py 结构变了：找不到 import make_figs as mf")
    src = src.replace("import make_figs as mf", "pass  # 引擎由外部注入")
    marker = "def main():"
    if marker not in src:
        raise SystemExit("gen_logicflow.py 结构变了：找不到 def main()")
    src = src[: src.index(marker)]
    ns = {"__name__": "gen_lc", "__file__": GEN_LC, "os": os, "json": json,
          "mf": EngineShim(engine)}
    exec(compile(src, GEN_LC, "exec"), ns)
    return ns["convert_swimlane"]


def main():
    # 引擎只用来取常量（TYPE / LANE_* / GRID）；**CAPTURE 必须从 capture.json 读**：
    # load_engine() 给的是空 CAPTURE，它是在调用 swimlane() 时才被填充的，
    # 而 22 张图已经由 gen_wms_figs.py 画过、CAPTURE 已落盘。
    import gen_wms_figs as gw
    engine = gw.load_engine()
    convert = load_converter(engine)

    flows = json.load(open(os.path.join(HERE, "flows.json"), encoding="utf-8"))["flows"]
    capture = json.load(open(os.path.join(HERE, "capture.json"), encoding="utf-8"))["capture"]

    os.makedirs(DATA, exist_ok=True)
    written, manifest_new = [], []
    for flow in flows:
        fname = f"SWIM-{flow['code']}.svg"
        cap = capture.get(fname)
        if not cap:
            print("跳过（无 CAPTURE）:", fname)
            continue
        data = convert(cap, flow["code"], flow["name"])
        out = os.path.join(DATA, f"{flow['code']}.json")
        with open(out, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        written.append(flow["code"])
        manifest_new.append({
            "name": flow["code"], "title": flow["name"], "kind": "swimlane",
            "file": f"{flow['code']}.json",
            "nodes": len(data["nodes"]), "edges": len(data["edges"]),
        })

    # ---- 合并 manifest（先备份）----
    mpath = os.path.join(DATA, "manifest.json")
    old = json.load(open(mpath, encoding="utf-8"))
    before = len(old)
    bak = mpath + ".bak-" + time.strftime("%Y%m%d-%H%M%S")
    shutil.copyfile(mpath, bak)
    kept = [m for m in old if not m["name"].startswith("YX-WMS-")]     # 幂等：重跑不叠加
    merged = kept + manifest_new
    with open(mpath, "w", encoding="utf-8") as f:
        json.dump(merged, f, ensure_ascii=False, indent=2)

    # ---- 核对：原有条目必须一条不少 ----
    after = json.load(open(mpath, encoding="utf-8"))
    kept_names = {m["name"] for m in kept}
    still_there = {m["name"] for m in after} >= kept_names
    print(f"写入 JSON: {len(written)} 个 -> {DATA}")
    print(f"manifest: 原有 {before} 条 -> 保留 {len(kept)} 条 + 新增 {len(manifest_new)} 条 = {len(after)} 条")
    print(f"原有条目是否全部保留: {still_there}   备份: {os.path.basename(bak)}")
    print("前 3 条:", [m['name'] for m in after[:3]])
    print("末 3 条:", [m['name'] for m in after[-3:]])


if __name__ == "__main__":
    main()