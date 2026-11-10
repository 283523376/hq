# 永鑫WMS 工作区备份

这是 `D:\OTC-260906-yongxin_WMS` 工作区的**备份与知识留存仓**，不是运行代码的主仓库。
目的是让后续项目能直接翻到「永鑫WMS 当时是怎么做的」：需求 → 蓝图 → 设计 → 计划 → 验收 → 代码。

## 仓库结构

`main` 一个分支就是完整工作区：

```
main
├── README.md
├── .gitignore / .gitattributes
└── code/
    ├── CLAUDE.md                  # 工作区级说明（端口、Maven 包装器、金蝶凭据等）
    ├── wms-blueprint/             # 蓝图生成流水线 + V2.0~V2.6 流程数据 + 各版 docx
    ├── yongxin_WMS/               # 主代码仓（Spring Boot + Vue 3，子树合并，历史完整）
    └── wms-logicflow-designer/    # 流程图 / 思维导图设计器（子树合并，历史完整）
```

`code/yongxin_WMS` 与 `code/wms-logicflow-designer` 是用 `git subtree` 合并进来的，
保留了各自全部提交历史：`git log -- code/yongxin_WMS` 能直接看到 2800+ 条记录。

## 从哪里看起

- **需求与方案**：`code/wms-blueprint/WMS蓝图关键信息.md` 是速查；`flows.json` / `flows.mmd` 是 V2.6、18 条流程的事实源。
- **某条流程怎么落地的**：`code/yongxin_WMS/docs/superpowers/specs/` 找设计、`plans/` 找验收报告，
  再看 `code/yongxin_WMS/jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms`。
- **工程约定与踩坑**：`code/CLAUDE.md`、`code/yongxin_WMS/CLAUDE.md`、
  `code/yongxin_WMS/jeecg-boot/CLAUDE.md`、`code/yongxin_WMS/jeecgboot-vue3/CLAUDE.md`。

## 恢复工作区

```bash
git clone -b main https://github.com/283523376/hq.git OTC-260906-yongxin_WMS
cd OTC-260906-yongxin_WMS

# 代码若还要继续开发，给产品仓补上远端
git -C code/yongxin_WMS remote set-url origin git@github.com:Guangdong-One-Top/yongxin-WMS.git
```

客户原始资料（`资料/`、`软件蓝图/`）不在本仓库，需从本地或其他渠道另存恢复。

## 独立镜像分支（可选）

远端另外保留了几条独立分支，用于单独 clone 代码仓或找回某个开发分支：

| 分支 | 内容 |
| --- | --- |
| `code/yongxin-wms/main` | 主代码仓 |
| `code/yongxin-wms/feat/wms-kingdee-business-sync` | 金蝶工单同步开发分支 |
| `code/yongxin-wms/283523376` | 历史开发分支 |
| `code/wms-logicflow-designer/master` | 设计器 |

## 不在此仓库的内容

| 路径 | 原因 |
| --- | --- |
| `资料/` | 客户方案 PPTX、堆垛机/米塔厂商资料等第三方材料 |
| `软件蓝图/` | 含兆鑫、格子柜等其他客户的蓝图版本 |
| `code/tmp_files/` | 调试残留、日志、数据库快照；`.tmp_env/kingdee.env` 含明文凭据 |
| `code/proofshot-artifacts/` | 验证工具产生的截图与录像 |

## 更新备份

```bash
# 1) 工作区文档 / 蓝图
git add -A
git commit -m "backup: $(date +%F)"
git push

# 2) 代码子树（先在产品仓提交，再拉进备份仓）
git subtree pull --prefix=code/yongxin_WMS \
  git@github.com:Guangdong-One-Top/yongxin-WMS.git main -m "backup: 同步主代码仓"
git subtree pull --prefix=code/wms-logicflow-designer \
  <设计器远端> master -m "backup: 同步设计器"
git push

# 3) 本地根仓快进（会把子树内容就地写回 code/ 下）
git fetch origin
git merge --ff-only origin/main

# 4) 快进后修复两个活仓库的换行差异（必做）
#    根仓 .gitattributes 是 `* -text`，子树快照按 LF 写回工作区；
#    而代码仓 core.autocrlf=true 期望 CRLF，快进后会显示几千个「已修改」。
git -C code/yongxin_WMS checkout -- .
git -C code/wms-logicflow-designer checkout -- .
# 自检：只剩换行差异时，忽略 CR 后的差异文件数应为 0
git -C code/yongxin_WMS diff --ignore-cr-at-eol --name-only | wc -l
```

> 注意：`code/yongxin_WMS/`、`code/wms-logicflow-designer/` 在 `.gitignore` 里保留了忽略规则，
> 只是防止根仓 `git add -A` 误收它们工作区里的未跟踪文件；已跟踪文件不受影响。
> 代码内容请用上面的 `subtree pull` 更新，不要在根仓直接提交。
