# 永鑫WMS 工作区备份

这是 `D:\OTC-260906-yongxin_WMS` 工作区的**备份与知识留存仓**，不是运行代码的主仓库。
目的是让后续项目能直接翻到「永鑫WMS 当时是怎么做的」：需求 → 蓝图 → 设计 → 计划 → 验收 → 代码。

## 分支地图

| 分支 | 内容 | 来源 |
| --- | --- | --- |
| `main` | 工作区快照：`code/wms-blueprint/`（蓝图生成流水线 + V2.0~V2.6 流程数据 + 各版 docx）、`code/CLAUDE.md` | 本仓 |
| `code/yongxin-wms/main` | 永鑫WMS 运行平台完整历史（Spring Boot + Vue 3），含 `docs/superpowers/` 全部设计与验收文档 | `Guangdong-One-Top/yongxin-WMS` |
| `code/yongxin-wms/feat/wms-kingdee-business-sync` | 金蝶工单同步开发分支 | 同上 |
| `code/yongxin-wms/283523376` | 历史开发分支 | 同上 |
| `code/wms-logicflow-designer/master` | 流程图 / 思维导图设计器完整历史 | 同名仓库 |

## 从哪里看起

- **想了解需求与方案**：`code/wms-blueprint/WMS蓝图关键信息.md` 是速查，`flows.json` / `flows.mmd` 是 V2.6、18 条流程的事实源。
- **想了解某条流程怎么落地的**：切到 `code/yongxin-wms/main`，在 `docs/superpowers/specs/` 找设计、`docs/superpowers/plans/` 找验收报告，再对代码 `jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms`。
- **想了解工程约定（含踩过的静默失败）**：`code/yongxin-wms/main` 根下的 `CLAUDE.md`，以及 `jeecg-boot/CLAUDE.md`、`jeecgboot-vue3/CLAUDE.md`。

## 恢复工作区

```bash
git clone -b main https://github.com/283523376/hq.git OTC-260906-yongxin_WMS
cd OTC-260906-yongxin_WMS

# 代码仓（从备份分支取完整历史）
git clone -b code/yongxin-wms/main https://github.com/283523376/hq.git code/yongxin_WMS
git -C code/yongxin_WMS remote set-url origin git@github.com:Guangdong-One-Top/yongxin-WMS.git

git clone -b code/wms-logicflow-designer/master https://github.com/283523376/hq.git code/wms-logicflow-designer
```

客户原始资料（`资料/`、`软件蓝图/`）不在本仓库，需从本地或其他渠道另存恢复。

## 不在此仓库的内容

| 路径 | 原因 |
| --- | --- |
| `资料/` | 客户方案 PPTX、堆垛机/米塔厂商资料等第三方材料 |
| `软件蓝图/` | 含兆鑫、格子柜等其他客户的蓝图版本 |
| `code/tmp_files/` | 调试残留、日志、数据库快照；`.tmp_env/kingdee.env` 含明文凭据 |
| `code/yongxin_WMS/`、`code/wms-logicflow-designer/` | 以分支镜像备份，不在 `main` 重复存一份 |

## 更新备份

```bash
# 1) 工作区快照（wms-blueprint 等）
git add -A
git commit -m "backup: $(date +%F)"
git push origin main

# 2) 代码镜像（两个仓库各自的全部本地分支）
git -C code/yongxin_WMS push origin 'refs/heads/*:refs/heads/code/yongxin-wms/*'
git -C code/wms-logicflow-designer push origin 'refs/heads/*:refs/heads/code/wms-logicflow-designer/*'
```
