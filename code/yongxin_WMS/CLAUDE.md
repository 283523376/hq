# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> 语言：默认用简体中文回答，除非用户明确要求其他语言。

## 这个仓库是什么

永鑫WMS 的运行平台 = JeecgBoot 3.9.5（Spring Boot 4.1.0 / Java 17 + Vue 3 / Vite 6）。
**单一 git 仓库**：后端 `jeecg-boot/`、前端 `jeecgboot-vue3/` 共用一条历史，统一从仓库根提交
（`feat(wms): …`），不要在 `jeecgboot-vue3/` 里单独提交。

文档优先级（冲突时上面的赢）：

| 文档 | 覆盖范围 |
| --- | --- |
| `../CLAUDE.md`（`code/CLAUDE.md`） | 工作区级：三个子项目关系、蓝图流水线、本机端口/凭据等机器事实 |
| 本文件 | 在**本仓库内**干活的命令、目录地图、静默失败清单 |
| `jeecg-boot/CLAUDE.md` | 后端：模块图、技术栈、MyBatis-Plus/Shiro/AutoPoi 约定、删除守卫 |
| `jeecgboot-vue3/CLAUDE.md` | 前端：启动序列、pinia、`defHttp`、组件注册 |

⚠️ 两份子项目 CLAUDE.md 保留了上游原文，其中 **`mvn …` 裸命令与端口 8080 在本机不成立**（见下节）。

## 命令（本机实测，2026-11-10）

`mvn` 不在 PATH，也没有 `mvnw`。唯一可用的包装器是
`D:/OTC-260906-yongxin_WMS/code/tmp_files/.tmp_env/m.sh`（内部已设 JAVA_HOME=OpenJDK 17 +
Maven 3.9.9；`/d/tools/m.sh` 已不存在，别照旧笔记写）。

```bash
M=/d/OTC-260906-yongxin_WMS/code/tmp_files/.tmp_env/m.sh

# 全量构建：-P dev 必须带（根 pom 把 dev 和 SpringCloud 都设成 activeByDefault，
# 裸跑会把 cloud 模块拖进反应堆并让 @profile.name@ 解析失败）
cd jeecg-boot && bash $M -P dev clean package                 # 默认跳过测试
bash $M -P dev clean package -DskipTests=false                # 带测试

# 单个测试类（-am 时必须补 failIfNoSpecifiedTests=false，
# 否则上游模块没有匹配测试会直接断）
bash $M -P dev test -DskipTests=false -pl jeecg-boot-module/jeecg-boot-module-wms \
     -Dtest=<TestClassName> -am -Dsurefire.failIfNoSpecifiedTests=false

# 改了 wms 模块后（两步都做，缺一步就是「页面正常、接口 404」）
bash $M -P dev -pl jeecg-boot-module/jeecg-boot-module-wms install -DskipTests
# 然后必须重启 JVM。带金蝶凭据的一键启动：
bash /d/OTC-260906-yongxin_WMS/code/tmp_files/.tmp_env/start-backend.sh   # 8081
```

前端（`jeecgboot-vue3/`，pnpm；Node 24 实测可用）：

```bash
pnpm dev                 # 3100；实际代理看 .env.development.local → localhost:8081/jeecg-boot
pnpm build               # 产物 dist/
npx eslint src/views/wms/xxx/index.vue
pnpm batch:prettier
# 没有统一 lint / test 脚本；Jest 有配置但未接入 package.json
```

体检脚本（PowerShell/Git Bash 均可，Python 3）：

```bash
python scripts/wms-perms-check.py          # 权限点三方对齐，退出码 1 = 有缺口
python scripts/wms-status-options-check.py # 后端状态常量 ↔ 前端下拉
python scripts/wms-demo-data-check.py      # 演示数据（查库 + 调接口）
python scripts/kingdee-formid-probe.py --configured   # 金蝶登录/表单可用性
```

## 目录地图（WMS 相关）

```
jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms/      # 后端，包 org.jeecg.modules.wms
  controller/ service/ service/impl/ mapper/ mapper/xml/ entity/ dto/ vo/
  constant/    # WmsConstants / WmsConfigKey / WmsKingdeeSyncScope：常量唯一事实源
  sync/        # 金蝶；audit/ 审计；job/ 定时（XxxJob + XxxJobRegistrar 成对）
  exporter/ exception/ util/
  src/main/java/.../mapper/xml/   # 注意 XML 在 java 目录下，不在 resources
  src/test/java/                  # 单测集中于此（约 174 个测试类）
jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/
  V3.9.5_*.sql                    # WMS 迁移脚本全在这里（约 77 个），Flyway 失败不阻断启动
jeecgboot-vue3/src/views/wms/     # 按业务域分目录：audit common customer defect device
                                  # inbound inventory inventoryLedger material materialGroupMap
                                  # order outbound print production stocktake supplier sync
                                  # systemConfig transfer warehouse warning workOrder
docs/superpowers/specs/           # YYYY-MM-DD-yx-wms-<主题>-design.md
docs/superpowers/plans/           # 计划与 *-验收报告.md，需求号 YX-WMS-* 一一对应
docs/wms/                         # 面向使用者的说明：主数据同步、外协/样品出库、盘点
.superpowers/sdd/<日期>-<主题>/    # 逐特性的过程产物（非事实源）
scripts/                          # 体检脚本，见上
```

## WMS 代码的三条线 + 扩展点

改一条线要同时看另外两条：

1. **业务单据线**：`controller → service.impl → entity → wms_inventory`。库存只由 `wms_inventory`
   记账；状态/类型字符串是**中文常量**，集中在 `constant/WmsConstants`；扣减必须用带条件的更新
   （如 `WmsInventoryMapper.deductQuantity` 的 `quantity >= qty`）。
2. **ERP 同步线**（`sync/`）：`IWmsKingdee*Writer`（WMS→金蝶回写）/ `IWmsKingdee*Reader`（金蝶→WMS 读取），
   底层统一 `WmsKingdeeFormClient`；`IWms*SyncGateway` 是外部系统网关（未就绪可 mock）。
   新增回写 = Writer 接口 + Impl + 到 `WmsKingdeeSyncScope` 登记。
3. **定时/重试线**（`job/`）：`XxxJob` + `XxxJobRegistrar` 成对；Registrar 在 `ApplicationReadyEvent`
   注册并走 `WmsJobRegistrarSupport.isScheduled`。**只加 Job 不加 Registrar = 没接上。**

横切三条：

- **配置项**：加 `WmsConfigKey` 枚举值即可，前端渲染与后端校验都读它（不改表结构）。
- **审计**：所有写端点必须 `@WmsAudit`（XT-002 口径）。
- **权限**：新增 `@RequiresPermissions` 时同一迁移里授 `admin` + `wms_admin` + `wms_manager`
  （device/warehouse/systemConfig 类只授 admin），授权行 id 用确定性拼接保证幂等，
  跑 `scripts/wms-perms-check.py` 验收。

## 静默失败清单（不报错、只产出错东西）

这些是本项目最贵的知识——现象都「看起来一切正常」：

| 现象 | 真因 | 动作 |
| --- | --- | --- |
| 页面/菜单正常，wms 接口 404「路径不存在」 | 起了新代码但 jar 未 install 或 JVM 未重启 | install 模块 + 重启；未登录访问同路径返回 401 可确认是「端点未注册」 |
| Flyway 脚本失败但服务照常启动 | `FlywayConfig` 捕获 `FlywayException` 只打一行 ERROR | grep 启动日志「flyway执行sql脚本失败」，核对 `flyway_schema_history.success=1` |
| 删/改脚本后报 `Found more than one migration with version` | `spring-boot:run` 读的是 `target/classes/flyway/` 旧副本 | `bash $M -P dev clean process-resources -pl jeecg-module-system/jeecg-system-start` |
| 定时任务永远不触发 | 有 JobDetail 无 trigger，注册器还会打印「已存在，跳过注册」 | 走 `WmsJobRegistrarSupport.isScheduled`（它会清理残留） |
| 金蝶同步完全没动静 | 启动前没加载 `kingdee.env`，全部按「未配置」静默跳过 | `set -a && . …/kingdee.env && set +a` 后再启动 |

## 文档工作流

**先 spec → 再 plan → 完工写验收报告**，命名 `YYYY-MM-DD-yx-wms-<主题>[-design|-验收报告].md`，
放 `docs/superpowers/specs|plans/`。动某个业务域前先读对应文档；需求条目 `YX-WMS-*` 在
`wms-blueprint/flows.json`（V2.6，18 条流程）与这里的设计/验收文档之间一一对应。

数据库：MySQL 8，库 `yongxin_wms`；CLI 不在 PATH，用绝对路径
`"/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe" -uroot -proot yongxin_wms -e "…"`。
