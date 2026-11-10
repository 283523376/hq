# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Language

Answer in Simplified Chinese first, unless the user explicitly requests another language. Both JeecgBoot subprojects declare this rule in their own `CLAUDE.md` / `AGENTS.md`, and all `wms-*` code, docs, and artifacts are written in Chinese.

## Workspace layout

**2026-11-09 起**：根目录改名为 `D:\OTC-260906-yongxin_WMS`（原 `D:\yongxin-WMS`，旧路径已不存在），且分两层——根下 `资料\` / `软件蓝图\` / `架构设计\` 是客户交付层，`D:\OTC-260906-yongxin_WMS\code\` 才是代码工作区（本文件所在、也是 Claude Code 的工作目录）。

**2026-11-10 起**：代码仓库目录再改名 `code\JeecgBoot\` → **`code\yongxin_WMS\`**（git 仓库本体不受影响，remote 仍是 `Guangdong-One-Top/yongxin-WMS`）。本文档与历史会话里所有 `JeecgBoot/`、`code/JeecgBoot` 路径引用一律按 `code/yongxin_WMS` 理解；活脚本中唯一一处硬编码（`tmp_files/.tmp_env/start-backend.sh` 的 cd 路径）已同步改好。

| Path | Version control | What it is |
| --- | --- | --- |
| `D:\OTC-260906-yongxin_WMS\code\yongxin_WMS\` | git repo | The JeecgBoot low-code platform (Spring Boot backend + Vue 3 frontend) being adapted into 永鑫WMS（目录 2026-11-10 前叫 `JeecgBoot`） |
| `D:\OTC-260906-yongxin_WMS\code\wms-blueprint\` | none | Python pipeline: source requirement docs → swimlane SVGs, LogicFlow JSON, client-facing `.docx` blueprint |
| `D:\OTC-260906-yongxin_WMS\code\wms-logicflow-designer\` | git repo | Vue 3 + LogicFlow SPA: read-only WMS mind map + swimlane flowchart editor |
| `D:\OTC-260906-yongxin_WMS\资料\` | none | 客户原始材料：方案 PPTX、堆垛机/米塔资料、金蝶同步说明、`关键步骤.md`（历史会话摘录）、`永鑫项目提示词/` |
| `D:\OTC-260906-yongxin_WMS\软件蓝图\` | none | 蓝图各版本 `.docx`（V2.6 最新）、`永鑫WMS软件蓝图原始文档.md`、`永鑫WMS系统功能结构.png` |
| `D:\OTC-260906-yongxin_WMS\架构设计\` | none | 空目录，2026-11-09 预留给架构文档 |

`code\` 内部是**工作区，不是 repo** —— 没有 git 仓库；三个子项目里两个各自是 git 仓库。每个有 `CLAUDE.md` 的子项目对自己的范围有权威性，本文件只覆盖跨项目的那部分。

**项目范围（2026-10-19 起）**：永鑫WMS 只交付「半成品立体仓 + 成品敏捷仓」。
「原材料线边柜 + 焊接线边仓」（方案 A）已单独立项移出：采购入库/检验/供应商/订单菜单与入口已停用，
代码与历史数据保留、可回滚；设计见 `yongxin_WMS/docs/superpowers/specs/2026-10-19-yx-wms-scope-reduction-line-side-design.md`。

WMS 的实际代码落点（都在 `yongxin_WMS` 内）：

- **后端**：`jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms`，Java 包 `org.jeecg.modules.wms`，业务表统一 `wms_` 前缀；Flyway 脚本在 `jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/`（注意比上面多一层 `jeecg-boot/`）。
- **前端**：`jeecgboot-vue3/src/views/wms/`，按业务域分子目录：`audit`、`common`、`customer`、`defect`、`device`、`inbound`、`inventory`、`inventoryLedger`、`material`、`materialGroupMap`、`order`、`outbound`、`print`、`production`、`stocktake`、`supplier`、`sync`、`systemConfig`、`transfer`、`warehouse`、`warning`、`workOrder`。
- **设计/计划/验收**：`yongxin_WMS/docs/superpowers/specs/` 与 `plans/`，命名 `YYYY-MM-DD-yx-wms-<主题>.md`。既有惯例是「先 spec → 再 plan → 完工写验收报告」，动某个业务域前先读对应文档。

Read the relevant subproject `CLAUDE.md` before editing code there — they contain detailed, hard-won architecture notes:

- `yongxin_WMS/jeecg-boot/CLAUDE.md` — backend (modules, MyBatis-Plus/Shiro/AutoPoi conventions, DB setup)
- `yongxin_WMS/jeecgboot-vue3/CLAUDE.md` and `AGENTS.md` — frontend (bootstrap sequence, pinia stores, `defHttp`, view/component conventions)
- `wms-logicflow-designer/CLAUDE.md` — designer internals (document-as-source-of-truth, grid anchoring, LogicFlow pitfalls)

`yongxin_WMS/README.md` / `README-AI.md` / `README.en-US.md` 与 `.github/` 下都是**上游 JeecgBoot 的通用说明**，不描述本项目的 WMS 改造；本项目的权威说明是根 `CLAUDE.md` + 三个子项目的 `CLAUDE.md`。仓库内没有 `.cursor/rules`、`.cursorrules` 或 copilot 指令文件。

The JeecgBoot frontend `AGENTS.md` references `E:\workspace-cc-jeecg\...` and SVN; those are inherited from upstream and do **not** describe this checkout. In this workspace `jeecgboot-vue3/` is **not** a separate repository — it is a subdirectory of the single `yongxin_WMS` git repo（目录 2026-11-10 前叫 `JeecgBoot`；verified 2026-10-16: `jeecgboot-vue3/.git` does not exist; `git rev-parse --show-toplevel` there returns `D:/OTC-260906-yongxin_WMS/code/yongxin_WMS`). Backend and frontend changes therefore land in **one** history — commit them from the repo root, not from the frontend directory.

## How the subprojects relate

- `wms-blueprint` produces the customer-facing deliverable: 《永鑫WMS软件蓝图（需求对接与方案确认书）》 (`wms-blueprint/out/*.docx`), with Figure 1 being the WMS functional-structure diagram that is **the same content the designer's 思维导图 mode renders** (see the comment at `wms-blueprint/build_wms_blueprint.py:65`). 最快找需求看 `wms-blueprint/WMS蓝图关键信息.md`（注意该摘要正文仍是 V1.0 的 22 条历史全量，V2.0~V2.6 的差异只写在 §1.1 的注释里；**以 `flows.json`/`flows.mmd` 的 V2.6、18 条流程为准**）；原始需求源是 `D:\OTC-260906-yongxin_WMS\软件蓝图\永鑫WMS软件蓝图原始文档.md`。需求条目编号 `YX-WMS-*` 与 `yongxin_WMS/docs/superpowers/plans/` 里的验收报告一一对应，查「某条流程做到哪了」两处对着看。
- `wms-logicflow-designer` is the in-house viewer/editor for that functional structure and for per-process swimlane flowcharts.
- `yongxin_WMS`（目录 2026-11-10 前叫 `JeecgBoot`）is the runtime platform the actual WMS is to be built on. The blueprint's process list (`YX-WMS-*`) is the requirement set it must implement.
- **所有临时产物统一放 `tmp_files/`**（2026-11-06 整理：`code/` 根目录只允许 `.claude/`、`.pi/`、`yongxin_WMS/`、`proofshot-artifacts/`、`tmp_files/`、`wms-blueprint/`、`wms-logicflow-designer/` 与 `CLAUDE.md`）。
  `tmp_files/.tmp_extract/` 是领域源材料的纯文本抽取（`docx1.txt`、`pptx1.txt`…），由同目录 `extract_*.py` 生成，原 `.docx`/`.pptx` 才是事实源。
  `tmp_files/` 下其余 `.tmp_*`（`.tmp_build/`、`.tmp_e2e/`、`.tmp_env/`、`.tmp_b2_backup/`、`.tmp_blueprint_*/`、`.tmp_kingdee_recon/`、`.tmp_mita/`、`.tmp_patch/` 及零散 `.tmp_*.json|txt`）
  都是调试脚本、日志、数据库快照与探测残留——**永远不要当作事实源**。历史截图与调试残留（`kd-*.png`、`wms-0*.png`、`kd-push*.js`、`({v`、`--full-page`、`o.text))'`）也已归档到 `tmp_files/`。
  例外：`proofshot-artifacts/` 是 ProofShot 工具的默认产物目录，仍留在根目录。

## Commands per subproject

### `wms-logicflow-designer` (pnpm)

```bash
pnpm install
pnpm dev          # Vite dev server with HMR
pnpm build        # vue-tsc -b && vite build  (type-check gates the build)
pnpm test         # vitest run
```

`pnpm test` and `pnpm build` catch different things — vitest strips types without checking them, so run both. Neither can see layout; visual changes require `pnpm dev`.

### `yongxin_WMS/jeecg-boot` (backend, Maven)

```bash
# `mvn` is NOT on PATH on this machine and there is no mvnw — every Maven command
# goes through a wrapper that sets JAVA_HOME (OpenJDK 17) and calls Maven 3.9.9.
# **2026-11-09 起 `D:\tools` 已不存在**，旧笔记里的 `/d/tools/m.sh` 全部失效；
# 唯一可用的包装器是 `D:/OTC-260906-yongxin_WMS/code/tmp_files/.tmp_env/m.sh`
# （内部写死 Maven 3.9.9 发行包 `D:\通用备份\tools\apache-maven-3.9.9`）。
# `-P dev` is ALWAYS required: the root pom marks both `dev` and `SpringCloud`
# activeByDefault, so a bare run pulls the cloud modules into the reactor and leaves
# `@profile.name@` unresolved (see yongxin_WMS/jeecg-boot/CLAUDE.md).
M=/d/OTC-260906-yongxin_WMS/code/tmp_files/.tmp_env/m.sh
bash $M -P dev clean package                              # tests skipped by default
bash $M -P dev clean package -DskipTests=false            # with tests
cd jeecg-module-system/jeecg-system-start && bash $M -P dev spring-boot:run   # context-path /jeecg-boot; on this machine start with --server.port=8081, see below
bash $M -P dev test -DskipTests=false -pl <module> -Dtest=<TestClassName>     # single test class
# 加 `-am` 时必须补 `-Dsurefire.failIfNoSpecifiedTests=false`，否则上游模块没有匹配测试会直接断掉

# 体检脚本（在 yongxin_WMS/ 下执行，Python 3）
python scripts/wms-demo-data-check.py          # 演示数据自查（查库 + 调接口，示例单号失效时先跑它）
python scripts/wms-status-options-check.py     # 后端状态常量 ↔ 前端下拉一致性；退出码 1 = 有缺口
python scripts/md_to_pdf.py <in.md> [out.pdf]  # md → PDF（中文排版，无头 Chromium）
```

### `yongxin_WMS/jeecgboot-vue3` (frontend, pnpm)

```bash
pnpm dev          # port 3100; this checkout proxies to localhost:8081/jeecg-boot — gitignored .env.development.local overrides .env.development (see below)
pnpm build        # production build → dist/
npx eslint src/path/to/file.vue
pnpm batch:prettier
```

No unified `lint` or `test` script exists; Jest is configured but not wired into `package.json`.

### `wms-blueprint` (Python 3, run from inside the directory)

```bash
# 数据变换 —— 都会重写 flows.json / flows.mmd，按版本顺序跑
# 当前版本 V2.6（18 条）：日常只跑 apply_v26_changes.py 即可（import flows_v26.py，其余 17 条继承 V2.5）
python apply_v26_changes.py        # V2.6：RK-001 改「改刀料」→ flows.json/flows.mmd（18 条）
python apply_v25_changes.py        # V2.5：新增 DY-001 单据打印（17 条）；重放/回滚时才用
python apply_v23_changes.py        # V2.4 流程数据（含来源标注，17 条；脚本名沿用 V2.3）
python rebuild_v23.py              # 仅留档用：从 V2.4 回滚出 V2.3（16 条，去掉 RK-001）
# 以下三步是 V1→V2.2 的历史路径，只有从原始 .md 全量重建时才需要
python parse_md.py                 # 原始 .md → flows.json（22 条 V1 流程）
python make_scope_v2.py            # 范围收窄 22→16（一次性，先备份到 _backup_v1/）
python apply_erp_sync_v3.py        # V2.2 ERP 同步改造（BD-002/RK-002/RK-005/CK-001/CK-002/CK-004）

# 生成链路
python parse_mmd.py                # flows.mmd   → mmd.json
python map_mmd.py                  # Mermaid↔表格节点匹配覆盖率 → map_report.json（先跑，看匹配率）
python gen_wms_figs_mmd.py         # flows.json + mmd.json → figs/*.svg + capture.json
python "D:/通用备份/永鑫格子柜/yongxin-prd-master/svg2png.py" <figs 下各 .svg 的绝对路径>   # 必做：docx 插的是 PNG
python build_wms_blueprint.py --sample   # sample docx (2 processes)
python build_wms_blueprint.py            # full docx（当前 18 条流程）→ out/
python gen_designer_docs.py        # flows.json + mmd.json → wms-logicflow-designer/data/YX-WMS-*.json（OUT_DIR 仍是搬迁前路径，先看下方对照表）

# 旧路径（保留，当前不用）
python gen_wms_figs.py             # 同图，但连线用启发式而非 Mermaid
python gen_wms_logicflow.py        # flows.json + capture.json → 外部 logicflow/data/（历史编辑器）
```

## Local runtime conventions (this machine)

Machine-specific facts, all verified in practice. They apply workspace-wide and are easy to get wrong:

- **The backend actually runs on port 8081**, not the 8080 declared in `application-dev.yml` — 8080 is occupied long-term by an unrelated `python.exe`, so the backend is started with `--server.port=8081`. The gitignored `jeecgboot-vue3/.env.development.local` retargets `VITE_PROXY` and `VITE_GLOB_DOMAIN_URL` to `localhost:8081/jeecg-boot`; `.env.development` still says 8080 — judge actual routing by the `.local` file, never by `.env.development`.
- **After changing `jeecg-boot-module-wms` code, two steps are mandatory, otherwise the new endpoints 404 while the system looks perfectly healthy:** (1) `bash /d/OTC-260906-yongxin_WMS/code/tmp_files/.tmp_env/m.sh -P dev -pl jeecg-boot-module/jeecg-boot-module-wms install -DskipTests` — when running `spring-boot:run`, sibling modules resolve from `~/.m2`, not from the reactor; (2) **restart the backend JVM** — a running process never picks up the new jar. Diagnostic signature: pages and menus load fine (they come from the DB) but the wms API returns 404 「路径不存在，请检查路径是否正确」; the same path returns 401 「token为空」 unauthenticated and 404 authenticated — that difference means "endpoint not registered".
- **Flyway migration failures do not stop startup.** `FlywayConfig` catches `FlywayException` and logs one ERROR line, so a broken script still boots looking fine. After adding or changing a migration script: grep the startup log for 「flyway执行sql脚本失败」 (count must be 0), confirm the row in `flyway_schema_history` has `success = 1`, and note that `spring-boot:run` reads `jeecg-system-start/target/classes/flyway/` — after deleting or renaming a source script, run `bash /d/OTC-260906-yongxin_WMS/code/tmp_files/.tmp_env/m.sh -P dev clean process-resources -pl jeecg-module-system/jeecg-system-start` or the stale copy resurfaces as `Found more than one migration with version …`.
- **MySQL CLI exists but is not on `PATH`** (verified 2026-10-19): call it by absolute path, e.g. `"/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe" -uroot -proot yongxin_wms -e "SELECT ..."`. Row-level DB evidence can use this directly; there is no usable Python MySQL driver, so scripted access otherwise goes through the HTTP API or a Java test.
- **金蝶测试账套的联调凭据在 `tmp_files/.tmp_env/kingdee.env`**（`export KINGDEE_ERP_*=…`，仓库外、勿提交）。后端启动前必须
  `set -a && . tmp_files/.tmp_env/kingdee.env && set +a`，否则所有金蝶同步/回写都按「未配置」跳过（Job 会静默不跑）。
  排错先用 `python yongxin_WMS/scripts/kingdee-formid-probe.py --configured` 验证登录与 formId 可用——它已处理
  金蝶「响应是 text/plain」「ExecuteBillQuery 必须包 `{"data":…}`」这两个坑。
- **Commit convention in `yongxin_WMS`**: Conventional Commits with the `wms` scope — `feat(wms): …`, `fix(wms): …`, `docs(wms): …` — committed from the repo root (backend + frontend share one history). `wms-logicflow-designer` is committed separately in its own repo.

## `wms-blueprint` pipeline

The chain is one-directional; regenerate downstream artifacts after changing upstream data:

```
D:\OTC-260906-yongxin_WMS\软件蓝图\永鑫WMS软件蓝图原始文档.md
        │  parse_md.py            (22 条 V1)
        │  make_scope_v2.py       (22→16，备份 _backup_v1/)
        │  apply_erp_sync_v3.py   (V2.2 ERP 同步改造)
        │  apply_v23_changes.py   (V2.4：按已落地设计重写 + 补回 RK-001，flows_v24.py)
        │  apply_v25_changes.py   (V2.5：新增 DY-001 单据打印，18 条，flows_v25.py)
        │  apply_v26_changes.py   (V2.6：RK-001 改「改刀料」，flows_v26.py)
        ▼
    flows.json ──────────────┬──► gen_wms_figs.py ──► figs/SWIM-*.svg  (旧路径：启发式连线)
        │                    │
   flows.mmd ─ parse_mmd.py  │
        ▼                    │
    mmd.json ── map_mmd.py ──┘  (coverage report; validates text matching before generating)
                    │
              map_mmd.py + gen_wms_figs.py
                    ▼
              gen_wms_figs_mmd.py  (same layout & numbering, but edges come from Mermaid)
                    │
                    ├──► figs/SWIM-*.svg ──(svg2png.py)──► figs/SWIM-*.png ──► build_wms_blueprint.py ──► out/*.docx
                    ├──► capture.json ──► gen_wms_logicflow.py ──► <external legacy editor>/data/*.json
                    └──► gen_designer_docs.py ──► wms-logicflow-designer/data/YX-WMS-*.json
```

`wms-blueprint` 有**两个** LogicFlow 写目标，别混淆：`gen_designer_docs.py` 写的是本仓库的 `wms-logicflow-designer/data/`（当前在用）；`gen_wms_logicflow.py` 写的是外部 `D:\通用备份\永鑫格子柜\...\logicflow\data\`（历史编辑器，只增不删）。

Two edge sources exist on purpose. The original document's step table supplies step numbers and grid positions (so figure step numbers match the docx body), but no arrows; `flows.mmd` supplies only the authoritative topology. `gen_wms_figs.py` therefore invents edges by heuristic (main spine row-by-row + exception column as branch terminators), while `gen_wms_figs_mmd.py` keeps the table layout and node numbering and swaps in the Mermaid edges. Run `map_mmd.py` first — it reports the Mermaid↔table match rate and flags ambiguous matches that would connect the wrong nodes.

### Hard external dependencies (absolute Windows paths)

> **2026-11-10 起外部依赖整体搬家**：`D:\永鑫格子柜\yongxin-prd-master\` → **`D:\通用备份\永鑫格子柜\yongxin-prd-master\`**
> （旧路径已不存在）。`build_wms_blueprint.py`、`gen_wms_figs.py`、`gen_wms_logicflow.py` 三处硬编码已同步改好；
> 这是「响亮失败」型依赖（文件找不到直接报错），但改错了会静默少图，重建蓝图前先确认这条路径存在。

This pipeline is **not self-contained**. It reads/writes outside the workspace:

- `D:\通用备份\永鑫格子柜\yongxin-prd-master\build_locker_blueprint.py` — layout helpers reused by `build_wms_blueprint.py`
- `D:\通用备份\永鑫格子柜\yongxin-prd-master\make_figs.py` — swimlane drawing engine (writes `.svg` only); its module-level `CAPTURE` is what `capture.json` holds
- `D:\通用备份\永鑫格子柜\yongxin-prd-master\gen_logicflow.py` — `convert_swimlane()`, reused by `gen_wms_logicflow.py`
- `D:\通用备份\永鑫格子柜\yongxin-prd-master\svg2png.py` — SVG→PNG（无头 Chrome/Edge 截图）；docx 只插 PNG，`gen_wms_figs_mmd.py` 只出 SVG，所以 build 前必须转一次
- `D:\通用备份\永鑫格子柜\yongxin-prd-master\logicflow\data\` — **write target** of `gen_wms_logicflow.py`; contains hand-edited `GZG` diagrams. The script only *adds* `YX-WMS-*.json` and *merges* (never overwrites) `manifest.json`, backing it up first. Preserve that behaviour.
- `D:\OTC-260906-yongxin_WMS\软件蓝图\永鑫WMS软件蓝图原始文档.md`, `D:\OTC-260906-yongxin_WMS\软件蓝图\永鑫WMS系统功能结构.png`（同名文件在 `资料\` 下还有一份 194KB 版，`build_wms_blueprint.py` 引的是这一份） — source content

`build_wms_blueprint.py:66` 对功能结构图做的是 `os.path.exists` 判断后静默跳过：上面这个路径写错不会报错，**Figure 1 会无声消失**，只留下一张缺图的 docx。

### 2026-11-09 两次目录调整后的硬编码路径（已修，别再改回去）

一天里发生了两次搬迁：① 原 `D:\yongxin-WMS` 整体下沉为 `code\`，`资料\`、`软件蓝图\` 提到同级；
② 根目录改名为 `D:\OTC-260906-yongxin_WMS`。`wms-blueprint` 里三处写死的绝对路径当时都是错的
（错法相同：**不报错、只产出错东西**），2026-11-09 已全部改好：

| 脚本 | 现在的值 | 之前错在哪 |
| --- | --- | --- |
| `parse_md.py:26` | `D:\OTC-260906-yongxin_WMS\软件蓝图\永鑫WMS软件蓝图原始文档.md` | 原写 `D:\资料\...`，该目录已不存在 |
| `build_wms_blueprint.py:66` | `D:\OTC-260906-yongxin_WMS\资料\永鑫WMS系统功能结构.png` | 同上；写错则 **Figure 1 无声消失**，docx 照常生成 |
| `gen_designer_docs.py:23` | `D:/OTC-260906-yongxin_WMS/code/wms-logicflow-designer/data` | 原写仓库外的旧根，`makedirs` 会在仓库外新建目录，设计器数据静默不更新 |

**待确认**：功能结构图有两份同名文件 —— `资料\`（194KB，2026-11-03）与 `软件蓝图\`（325KB，2026-10-10）。
脚本现在指 `资料\` 那份（对应搬迁前脚本引的位置）；若发现蓝图里的 Figure 1 是旧版，改成 `软件蓝图\` 那份。

对照：`D:\永鑫格子柜\...` 那组外部依赖是**响亮失败**的（`结构变了` / `找不到`），改坏了立刻能发现；
上表这三处当年恰恰相反——**静默少图、或把数据写到仓库外**，只能靠人看输出目录核对。这就是为什么它们值钱、也别再改回去。

### The "cut source and exec" reuse pattern

`build_wms_blueprint.py`, `gen_wms_figs.py`, and `gen_wms_logicflow.py` all reuse the external modules the same way, and it is **load-bearing**: none of those modules has a `__main__` guard, so a plain `import` would execute their driver loops and overwrite the unrelated 格子柜 artifacts. Instead each script reads the module's source, slices it off before its driver section (`# ===== 封面 =====`, `for spec, name in [`, `def main():`), `exec`s the remainder into a fresh namespace, then redirects `OUT` / `FIGDIR` to a local directory. Do not "simplify" this into an `import`, and do not edit the cut markers in a way that lets a driver section through.

Cross-script coupling to keep in sync: `gen_wms_figs.py` defines `node_type()` (lane-name → node type) and `flow_grid()` (assigns the `A01…` node ids). `gen_wms_figs_mmd.py` and `map_mmd.py` import these so figure ids and JSON ids stay identical — changing id assignment in one place without the other desynchronises the figures from the generated documents.

## `wms-logicflow-designer` (summary)

Two modes sharing no code: 思维导图 (`src/data/wms.ts` → `src/layout/mindmap.ts` → `WmsStructure.vue`, read-only) and 流程图 (`src/views/FlowEditor.vue` + `src/editor/`, an editable swimlane editor). In the editor the `FlowDoc` document is the single source of truth and stores **no coordinates** — pixel positions are derived from lane/step by `src/editor/geometry.ts`, so adding/removing lanes or steps reflows automatically. See its `CLAUDE.md` for the LogicFlow gotchas (viewport reset, `history: false`, `dbclick` event naming, no `@logicflow/extension` barrel imports).

## `yongxin_WMS`（JeecgBoot 平台）(summary)

Spring Boot backend + Vue 3 frontend for the low-code platform. Backend uses `jakarta` namespace, MyBatis-Plus, Shiro + JWT, AutoPoi, and a `Result<T>` response wrapper; entities follow the `Sys*` / `@TableName` / `IService`+`ServiceImpl` conventions. Frontend is a fork of Vben Admin with **BACK** permission mode (routes/menus fetched from the API) and a `defHttp` axios wrapper that MD5-signs requests. Both have authoritative `CLAUDE.md` files with the module map, stack table, and per-project conventions — read them there rather than duplicating here.

## 默认操作位置

写代码默认在 `yongxin_WMS/` 下操作；查需求去 `wms-blueprint/`；流程图/思维导图改动在 `wms-logicflow-designer/`。

## WMS 后端架构速览（跨文件，读多处才看得出）

`jeecg-boot-module-wms` 内部分三条线，改一条要同时看另外两条：

- **业务单据线**：`controller` → `service.impl` → `entity` → `wms_inventory` 库存行。主链是
  `WmsOrder`/`WmsWorkOrder` → `WmsInboundOrder` / `WmsOutboundOrder` / `WmsTransferOrder` / `WmsStocktakeOrder`，
  库存只由 `wms_inventory` 记账。状态与类型字符串全是**中文常量**，集中在 `constant/WmsConstants`，不写魔法值；
  库存数量变更必须用带条件的更新（如 `WmsInventoryMapper.deductQuantity` 的 `quantity >= qty`）做并发保护。
- **ERP 同步线**（`sync/` 包）：两层接口 —— `IWmsKingdee*Writer`（WMS→金蝶回写：生产入库/领料/销售出库/调拨/盘盈亏/不良品）
  与 `IWmsKingdee*Reader`（金蝶→WMS 读取：销售订单/用料清单/工序汇报），底层统一走 `WmsKingdeeFormClient`
  （WebAPI Save/Submit/Query）。`IWms*SyncGateway` 是同步与设备调用的网关接口（ERP 单据网关 + WCS/RMS），
  接口化是为了外部系统未就绪时能 mock。
  新增一种回写 = Writer 接口 + Impl，并到 `constant/WmsKingdeeSyncScope` 登记；该枚举是同步范围的**唯一扩展点**，
  但当前只用于展示与对账、**不参与运行时开关**——实际开关在各 `wms.sync.erp.kingdee.*` 配置。
- **定时/重试线**（`job/` 包）：每个任务成对出现 `XxxJob` + `XxxJobRegistrar`。Registrar 在 `ApplicationReadyEvent`
  时注册 Quartz，并统一走 `WmsJobRegistrarSupport.isScheduled`（它会删除「有 JobDetail 但无 trigger」的残留，
  否则任务永远不触发、日志还写「已存在，跳过注册」）。**只加 Job 不加 Registrar 等于没接上。**
- **系统配置**：`constant/WmsConfigKey` 枚举是配置项的唯一事实源（中文名/分组/类型/默认值/候选值），
  前端渲染与后端校验都读它；**加配置项 = 加枚举值**，不改表结构。
- **审计**：写端点加 `@WmsAudit`（`audit/` 包），另有回写/同步两通道自动采集；新增写接口必须带审计（XT-002 口径）。
- **权限点（2026-11-10 RBAC 起）**：新增 `@RequiresPermissions` 权限点时，同一迁移里**授 3 个角色**——`admin` + `wms_admin` + `wms_manager`（`device`/`warehouse`/`systemConfig` 类系统管理码按矩阵只授 admin），授权行 id 用确定性拼接 `'192'||SUBSTR(permission_id,4)||角色位`（admin=A/901=1~905=5，保证幂等），并跑 `python scripts/wms-perms-check.py`（退出码 1=有缺口）。角色矩阵事实源：`yongxin_WMS/docs/superpowers/specs/2026-11-10-yx-wms-权限租户角色优化-design.md` §3.2。

## 当前进度与计划（快照 2026-11-10）

> 详细快照：`.pi/memory/project-progress.md`（**内容停在 2026-11-03，已落后**，只当历史背景看）。
> 权威来源始终是 `yongxin_WMS/docs/superpowers/plans/*验收报告.md` 与 `specs/*-design.md`；
> 本段只是索引，冲突时以文档与代码实测为准，过期就更新。
> 未决事项汇总在 `plans/2026-10-29-yx-wms-待办清单.md`（**2026-11-09 最新**；该文仍是 V2.4/17 条口径，
> 未计入 DY-001 与 V2.6，以本段为准；§4 有四条链路的真账套结论）。

**一句话**：蓝图 V2.6 的 **18 条流程已落地 17 条**，只剩 SB-002 未开工（DY-001 代码已落地、尚无独立验收报告）；
当前基线 **1576 个单测全绿**（2026-11-10 实跑 `-pl jeecg-boot-module/jeecg-boot-module-wms`）；
卡住的是业务口径、金蝶侧数据与硬件，不是代码。

- **范围**：只做半成品立体仓 + 成品敏捷仓（2026-10-19 收窄，线边柜方案 A 已移出）；蓝图 **V2.6，18 条流程**
  （V2.3 补回「外购/受托原材料入库 RK-001」；V2.5 新增「DY-001 单据打印」；
  V2.6 把 RK-001 的「大改小」改写为「改刀料」，与段差棒同口径直入半成品立库）。
  2026-10-26 追加暂缓：原材料分类、五金配件仓、包材仓、其余原材料事务流程。
- **已落地的 17 条**：BD-001~004 ｜ **RK-001**（外购 / 受托入库 / 受托不良退货全链路真账套验证过；V2.6 改刀料已改代码）｜
  RK-002 ｜ RK-005 ｜ CK-001（2026-11-09 `PRD_PickMtrl` 真账套开出 `SOUT10126110001`）｜ CK-002 ｜ CK-004 ｜ KC-001 ｜
  KC-002（盘亏 `QTCK10126110002`、盘盈 `QTRK10126110003` 均已真账套）｜ KC-004 ｜ **SB-001（2026-11-03）** ｜
  XT-001（⚠️ 报废未回写金蝶 `STK_MisDelivery`）｜ XT-002 ｜ DY-001（打印工作台 12 页签 + 7 类定稿模板 + 批量打印；待补验收报告）。
- **未开工 1 条（P3）**：SB-002 WCS/RMS 设备调度完善（等 `sync.rms.url` 与真机）。

### 关键时间线（近期）

- **2026-11-10**：
  - **仓库目录改名**：`code\JeecgBoot` → **`code\yongxin_WMS`**（git 不受影响，remote `Guangdong-One-Top/yongxin-WMS` 不变）；
    唯一受影响的活脚本 `tmp_files/.tmp_env/start-backend.sh`（cd 路径）已同步修正；repo 内部无绝对路径引用。
  - **蓝图 V2.6：RK-001 重写**——「大改小」改「改刀料」，与段差棒同口径直入半成品立库，删「加工/生成改制单」两步；
    代码侧 CONVERT 与 DIRECT 处理方式统一（登记→待分配→上架→库存→回写采购收料单），改制单菜单逻辑删除
    （`V3.9.5_57`+`V3.9.5_58`，代码与表保留可回滚）。见 `yongxin_WMS/docs/superpowers/specs/2026-11-10-yx-wms-rk001-gaidaoliao-design.md`。
  - **外部依赖搬家**：`D:\永鑫格子柜\yongxin-prd-master` → `D:\通用备份\永鑫格子柜\yongxin-prd-master`，三处脚本硬编码已改。
  - **权限/租户/角色设计文档出评审稿**：`yongxin_WMS/docs/superpowers/specs/2026-11-10-yx-wms-权限租户角色优化-design.md`（`3b8a20a3`）。
    结论：业务收敛租户 0、不启用套餐通道；5 个业务角色矩阵待用户评审；顺带发现 admin 角色缺 `wms:device:add/edit` 两权限点。
- **2026-11-09**：
  - **目录一天两动**：① 原 `D:\yongxin-WMS` 整体下沉为 `code\`，同级新建 `资料\`（客户原始材料）、
    `软件蓝图\`（各版本 docx + 原始需求 md + 功能结构图）、`架构设计\`（空，预留），`D:\资料\` 不再存在；
    ② 根目录改名为 **`D:\OTC-260906-yongxin_WMS`**（旧名 `D:\yongxin-WMS` 已不存在）。
  - 附带修复：`wms-blueprint` 的 `parse_md.py`、`build_wms_blueprint.py`、`gen_designer_docs.py` 三处硬编码
    已按新根改好（此前是静默错：少图 / 写到仓库外）；`tmp_files/.tmp_env/start-backend.sh` 同步改路径。
- **2026-11-09（晚间）**：
  - **盘盈 `QTRK` 真账套补通**（KC-002 的另一半）：盘点单 `PD202611090002` → **`QTRK10126110003`**（A 状态）。
    修了 `WmsStocktakeErpWriterImpl`：盘盈分录必须带 `FStockId`+`FStockStatusId`，盘亏侧不带也能存。
    另识别一个误诊坑：物料在金蝶被禁用时报的是「物料编码必填」，不是报文缺字段。单测 1510 → **1511**。
- **2026-11-09（下午）**：
  - **CK-001 生产领料 `PRD_PickMtrl` 首次真账套打通**：WMS `LL2119585299817132033`（MO1012615930，
    部分领料 2.9）→ 分配 → WCS 回执 → 取料确认 → 金蝶开出 **`SOUT10126110001`**（B 状态）。
    前提是 ERP 侧有货：D.02 在 101 账套零库存，临时把 `stock-code.stereo`（含 `stock-code-by-org.101`）
    指到真实半成品仓 W.26 跑通，跑完已改回 D.02。注意 `stock-code-by-org` 会覆盖通用值，只改一处不生效。
- **2026-11-06**：
  - **真账套四条链路跑完**（结果见 `plans/2026-10-29-yx-wms-待办清单.md` §4/§4.1）：外购→采购收料单 `CGSL016655` ✅、
    盘点回写 `QTCK10126110002` ✅；受托不良退货与改制→PRD_INSTOCK 两条 WMS 侧全通、ERP 侧分别卡在
    「受托方是客户 vs PUR_MRB 要供应商」和「没有业务流程=生产直接入库流程的生产订单」。过程中修掉 5 个
    只有真单据才暴露的缺陷（`9f538868`）：盘点回写配置缺失、`FLot` 需字典、盘点明细 `material_code` 恒空、
    盘亏缺部门、采购退货单分录键 `FPURMRBENTRY` + 备注字段 `FDescription`。
  - **工作区根目录整理**：所有 `.tmp_*` 与历史截图/调试残留移入 `tmp_files/`，根目录只留 7 个目录 + 本文件；
    金蝶凭据与日志现在在 `tmp_files/.tmp_env/`（移动时后端持有日志文件会 `Permission denied`，须先停后端）。
  - **受托不良退货改投 `STK_OEMInStockRETURN`**（收尾四条链路的最后一条）：Writer 由
    `WmsKingdeePurchaseReturnWriter*` 改名为 `WmsKingdeeConsignedReturnWriter*`，`write()` 增 `sourceErpBillNo` 参数
    （有金蝶受托入库单号时带 `FSrcBillNo`）；配置键 `kingdee.form.purchase-return` → `kingdee.form.consigned-return`
    （DB 行已改名）；真账套开出 **`STCLTL000281`**（B 状态），WMS 库存 5→2、记录「已退货」+ 回存单号。
    只有真单据才暴露的坑：分录键是 `FBillEntry`（照抄采购退料单的 `FPURMRBENTRY` 会被当成「没给分录」，
    金蝶反而报「单位/物料编码/保管者/货主必填」一串误导性错误）、备注是 `FNote`（不是 `FDescription`）、
    客户走 `FCustId`、数量走 `FQty`。单测 1509 → **1510** 全绿。
- **2026-11-05**：
  - 金蝶 **5 个 formId 全部确认并配置**（`PUR_PurchaseOrder`/`SAL_SaleOrder`/`PUR_ReceiveBill`/`STK_OEMInStock`/`PUR_MRB`）；
    受托入库回写改 **Push 下推**（`Sal_Order-STK_OEMInStock`，真账套开出 `STCLRK001438/1439/1440`）、
    段差棒直入改回写**采购收料单**（真账套 `CGSL016654`）。根因是 `WmsKingdeeFormClient.save()` 顶层参数
    只发了 3 个，缺 `IsVerifyBaseDataField` 等会让服务端按另一套规则解析 Model 并报误导性字段错（`9a85b3c1`）。
  - 客户档案主数据（受托方口径对齐金蝶客户）；**物料分组→WMS 分类映射表**（独立表 + 维护页面 + SOP）。
  - 前端：列表全局关键词改发裸值（修「界面搜索恒 0 条」的星号缺陷，11 页）、inline 表单错位（全局 `.ant-col{width:100%}` 根因）、
    生产入库录入行对齐、**行间距调节**（系统配置子菜单，0~200%）。
- **2026-11-04**：**V2.4 差距落地**（受托库存模型 `ownership_type/consignor_id` 六列唯一键、RK-001 到货登记、
  大改小改制单、RK-005 两类不良、CK-001 领料动作、5 个金蝶可配置单据、9 菜单 8 权限；`V3.9.5_47~49`）。
  整分支评审 1 Critical + 4 Important 全部整改；单测 1290 → **1449**。见 `plans/2026-11-04-yx-wms-v2.4-gap-验收报告.md`。
- **2026-11-03**：**SB-001 上位系统同步交付**（`V3.9.5_46`：对账批次/快照/流水/差异 4 表 + 12 接口 + 前端 5 页，
  单测 1167 → 1287）；真账套对账因业务口径未定未执行，失败链路已实测。见 `plans/2026-11-03-yx-wms-sb-001-验收报告.md`。
- **2026-11-02**：**XT-002 操作日志留痕交付**（`V3.9.5_43` 审计表 + `@WmsAudit`/回写/同步三通道采集 +
  影响面清单 + 查询导出（复用 KC-004 异步任务）+ 保留期清理 + **ERP 作废工单同步修复（方案 B：新增「已作废」终态）**）；
  独立评审 1 Critical + 4 Important + 7 Minor 全部整改（作废止损闭环、审计导出权限、UI 动作按单号留痕等）；
  单测 1084 → **1156**。**作废链路真账套端到端验证通过**：手工造出样本 `MO1012616533`
  （前台建单「直接入库-普通生产」→ 删用料清单 PPBOM → 作废），金蝶 `FCancelStatus A→B`、
  WMS 工单「待生产」→「已作废」、审计 before/after 完整；该单保留作常驻样本。
  「下游」口径 = 用料清单 PPBOM **+ 下级生产/委外订单**（两者都算）。
  见 `plans/2026-11-02-yx-wms-xt-002-验收报告.md` §3.5。
- **2026-11-02（XT-002 之后）**：
  - **XT-001 不良品处理出口交付**（`V3.9.5_44`）：不良品台账 `GET /wms/defect/list` + 报废出库
    `POST /wms/defect/scrap`（扣库存 / 建单 / 审计一步到位，`deductQuantity` 并发保护）+ 前端 `views/wms/defect`。
    **未做**：报废回写金蝶 `STK_MisDelivery`（结果 `syncSuccess=false`，不阻断本地）、退货（用户拍板本期不做）。
  - **混合入库单的不良品补回写**（`V3.9.5_45`）：`PRD_INSTOCK` 只有良品字段，不良品另开其他入库单（QTRK → 废品仓 F.03）；
    真账套验证 `SCRK101261100004`(10) + `QTRK10126110001`(2)。配套修了 `stock-code-by-org.101.scrap` 缺失——
    该覆盖表一旦存在就不会回落通用 `stock-code.scrap`，101 录混合单时不良品会写不进金蝶（生产同样会踩）。
  - **查询窗口统一模糊匹配**：7 个列表页支持任意连续子串。前 5 项是「保留 `QueryGenerator` 后手写 `like`」，
    不要图省事改回 `eq`（会丢日期区间等既有能力）；新增列表页照此办理。
  - 前端：列表页全局关键词框、左上角标题改「楠柏莞」、缩放弹层漂移修复。
- **2026-10-30（真账套端到端）**：
  - **CK-002 销售出库打通**：真账套开出 `SAL_OUTSTOCK`；回写由「直开」改为**关联发货通知单**（Save-with-Link，
    支持部分数量、草稿查重、占用口径扣减），见 `plans/2026-10-30-yx-wms-半成品方案B落地验收报告.md`。
  - **CK-001 走方案 B**：工序汇报草稿推进入口（`POST /wms/production/{id}/promote`）+ 回写豁免 + `FSrcBillNo` 对账。
    选型依据：`plans/2026-10-30-yx-wms-出库链路端到端验证报告.md` §8（方案 A 改工单流程可行但与车间流程冲突）。
  - 修 4 个出库链路必炸缺陷 + 独立评审 6 项 Critical/Important + 9 项 minor；单测 1055 → **1084**。
  - 前端：库存台账 150% 字号压列（裸 `a-table` 未接列宽缩放）、生产入库/出库状态下拉漏「草稿」。
  - 工具：`scripts/wms-demo-data-check.py`（演示数据自查）、`wms-status-options-check.py`（状态枚举一致性）、
    `md_to_pdf.py`（md→PDF）。
- **2026-10-29**：KC-004 库存查询与导出（`V3.9.5_42`，异步大导出 + 7 天清理）；CK-004 C 分支真账套审批验证。
- **2026-10-28**：KC-001 库存预警与批次追溯（`V3.9.5_37`）、仓库主数据全量同步、仓库编码与金蝶 1:1、
  产品类型改由入库来源判定、销售出库待办按发货通知单生成。
- **2026-10-27**：质量收口（任务注册器、47 个写端点权限码、输入校验、菜单图标离线注册）、863 单测。
- **2026-10-26**：主数据同步、外协出库骨架、样品出库、盘点（只 WMS 发起 → 回写 ERP）。
- **2026-10-22**：BD-002 工单 ERP 同步（P0）、RK-002 真实金蝶回写、CK-002 方向反转为 ERP→WMS（销售订单只读快照）。

### 当前阻塞（都不是代码问题）

1. **业务口径未定**：WMS 立库管哪些物料、对应金蝶哪个仓 —— `stock-code.stereo=D.02`（PCB 呆滞半成品专仓）
   仍是占位值，真实半成品库存在 Y.05/W.01/W.26。这条不定，CK-001 的 WMS 库存与 ERP 账对不上。
   （2026-11-09 联调时临时把 `stock-code.stereo` 与 `stock-code-by-org.101` 指到 W.26 才开出
   `SOUT10126110001`，跑完已改回 D.02 —— 这是绕行，不是口径已定。）
2. ~~受托不良退货的对象选择~~ **已闭环（2026-11-06）**：改投 `STK_OEMInStockRETURN`（受托加工材料退料单 /
   `STCLTL01_SYS`），配置键 `kingdee.form.consigned-return`，真账套开出 `STCLTL000281`。
   分录键是 `FBillEntry`、备注是 `FNote`、客户走 `FCustId` —— 别照抄采购退料单 `PUR_MRB` 的
   `FPURMRBENTRY`/`FDescription`/`FSupplierId`（见 `WmsKingdeeConsignedReturnWriterImpl` 类注释）。
3. **金蝶测试环境**：客户信用额度会拦 `SAL_OUTSTOCK` 提交（业务闸门，不绕过）；101 组织 18551 张生产订单中
   18515 张是「生产工序汇报入库流程」，无法下推生产入库。
4. **设备**：米塔 RMS `url` 为空，下发走模拟受理，物理搬运未真机验证。
5. ~~改制→生产入库（PRD_INSTOCK）~~ **已闭环（2026-11-06 拍板：半成品走工序汇报通道/方案 B）**：
   半成品入库单恒「无需同步」，WMS 不回写 `PRD_INSTOCK`，ERP 由车间汇报生成入库单（`claimFinished`/`submit`/
   `manualComplete` 三处豁免 + 手工同步显式拒绝 + 存量 3 条止血）。不再等「直接入库流程」的生产订单。
   盘点回写已于 2026-11-06 真账套打通。

### 已验证的产品缺口（记录备查）

- WMS **无「从零造库存」入口**：采购入库已停用、盘点要求先有账面。已验证绕法：挑「产物正好是别人要用的料」
  的工单走生产入库，形成两级制造链（见出库链路验证报告 §1）。
- 历史时点结存（如「9 月 30 日结存」）未做，`wms_inventory` 只有当前数量。
- `KINGDEE_ERP_ENABLED` 默认 false；物料双身份问题已于 2026-10-30 用 B2′ 解绑解决（原合并方案前提错误）。
