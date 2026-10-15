# YX-WMS-BD-003 库区/库位与设备绑定 — 设计说明

- 日期：2026-10-15
- 蓝图流程：`YX-WMS-BD-003` 库区/库位与设备绑定流程（基础数据类）
- 落点模块：`jeecg-boot/jeecg-boot-module/jeecg-boot-module-wms`
- 状态：待评审

---

## 1. 目标

在永鑫 WMS 运行时中，让管理员能够：

1. 维护库区（立库 / 敏捷仓 / 线边柜），含编码唯一性校验；
2. 按库区维度**分范围批量生成库位**，库位编码全局唯一且可反解摆放位置；
3. 把设备（堆垛机 / SCR / 分拣单元 / 充电桩）归属到库区；
4. 维护库位属性（承重、状态），且**库位内有物料时拒绝修改**；
5. 设备离线时产生告警（绑定校验 + 状态上报 + 定时扫描三条链路）。

## 2. 需求来源与蓝图七步映射

权威流程取自 `wms-blueprint/flows.json` 的 `YX-WMS-BD-003`（7 个步骤 + 3 条异常），泳道为
`管理员 / WMS系统 / WCS/RMS / 异常处理`。

| 蓝图步骤 | 泳道内容 | 本设计落点 |
| --- | --- | --- |
| 1 | 登录，进入仓库管理 | 菜单「库区与库位」「设备管理」+ 权限 |
| 2 | 选择库区/库位管理 → 展示库区列表 | `GET /wms/warehouse/treeList` + 左树右表页面 |
| 3 | 新增库区（立库/敏捷仓/线边柜）→ **校验库区编码唯一性** → 异常：编码重复→提示 | `add/edit` + `GET /checkCode`（§5.1） |
| 4 | **按规则生成库位编码** → 批量生成库位 → 异常：生成失败→提示 | `POST /wms/location/generate`（§5.2） |
| 5 | 绑定设备（堆垛机/SCR/分拣单元）→ 保存绑定关系 → 下发设备注册信息 → 异常：**设备离线→告警** | `POST /wms/device/bind` + 告警（§5.4、§6.3） |
| 6 | 设置库位属性（承重/状态）→ 更新库位属性 | `POST /wms/location/updateAttr`（§5.3） |
| 7 | 保存并生效 → 记录操作日志 → 异常：保存失败→回滚 | §5.5（**工程化偏离，见 §5.5.1**） |

蓝图 3 条异常照单实现：

| 蓝图异常 | 落点 |
| --- | --- |
| 库位编码重复：提示并拒绝保存 | DB 唯一索引 + `checkCode` + 生成时 `INSERT IGNORE` 跳过 |
| 设备绑定失败：保存设备绑定状态，给出告警 | 绑定**照常成功**，离线设备聚合告警不阻断（§5.4） |
| 库位有物料时，不允许修改库位属性 | `status == 占用` → 拒绝（§5.3） |

### 2.1 与需求方的六项确认结论（本设计的输入前提）

| # | 议题 | 结论 |
| --- | --- | --- |
| 1 | 库位编码规则 | **库区码-排-列-层**，形如 `LB-R01-C06-L24` |
| 2 | 生成规模落地方式 | **分批同步**，每批 1000 条，单次请求上限 50000 条；**不做异步**（异步方案已评估并主动退回） |
| 3 | 设备绑定粒度 | **设备归属库区**（`wms_device.warehouse_id`），不建关联表 |
| 4 | 「库位内有物料」判定 | **库位状态即判据**：`占用` 视为有料 |
| 5 | 敏捷仓网格维度 | **库区上存默认维度**（`row_count`/`col_count`/`layer_count`） |
| 6 | 离线告警触发时机 | **三处**：绑定时校验 + 状态上报接口 + 定时扫描任务 |

## 3. 范围

### 3.1 本期实现

- `wms_warehouse` / `wms_location` / `wms_device` 三张表（Flyway）
- 库区 CRUD + 库区树 + 编码唯一性校验
- 库位**按范围批量生成**（幂等）+ 分页查询 + 属性更新 + 释放 + 删除守卫
- 设备 CRUD + 归属库区绑定/解绑 + 状态上报
- 设备离线告警三链路 + 离线扫描定时任务（含启动自注册器）
- 前端两个菜单页：库区与库位（左树右表）、设备管理
- 菜单与按钮权限

### 3.2 本期明确不做（YAGNI，需要时另行提出）

| 不做项 | 原因 |
| --- | --- |
| 异步生成任务 + 任务表 + 进度查询 | 需求方主动退回（结论 #2），`wms_location_gen_task` 不建 |
| 库位↔设备关联表 | 绑定粒度为库区（结论 #3） |
| `wms_stock` 库存表 | 库存归属 RK/CK/PD 流程，在此建表会与其归属冲突 |
| Excel 导入 / 导出设备与库区 | 需求未提出；库位导出可后补 |
| 「下发设备注册信息」的真实 WCS/RMS 调用 | WCS/RMS 接入属 `YX-WMS-SB-002`，当前不存在 |
| 格子柜（线边柜）格子级建模 | 线边柜侧最小单元是「格子」，属另一套模型；本期线边柜库区只建档 |

## 4. 数据模型

### 4.1 `wms_warehouse` 库区

| 列 | 类型 | 约束/说明 |
| --- | --- | --- |
| `id` | varchar(32) | 主键，`IdType.ASSIGN_ID` |
| `warehouse_code` | varchar(64) | NOT NULL，唯一索引 `uk_wms_warehouse_code` |
| `warehouse_name` | varchar(100) | NOT NULL，索引 `idx_wms_warehouse_name` |
| `warehouse_type` | varchar(20) | NOT NULL，立库 / 敏捷仓 / 线边柜（中文字面量） |
| `row_count` | int | **容量包络**：排数上限 |
| `col_count` | int | **容量包络**：列数上限 |
| `layer_count` | int | **容量包络**：层数上限 |
| `status` | varchar(1) | NOT NULL DEFAULT '1'，1启用 0停用 |
| `deleted_at` + 审计四列 | | `JeecgEntity` 标准；删除标记用**删除时间戳**（`deleted_at`，0 = 未删除），见 §4.4 |

> **`row_count`/`col_count`/`layer_count` 的语义是「生成上限」，不是「必有这么多库位」，也不参与编码计算。**
> 需求方明确两点：**料箱不一定堆满仓库**；**所有维度可自由增减**。
> 故：库位生成只按操作员选定的范围，仓库内允许网格空洞、允许不连续；
> 维度随时可改（无守卫），改完已有库位编码不受任何影响（§5.2）。

### 4.2 `wms_location` 库位

| 列 | 类型 | 约束/说明 |
| --- | --- | --- |
| `id` | varchar(32) | 主键 |
| `location_code` | varchar(64) | NOT NULL，**唯一索引 `uk_wms_location_code`**（约束①） |
| `warehouse_id` | varchar(32) | NOT NULL，指向 `wms_warehouse.id`，索引 `idx_wms_location_warehouse` |
| `row_no` | int | NOT NULL，排 |
| `col_no` | int | NOT NULL，列 |
| `layer_no` | int | NOT NULL，层 |
| `max_weight` | decimal(12,3) | 承重（kg） |
| `status` | varchar(10) | NOT NULL DEFAULT '空闲'，**空闲 / 占用 / 故障** |
| `deleted_at` + 审计四列 | | 同 4.1（删除时间戳，见 §4.4） |

**唯一索引 `uk_wms_location_grid (warehouse_id, row_no, col_no, layer_no)`** —— 这不是装饰。
单次生成最多 5 万条、敏捷仓目标 60 万级需要多次请求，靠该索引 + `INSERT IGNORE` 才能让
**重跑同一范围是安全的**（幂等续做，见 §5.5.1）。

### 4.3 `wms_device` 设备

| 列 | 类型 | 约束/说明 |
| --- | --- | --- |
| `id` | varchar(32) | 主键 |
| `device_code` | varchar(64) | NOT NULL，唯一索引 `uk_wms_device_code` |
| `device_name` | varchar(100) | NOT NULL |
| `device_type` | varchar(20) | NOT NULL，堆垛机 / SCR / 分拣单元 / 充电桩 |
| `warehouse_id` | varchar(32) | **归属库区**（结论 #3），NULL = 未绑定，索引 `idx_wms_device_warehouse` |
| `status` | varchar(10) | NOT NULL DEFAULT '离线'，在线 / 离线 / 故障 |
| `last_heartbeat` | datetime | 最近一次在线心跳（或显式置在线）时刻；**判定离线的唯一依据** |
| `last_alert_time` | datetime | 最近一次离线告警时刻，用于**告警节流**（§6.3） |
| `deleted_at` + 审计四列 | | 同 4.1（删除时间戳，见 §4.4） |

复合索引 `idx_wms_device_type_status(device_type, status)` 支撑「按类型 / 按在线状态筛选」。

> **`status` 默认 `离线` 而非 `在线`**：新建设备在没收到任何心跳前不应被当成在线设备投用。
> 与 `last_heartbeat` 配合时需注意 —— 从未上报心跳的设备**不参与离线扫描**（§6.4），
> 否则每台新建设备都会在 10 分钟后误报离线。

`warehouse_type` / `warehouse_type` / `status` / `device_type` 均采用**中文字面量**存储，
与既有 `wms_material_category.category_type`（半成品/成品/辅料）、`wms_order.order_type`（公司订单/采购订单/委外订单）
一致，避免引入数据字典这一额外配置依赖。

### 4.4 删除语义：删除时间戳（实施后按需求方指定方案改定）

三张表的删除标记是 **`deleted_at`（BIGINT，epoch 毫秒；0 = 未删除，否则为删除时刻）**，
由 `@TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW(3)) * 1000")` 驱动：
查询自动追加 `deleted_at = 0`，删除则写当前时间戳。

**唯一索引必须把 `deleted_at` 一起纳入**：

| 索引 | 列 |
| --- | --- |
| `uk_wms_warehouse_code` | `(warehouse_code, deleted_at)` |
| `uk_wms_location_code` | `(location_code, deleted_at)` |
| `uk_wms_location_grid` | `(warehouse_id, row_no, col_no, layer_no, deleted_at)` |
| `uk_wms_device_code` | `(device_code, deleted_at)` |

**为什么不是 0/1 的 `del_flag`**：唯一索引若不纳入删除标记，被删除的编码会**永久占用**它
（表现为「查重说可用 → 保存撞唯一索引 → 用户看到原始 SQL 异常」）。
而若纳入的删标记是**固定值**，同一编码就**只能删一次**（第二次删除的行与第一次删标记相同，仍撞索引）。
用**时间戳**则每次删除值都不同，于是「删除 → 重建 → 再删除」可无限次进行，
同时保留逻辑删除的**可恢复/可审计**特性。

对 `wms_location` 的额外意义：网格唯一索引纳入 `deleted_at` 后，
「删掉几个库位 → 重跑批量生成」会**新建**行，而不是被 `INSERT IGNORE` 静默跳过
（计入 `skipped` 却不恢复，且列表里看不见）—— 删除语义与批量生成的**幂等续做**语义不再互相干扰。

> **实施轨迹（保留决策痕迹）**：本规格最初按全模块惯例设计为 0/1 的 `del_flag`；
> 实施时因唯一索引冲突改为「物理删除」（`V3.9.5_6`）；同日按需求方指定方案最终改为
> **删除时间戳**（`V3.9.5_9`），并同步推广到 4 张既有表（`V3.9.5_8`），
> 使全模块只有一个删除语义。详见
> `docs/superpowers/specs/2026-10-15-yx-wms-hard-delete-existing-tables-design.md`。

## 5. 核心业务规则

### 5.1 库区编码唯一性

照抄 `WmsMaterialCategoryServiceImpl.checkCodeExists(code, id)`：`eq(code)` + `id` 非空时 `ne(id)`。
前端 `dynamicRules` 异步查重（`isTransformResponse: false` 读原始报文），后端 `addCategory`/`editCategory`
同款二次强制。

### 5.2 库位编码生成规则（结论 #1，验收标准①的核心）

```
段宽 = max(2, 该库位自身该段数字的位数)
location_code = {warehouse_code}-R{排,段宽}-C{列,段宽}-L{层,段宽}
```

**关键点：段宽只由「该库位自身的数字」决定，与库区维度上限无关。**

这条约束推翻了本规格初稿的一个错误设计。初稿把段宽定义为
`max(2, 库区该维度上限的位数)`，于是「库区维度」被绑进了编码；
为了保住编码稳定，初稿不得不追加一堆守卫去禁止维度缩小、禁止跨段宽扩容。
**需求方确认：所有维度必须可自由调节（可大也可小）。** 因此正确的做法是
**把维度从编码规则里彻底移除**，而不是给维度加锁。

- 同一个 `(row_no, col_no, layer_no)` **永远算出同一个编码**，与库区维度怎么配、请求范围开多大均无关；
- 补零采用 `%0Nd` 语义：**只补不截**。值 6 → `06`，值 106 → `106`。该映射是单射，
  故不同坐标必得不同编码，**唯一性不依赖任何外部配置**；
- 幂等性随之免费获得：重跑同一范围算出的编码与首次完全一致，`INSERT IGNORE` 必然命中跳过。

| 库区 | code | 库位自身坐标 | 编码示例 |
| --- | --- | --- | --- |
| 半成品立库 | `LB` | 排 1 / 列 6 / 层 24 | `LB-R01-C06-L24` |
| 　　同库区低层 | `LB` | 排 1 / 列 6 / 层 1 | `LB-R01-C06-L01` |
| 成品敏捷仓 | `MJ` | 排 1 / 列 60422 / 层 10 | `MJ-R01-C60422-L10` |

立库 1 排 × 6 列 × 24 层 = 144 个库位，首末编码 `LB-R01-C01-L01` / `LB-R01-C06-L24`，
与需求方确认的样例**逐字符一致** —— 新规则没有改变任何已确认的编码结果，
只是不再让它们依赖可变的库区配置。

> **不出现任何除法。** 蓝图里「604218 料箱」是物理描述，不是系统要校验的目标：
> 层数由库区配置决定而非固定为 10，料箱也不一定堆满仓库。
> 系统只按运维填的 `排 × 列 × 层` **如实生成并回报实际条数**，不对账 604218。

**库区维度的角色被收窄为「生成上限」**

`row_count` / `col_count` / `layer_count` 此后**只有一个用途**：限制批量生成能开到哪。
它**不再参与编码计算、不再约束任何已有数据**。因此：

| 操作 | 允许？ | 说明 |
| --- | --- | --- |
| 任意时刻**扩大**任一维度 | ✅ | 随时，无守卫 |
| 任意时刻**缩小**任一维度 | ✅ | 随时，无守卫。**已有库位编码完全不变** |
| 维度设为空 / 0 | ✅ 保存，但**不允许生成库位**（§7.2 守卫 2） | 用于线边柜这类不适用网格的库区 |

**缩维的唯一后果（非阻断，但要看得见）**：已存在的库位可能落到新包络之外
（如层数由 24 缩到 10，则 11~24 层的库位仍在库里、仍在用，只是「超出了当前声明的上限」）。
处理方式**不做拦截**，改为**可见**：库区树 / 库区列表上对这类库区显示提示
「有 N 个库位位于当前维度之外」，让运维知道自己的声明与现状不一致，而不是系统替他拦住或悄悄掩盖。

> 为什么不拦截：维度是**声明**，库位是**已存在的资产**。运维把声明调小（例如只是临时收窄生成范围）
> 不该被判为非法操作，更不该因为拦不住而留下半截状态。把不一致**显示出来**，
> 由人决定是补回维度还是删库位，比替人做决定更安全。

### 5.3 库位属性更新与「有物料」守卫（约束②）

`POST /wms/location/updateAttr { id, maxWeight, status }`

```
status(当前) == 占用  →  拒绝：
  "库位内有物料，不允许修改属性"
```

**附加：`POST /wms/location/release { id }`（占用 → 空闲）**

> **这是对原规格的一处增补，请评审确认。** 理由：若 `占用` 既代表"有物料"、又阻断一切状态修改，
> 那么一个被误置为 `占用` 的库位（或出库后无人回写的库位）**永远无法人工恢复**——
> 约束②会变成一个锁死缺陷，且验收演示时会卡住。
> 故单开一个语义明确的「释放」动作：仅 `占用 → 空闲`，独立权限 `wms:location:release`，
> 语义是"人工确认该库位已清空"。它**不是**绕过约束的通用后门（改承重仍被拒）。

删除守卫：

| 对象 | 允许删除的前置 | 否则报错 |
| --- | --- | --- |
| 库位 | `空闲` | 「库位已占用或有故障，不允许删除」 |
| 库区 | 无库位 **且** 无绑定设备 | 「该库区下存在库位/已绑定设备，不允许删除」 |

库区停用后：不允许向其生成库位、不允许向其绑定设备（报错而非静默）。

### 5.4 设备绑定与离线告警（结论 #3 #6，约束③）

`POST /wms/device/bind { warehouseId, deviceIds: [] }`

1. 校验库区存在且**启用**；
2. 逐个设备 `warehouse_id = warehouseId`；
3. 若某设备原本已归属**另一个**库区，本次为搬迁 —— 允许，但在返回体中列出 `movedDevices`并 `log.warn`，不静默；
4. **绑定成功后**，统计本次涉及设备中 `status == 离线` 的，**聚合成一条**站内消息告警
   （不是 N 台设备发 N 条）；
5. 返回 `WmsDeviceBindResultVO { bound, movedDevices, offlineDevices, warning }`：
   `bound` = 成功绑定的设备数；`movedDevices` / `offlineDevices` = 设备编码列表；
   `warning` = 前端提示语（`offlineDevices` 非空时给出，否则为 null）。

**语义要点（对齐蓝图「设备绑定失败：保存设备绑定状态，给出告警」）**：
**绑定本身成功**，告警是并行的副作用，不阻断、不回滚。前端依据 `warning` 非空弹 `useMessage().warning`。

### 5.5 保存失败的处理

#### 5.5.1 批量生成不做整体回滚（**对蓝图第 7 步的工程化偏离，已确认接受**）

蓝图第 7 步写「保存失败 → 回滚」。本设计**刻意不这样做**：

- 单次最多 5 万行，开一个大事务会把 Druid 连接池拖死、且 undo log 巨大；
- 改为：**某批失败即立即停止**，返回 `Result.error`，文案含**已生成条数与失败原因**，
  并 `log.error`；已落库的批次**保留**。

**为什么这是安全的**：第 4.2 节的唯一索引 + `INSERT IGNORE` 使同一范围重跑**幂等** ——
已存在的库位被跳过（计入 `skipped`），未生成的被补上。恢复成本是"再点一次"，
而不是"人工清理半截数据"。这与 BD-002 里「不下发长事务、靠认领机制自愈」是同一取舍口径。

报错文案形如：
「已生成 12000 个后失败：<原因>。重跑同一范围可安全续做（已生成的会自动跳过）。」

## 6. 后端结构

全部落在 `org.jeecg.modules.wms`，逐项对齐既有 `WmsMaterial` / `WmsSupplier` 范式。

```
entity/     WmsWarehouse.java  WmsLocation.java  WmsDevice.java
dto/        WmsLocationGenDTO.java      （warehouseId + 六个范围字段）
            WmsLocationAttrDTO.java     （id, maxWeight, status）
            WmsDeviceBindDTO.java       （warehouseId, deviceIds[]）
            WmsDeviceStatusDTO.java     （deviceCode, status）
vo/         WmsLocationGenResultVO.java （generated, skipped）
            WmsDeviceBindResultVO.java  （bound, movedDevices, offlineDevices, warning）
mapper/     WmsWarehouseMapper.java  WmsLocationMapper.java  WmsDeviceMapper.java
            xml/WmsLocationMapper.xml   （insertIgnoreBatch，唯一一条自定义 SQL）
service/    IWmsWarehouseService.java   impl/WmsWarehouseServiceImpl.java
            IWmsLocationService.java    impl/WmsLocationServiceImpl.java
            IWmsDeviceService.java      impl/WmsDeviceServiceImpl.java
            IWmsDeviceAlertService.java impl/WmsDeviceAlertServiceImpl.java  ← 告警投递（§6.3）
job/        WmsDeviceOfflineScanJob.java
            WmsDeviceOfflineScanJobRegistrar.java
controller/ WmsWarehouseController.java  WmsLocationController.java  WmsDeviceController.java
constant/   WmsConstants.java              （追加库区/库位/设备/生成/扫描常量）
```

### 6.1 批量生成的分层边界

| 层 | 职责 | 不知道什么 |
| --- | --- | --- |
| `IWmsLocationService.generate(dto)` | 守卫校验、算编码、分批、汇总 `generated`/`skipped` | 不知道 SQL 怎么写的 |
| `WmsLocationMapper.insertIgnoreBatch(list)` | 一批 INSERT IGNORE，返回**实际插入行数** | 不知道批次大小、上限、编码规则 |

`skipped = 请求总数 − 各批 affected rows 之和`。这样 `skipped` 是**数据库告诉我们的真值**，
不是服务端自己猜的。

### 6.2 `insertIgnoreBatch`（`WmsLocationMapper.xml`）

```xml
<insert id="insertIgnoreBatch">
  INSERT IGNORE INTO wms_location
    (id, location_code, warehouse_id, row_no, col_no, layer_no,
     max_weight, status, deleted_at, create_by, create_time, update_by, update_time)
  VALUES
  <foreach collection="list" item="it" separator=",">
    (#{it.id}, #{it.locationCode}, #{it.warehouseId}, #{it.rowNo}, #{it.colNo}, #{it.layerNo},
     #{it.maxWeight}, #{it.status}, 0, #{it.createBy}, #{it.createTime}, #{it.updateBy}, #{it.updateTime})
  </foreach>
</insert>
```

返回值为 `int`（受影响行数）。**注意**：`INSERT IGNORE` 也会吞掉其它可忽略错误
（如字段超长），故生成前对编码长度做一次校验（`location_code` 上限 64）。

XML 位置：`src/main/java/org/jeecg/modules/wms/mapper/xml/`（与模块 `package-info.java` 说明一致，
`mapper-locations` 已在全部 profile 配置，**无需改 yml**）。

### 6.3 设备告警投递（`IWmsDeviceAlertService`）

复用 BD-002 已验证的链路，**逐条继承其踩过的坑**：

- `ISysBaseAPI.sendSysAnnouncement(MessageDTO)`；
- `fromUser = WmsConstants.ALERT_FROM_USER`（`"system"`）；
- **`toUser` 不可为 null** —— 平台 `SysBaseApiImpl` 会对它 `split(",")`，null 会抛 NPE
  并被本方法的 try/catch 吞掉，表现为「公告插入成功但收件人零条」，管理员实际收不到任何告警。
  默认 `WmsConstants.ALERT_TO_USER`（`"admin"`），可用 `wms.device.alert.to-user` 覆盖；
- 整段用 **try/catch 包住**，告警设施自己的毛病不允许掩盖设备状态这件事；
- **告警节流**：同一设备在 `WmsConstants.DEVICE_ALERT_THROTTLE_MINUTES`（30 分钟）内
  **不重复投递**；节流依据 `wms_device.last_alert_time`。

节流读写在 `WmsDeviceAlertServiceImpl` 内，调用方（绑定 / 上报 / 扫描）不感知。

### 6.4 离线判定与三条触发链路

**离线判定的唯一依据是 `last_heartbeat`**（不是 `status`）：

| 链路 | 触发 | 行为 |
| --- | --- | --- |
| ① 绑定 `POST /bind` | 管理员操作 | 绑定成功；对 `status == 离线` 的设备**聚合告警一条**（不受节流约束——这是人的即时操作，就要即时反馈） |
| ② 上报 `POST /reportStatus` | WCS/RMS 回传 | 更新 `status` + `last_heartbeat = now`；转 `离线`/`故障` → 按节流告警；**恢复 `在线` → 清空 `last_alert_time`**（使后续再次离线能立即告警） |
| ③ 扫描 `WmsDeviceOfflineScanJob` | 每 5 分钟 | 见下 |

**置在线的任何路径（`add`/`edit` 显式选在线、`reportStatus` 上报在线）都必须同时
`last_heartbeat = now`** —— 否则扫描会在下一轮把刚建的在线设备打成离线。

**扫描逻辑**：

```
候选 = status != '离线'
       AND last_heartbeat IS NOT NULL          ← 关键：从不上报心跳的设备不参与
       AND last_heartbeat < now - DEVICE_OFFLINE_THRESHOLD_SECONDS(600)
       按 last_heartbeat 升序，每轮最多 DEVICE_SCAN_BATCH = 200 条
```

1. **`last_heartbeat IS NULL` 的设备一律跳过**。它们从未上线，判离线是误报；
   且新建设备若被扫成离线，验收演示时会瞬间出现一屏假告警，掩盖真实告警。
2. 逐条置 `status = 离线`，`log.warn` 记录设备编码与静默时长；
3. 本轮转离线的设备**聚合为一条**站内消息；
4. 单条异常 try/catch 包住、`log.error` 后继续，不中断整轮；
5. 每轮 `log.info` 汇总：候选数 / 转离线数。

**天然无需节流**：`WHERE status != '离线'` 使扫描只捕捉**跃迁**（在线/故障 → 离线），
同一台设备不会被反复投递。节流只服务于链路①（管理员反复点绑定）与②（WCS 反复上报离线）。

**cron**：`0 7/5 * * * ? *`（每 5 分钟，落在 :07 / :12 / …）。
刻意与既有订单补偿任务（`0 3/5 * * * ? *`，落在 :03/:08/…）错开 4 分钟，避免两轮任务同时打库。

### 6.5 定时任务启动自注册（**继承 BD-002 的关键发现**）

**已知事实**（BD-002 已核实并留下记录）：`sys_quartz_job` 表只是平台的**注册表元数据**，
全库**没有任何启动加载器**，任务仅经「定时任务管理」UI 的 `saveAndScheduleJob` 写入调度器。
**故仅靠 Flyway 插一行，任务在全新环境不会执行。**

因此 `WmsDeviceOfflineScanJobRegistrar` **照 `WmsOrderSyncJobRegistrar` 同构实现**：

| 组成 | 作用 |
| --- | --- |
| `WmsDeviceOfflineScanJob implements org.quartz.Job` | 扫描与告警。加 `@DisallowConcurrentExecution` 防自我重叠 |
| `WmsDeviceOfflineScanJobRegistrar` | `@EventListener(ApplicationReadyEvent.class)`，幂等自注册；**关闭开关时主动 `deleteJob` 摘除**（本部署是 JDBC 作业存储 + `isClustered:true`，trigger 持久化在 `QRTZ_*` 表，只 return 是停不掉的） |
| Flyway 的 `sys_quartz_job` 种子行 | 让任务在 UI 可见，可用**立即执行**按钮手工触发（验收演示用） |

约定对齐（与 `QuartzJobServiceImpl.schedulerAdd` 一致）：

- `JobDetail` / `CronTrigger` 均用 `.withIdentity(DEVICE_SCAN_JOB_ID)`、**默认组**；
- `id` 即 `sys_quartz_job.id`，**常量与种子行必须同值**：`1920000000000000102`
  （`101` 已被订单补偿任务占用）；
- 类名须以 `org.jeecg.` 开头（`QuartzJobServiceImpl.getClass()` 有包白名单防 RCE）——
  `org.jeecg.modules.wms.job.WmsDeviceOfflineScanJob` 合规；
- 启停以配置 `wms.device.job.enabled`（默认 `true`）为准，**不读 `sys_quartz_job.status`**
  （读它需 wms 反向依赖 `system-biz`，越界）；
- 注册失败用 try/catch + WARN 包住，**不允许调度器的毛病阻断应用启动**。

> **已知重复**：此处与 `WmsOrderSyncJobRegistrar` 是两份同构代码。**刻意不抽象**——
> 抽象需改动一个已上线并已验收的类，收益仅是省 30 行，风险大于收益。
> 若出现第三个任务，再抽公共基类。

## 7. 接口清单

### 7.1 `WmsWarehouseController` `@RequestMapping("/wms/warehouse")`

| 方法 | 路径 | 入参 | 说明 |
| --- | --- | --- | --- |
| GET | `/treeList` | — | **两层树**：库区类型 → 库区（`SelectTreeModel`） |
| GET | `/list` | `warehouseCode` `warehouseName` `warehouseType` `status` + 分页 | 分页查询 |
| GET | `/queryById` | `id` | 详情 |
| GET | `/checkCode` | `code`、`id`(可空) | 编码唯一性（蓝图步骤 3） |
| POST | `/add` | `WmsWarehouse` | 新增 |
| PUT\|POST | `/edit` | `WmsWarehouse` | 编辑（**维度可自由增减，无守卫**，见 §5.2） |
| POST | `/disable` | `id` | 停用 |
| DELETE | `/delete` | `id` | 逻辑删除（有库位/设备时拒绝） |
| DELETE | `/deleteBatch` | `ids` | 批量逻辑删除 |

**`/treeList` 的节点设计**：类型节点的 `key = "type:立库"`（非真实 id，带前缀防止与库区 id 撞键），
库区节点 `key = 真实 id`。这样左侧树既能按**类型**过滤也能按**单个库区**过滤。

### 7.2 `WmsLocationController` `@RequestMapping("/wms/location")`

| 方法 | 路径 | 入参 | 说明 |
| --- | --- | --- | --- |
| GET | `/list` | `warehouseId` `warehouseType` `locationCode` `status` `layerNo` + 分页 | 分页查询 |
| GET | `/queryById` | `id` | 详情 |
| GET | `/checkCode` | `code`、`id`(可空) | 编码唯一性（蓝图异常①） |
| POST | `/generate` | `WmsLocationGenDTO` | **批量生成**，返回 `Result<WmsLocationGenResultVO>`（验收标准①） |
| POST | `/updateAttr` | `WmsLocationAttrDTO` | 承重 / 状态（占用时拒绝，约束②） |
| POST | `/release` | `id` | 占用 → 空闲（§5.3 增补） |
| POST | `/add` | `WmsLocation` | 手工新增单个（补录空洞） |
| PUT\|POST | `/edit` | `WmsLocation` | 编辑 |
| DELETE | `/delete` | `id` | 逻辑删除（仅空闲） |
| DELETE | `/deleteBatch` | `ids` | 批量逻辑删除 |

**`/list` 的 `warehouseType` 处理（避免写 JOIN SQL）**：
`warehouseType` 非空时，先查该类型下的全部库区 id，再 `queryWrapper.in("warehouse_id", ids)`；
**若 id 集合为空则直接返回空页，不交给 `QueryGenerator`** ——
MyBatis-Plus 对空集合会生成 `IN ()` 导致 SQL 语法错误。

**生成范围守卫（`/generate`）**：

| # | 守卫 | 违反时报错 |
| --- | --- | --- |
| 1 | 库区存在且启用 | 「库区不存在或已停用」 |
| 2 | **库区三个维度均已配置且 > 0** | 「库区未配置网格维度，请先在库区上填写排 / 列 / 层上限」 |
| 3 | 各 `from >= 1` 且 `from <= to` | 「库位范围不合法」 |
| 4 | 各 `to <= 库区对应维度上限` | 「第 30 层超出库区层数上限 24」 |
| 5 | 总数 `<= 50000` | 「单次最多生成 50000 个库位，本次 N 个，请按层 / 列分批」 |

> 守卫 2 是为**线边柜**这类不适用网格模型的库区准备的（§12-10）：维度为空时不允许生成，
> 而不是拿 NULL 去算段宽、算出满屏 `null-null` 之类的东西。

### 7.3 `WmsDeviceController` `@RequestMapping("/wms/device")`

| 方法 | 路径 | 入参 | 说明 |
| --- | --- | --- | --- |
| GET | `/list` | `deviceCode` `deviceName` `deviceType` `warehouseId` `status` + 分页 | 分页查询（`status=离线` 即「只看离线」） |
| GET | `/listUnbound` | — | 未归属库区的设备精简列表（绑定弹窗用） |
| GET | `/queryById` | `id` | 详情 |
| GET | `/checkCode` | `code`、`id`(可空) | 编码唯一性 |
| POST | `/add` | `WmsDevice` | 新增 |
| PUT\|POST | `/edit` | `WmsDevice` | 编辑 |
| POST | `/bind` | `WmsDeviceBindDTO` | **绑定归属库区**，返回 `Result<WmsDeviceBindResultVO>` |
| POST | `/unbind` | `ids` | 解绑（`warehouse_id = NULL`） |
| POST | `/reportStatus` | `WmsDeviceStatusDTO` | **WCS/RMS 状态上报** |
| DELETE | `/delete` | `id` | 逻辑删除 |
| DELETE | `/deleteBatch` | `ids` | 批量逻辑删除 |

### 7.4 `QueryGenerator` 注意点

沿用 `WmsMaterialController` / `WmsOrderController` 的既有教训：
实体上若有**非持久化字段**（如列表要展示的 `warehouseName`，表无该列），
**必须先置空**再交给 `QueryGenerator`，否则会拼出 `warehouse_name` 条件导致 SQL 报错。

本设计需要在 `WmsLocation` / `WmsDevice` 上加
`@TableField(exist = false) private String warehouseName;`（列表展示库区名用），
并在**每个**经过 `QueryGenerator` 的入口落实置空。本期只有 `/list` 一处
（库位 / 库区的 Excel 导出按 §3.2 暂不做；将来补 `exportXls` 时，同一处坑会再出现一次 ——
届时照做即可。）

## 8. 前端

```
src/views/wms/warehouse/  index.vue  warehouse.api.ts  warehouse.data.ts
                          location.api.ts  location.data.ts
                          components/WarehouseTree.vue      库区树 + 库区增删改
                          components/WarehouseModal.vue     库区表单（含 row/col/layer 维度）
                          components/GenerateModal.vue      批量生成库位
                          components/LocationAttrModal.vue  库位属性（承重/状态）
                          components/LocationModal.vue      单个库位新增/编辑
src/views/wms/device/     index.vue  device.api.ts  device.data.ts
                          components/DeviceModal.vue
                          components/DeviceBindModal.vue    设备绑定库区
```

均以 `useListPage` + `BasicTable` + `TableAction` + `useModal` 组织，
与 `views/wms/material/index.vue` 同构（含其 `beforeFetch` **必须返回合并结果**的教训 ——
否则导出侧 `Object.assign(undefined, …)` 抛 TypeError，表现为「点导出没反应」）。

**库区与库位页（左树右表）**

- 左 `WarehouseTree.vue`：两层树（库区类型 → 库区）。选中类型节点 → 右侧按 `warehouseType` 过滤；
  选中库区节点 → 按 `warehouseId` 过滤。
- 右 `BasicTable` 列：库位编码、库区、排、列、层、承重、状态、创建时间。
- **状态渲染为带色 `Tag`**：空闲(绿) / 占用(蓝) / 故障(红)。
- 行操作：属性（`wms:location:attr`）/ 释放（`wms:location:release`，**仅 `占用` 时显示**）/
  删除（`wms:location:delete`，**仅 `空闲` 时显示**）。
- 工具条：批量生成（`wms:location:generate`）。

**`GenerateModal.vue`（验收标准①的主界面）**

```
选库区 → 自动带出 row_count/col_count/layer_count 作为范围初值
       → 可逐项改起止
实时显示：预计生成数 = (rowTo-rowFrom+1) × (colTo-colFrom+1) × (layerTo-layerFrom+1)
   立库 1×6×24          = 144        → 按钮可用
   > 50000                          → 按钮禁用 + 红字「单次最多 50000 个，请按层 / 列分批」
   任一 to > 库区维度上限           → 红字「第 N 层超出库区层数上限 M」
提交成功 → useMessage().success("生成 144 个，跳过 0 个")
```

- 不做进度轮询（无异步任务）。
- 文案刻意用「**本次预计生成数**」而非「仓库总容量」—— 包络是上限，料箱不一定堆满仓库。
- 「已存在则跳过」的 `skipped` 在提示语中显式呈现，使**幂等续做**对操作员可见，
  否则重跑后「生成了 0 个」会被误读为失败。

**设备管理页**

- 搜索项：设备编码 / 名称 / 类型 / 归属库区 / 状态。
- 列：编码、名称、类型、归属库区、状态、最近心跳、行内操作。
- **`status` 渲染为带色 `Tag`**：在线(绿) / 离线(红) / 故障(橙)。
- 工具条「绑定库区」按钮 → `DeviceBindModal.vue`：
  选库区 → 勾选待绑定设备（数据源 `/wms/device/listUnbound`，另支持搜索已有设备以搬迁）
  → 保存；**弹窗内对 `离线` 设备行标红并提示「保存后将产生离线告警」**；
  保存后若返回 `warning` 非空 → `useMessage().warning(warning)`（绑定成功但需知悉）。

## 9. Flyway 与菜单

新增 `jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/V3.9.5_5__wms_warehouse_location_device.sql`，
沿用既有幂等风格（`CREATE TABLE IF NOT EXISTS` / `INSERT IGNORE`）。

内容：

1. **三张表 DDL + 索引**（§4）；
2. 新菜单，父节点 = `1910000000000000001`（V3.9.5_4 已改名为**「基础管理」**）：

| id | 名称 | url | component | sort | icon |
| --- | --- | --- | --- | --- | --- |
| `1910000000000000051` | 库区与库位 | `/wms/warehouse` | `wms/warehouse/index` | 4.00 | `ant-design:apartment-outlined` |
| `1910000000000000052` | 设备管理 | `/wms/device` | `wms/device/index` | 5.00 | `ant-design:deployment-unit-outlined` |

（既有「物料与分类」sort=1.00、供应商 2.00、订单 3.00，故新菜单排 4.00 / 5.00。）

3. 按钮权限（`menu_type=2`）：

| id | 名称 | perms | 父菜单 |
| --- | --- | --- | --- |
| `1910000000000000061` | 库区-新增 | `wms:warehouse:add` | `…051` |
| `1910000000000000062` | 库区-编辑 | `wms:warehouse:edit` | `…051` |
| `1910000000000000063` | 库区-删除 | `wms:warehouse:delete` | `…051` |
| `1910000000000000064` | 库位-生成 | `wms:location:generate` | `…051` |
| `1910000000000000065` | 库位-属性 | `wms:location:attr` | `…051` |
| `1910000000000000066` | 库位-释放 | `wms:location:release` | `…051` |
| `1910000000000000067` | 库位-删除 | `wms:location:delete` | `…051` |
| `1910000000000000068` | 设备-新增 | `wms:device:add` | `…052` |
| `1910000000000000069` | 设备-编辑 | `wms:device:edit` | `…052` |
| `1910000000000000070` | 设备-删除 | `wms:device:delete` | `…052` |
| `1910000000000000071` | 设备-绑定 | `wms:device:bind` | `…052` |

4. `sys_role_permission` 给 admin（`f6817f48af4fb3af11b9e8bf182f618b`）授权，
   id 段 `1920000000000000051` / `…052` / `…061~071`。

5. `sys_quartz_job` 种子行（仅为让任务在「定时任务管理」UI 可见并支持**立即执行**；
   实际调度由 §6.5 的注册器负责）：

| 列 | 值 |
| --- | --- |
| `id` | `1920000000000000102`（**必须**与 `WmsConstants.DEVICE_SCAN_JOB_ID` 同值） |
| `job_class_name` | `org.jeecg.modules.wms.job.WmsDeviceOfflineScanJob` |
| `cron_expression` | `0 7/5 * * * ? *` |
| `description` | WMS设备离线扫描告警任务 |
| `status` | `0`（正常） |
| `del_flag` | `0` |

**ID 段分配依据**（已核对现有脚本，避免撞号）：`…0011~0018`（物料）、
`…0021/0022/0031~0046`（供应商/订单）、`…0101`（订单补偿任务 job）。故本期取 `…0051` 起。

## 10. 依赖变更

**无。** 已核实：

- `jeecg-boot-module-wms/pom.xml` **已含** `jeecg-system-local-api`（告警需要 `ISysBaseAPI`），
  BD-002 已加，本次复用，**不需要再改 pom**；
- Quartz 类经 `jeecg-boot-base-core` 传递获得，无需声明；
- Mapper XML 位置已在全部 profile 的 `mapper-locations` 覆盖，无需改 yml；
- wms 模块已在 `jeecg-boot-module/pom.xml` 的 `<modules>` 与 `jeecg-system-start/pom.xml`
  的依赖中登记，无需接线。

## 11. 验证与验收证据

### 11.1 单元测试

沿用模块现有风格：JUnit 5 + Mockito，`spy(new XxxServiceImpl())` + `WmsTestSupport.inject(...)`，
**不启 Spring 上下文、不连库**。

| 测试类 | 覆盖 |
| --- | --- |
| `WmsWarehouseServiceImplTest` | 编码唯一（新增/编辑排除自身）；树构建（类型分组、`type:` 前缀键）；**维度自由调节**：有库位时把层数 24 缩到 10、再扩到 9999，**两次都保存成功且不抛异常**（正面断言"无守卫"，与初稿的设计恰好相反）；维度为空时保存成功但生成被拒 |
| `WmsLocationServiceImplTest` | **① 编码规则（验收标准①核心）**：立库 `LB-R01-C06-L24`、低层 `LB-R01-C06-L01`；`MJ-R01-C60422-L10`；**编码与配置解耦（本轮修正的核心）**：同一 `(1,6,24)` 在**库区层数配 24 / 配 9999 / 配 10 三种情形下算出同一个编码**，且请求范围开 `[1,1]` 或 `[1,6]` 也不变；**补零只补不截**：列 6→`06`、列 106→`106`；**② 幂等**：重跑同范围 → `generated=0`、`skipped=总数`；**③ 守卫**：超 5 万拒绝、超包络拒绝、`from>to` 拒绝、维度为空拒绝；**④ 约束②**：`占用` 改属性被拒且属性未变；`release` 占用→空闲成功；`空闲` 调 `release` 被拒；**⑤ 删除守卫**：占用不可删 |
| `WmsDeviceServiceImplTest` | 绑定成功；**离线设备绑定成功且返回 `offlineDevices`**（蓝图异常②：绑定不因告警而失败）；搬迁写入 `movedDevices`；解绑；`reportStatus` 转离线触发告警、**30 分钟内重复离线不二次告警**、恢复在线清空 `last_alert_time`；**显式置在线时 `last_heartbeat` 被刷新** |
| `WmsDeviceOfflineScanJobTest` | **`last_heartbeat IS NULL` 的设备不被判离线**；静默超阈值被置离线并聚合告警；单条失败不中断整轮（3 条候选中第 2 条抛异常 → 第 1、3 条仍被处理） |
| 三个 Controller 薄层测试 | 转发与 `Result` 包装（照 `WmsSupplierControllerTest` 写法） |

> **诚实声明（边界划清楚）**：
> - 单测覆盖的是**编码规则、幂等决策、守卫、告警节流**这四处真逻辑。
> - **`insertIgnoreBatch` 的 SQL 本身不在单测范围内** —— 它需要真实数据库
>   （`INSERT IGNORE` 的"跳过"语义由 MySQL 定义）。故用「mapper 返回 0 时 `skipped` 记满」测决策，
>   用 §11.3 步骤 6–7 的真库查询测真值。这是本设计中**测试覆盖最薄的一处**，实现后应重点手工验证。
> - 单测**不证明**：前端渲染、`ISysBaseAPI` 告警真实投递、Quartz 注册器在真机生效、
>   WCS/RMS 真实回传。验证方式见 §11.3。

### 11.2 构建证据

将实际执行并**原样贴出输出**（失败也贴）：

```bash
# 本机 mvn 不在 PATH，必须走包装脚本
bash /d/tools/m.sh -P dev clean package
bash /d/tools/m.sh -P dev test -pl jeecg-boot-module/jeecg-boot-module-wms -Dtest='Wms*Test'
cd jeecgboot-vue3 && pnpm build          # 含 vue-tsc 类型检查
```

### 11.3 端到端手工验收（需 MySQL + Redis 的 dev 环境）

```bash
cd jeecg-module-system/jeecg-system-start && mvn spring-boot:run
```

1. **Flyway 取证**：启动后**必须 grep 启动日志**确认迁移脚本无报错
   —— 该环境实测 Flyway 会**静默吞错**：脚本失败时应用照常启动，
   表现为「表不存在」的后端 500，而不是启动失败。
2. 登录 → 「基础管理」下出现「库区与库位」「设备管理」。
3. 新增库区 `LB`（立库，1 排 / 6 列 / 24 层）；重复提交 → 提示「编码已存在」。
4. **验收标准①（编码规则）**：选中 `LB` → 批量生成，范围自动带出 1/1/6/24，
   预计生成数 144 → 提交 → 提示「生成 144 个，跳过 0 个」。
   列表首条应为 `LB-R01-C01-L01`，末条应为 `LB-R01-C06-L24`。
5. **验收标准①（幂等）**：**原样再提交一次** → 提示「生成 0 个，跳过 144 个」。
6. **取证**：`SELECT COUNT(*) FROM wms_location WHERE warehouse_id=<LB的id>;` → 144。
   且 `SELECT location_code FROM wms_location WHERE warehouse_id=<LB的id> ORDER BY layer_no DESC LIMIT 1;`
   → `LB-R01-C06-L24`。
7. **验收标准①（包络守卫）**：仍在 `LB` 上把层范围改成 1~30 → 应提示
   「第 30 层超出库区层数上限 24」。
   **验收标准①（5 万上限）**：新建库区 `MJ`（敏捷仓，1 排 / 60422 列 / 10 层），
   生成范围取列 1~60422 → 总数 604218 > 50000 → 应提示
   「单次最多生成 50000 个库位，本次 604218 个，请按层 / 列分批」。
   （**必须在 `MJ` 上测这一条**：`LB` 的包络只有 144，在它上面永远先撞包络守卫、撞不到上限守卫。）
8. **验收标准②（绑定时告警）**：新增设备 `DDJ01`（堆垛机）—— 注意其状态**默认为 `离线`**（§4.3）
   —— 绑定库区 `LB` → **绑定本身成功**，同时前端弹**警告**。
   **取证**：返回体 `bound = 1`、`offlineDevices = ["DDJ01"]`、`warning` 非空。
9. **验收标准②（告警落库）**：
   `SELECT device_code, status, last_alert_time FROM wms_device WHERE device_code='DDJ01';`
   → `离线 / <刚写入的时间>`；且 `sys_announcement` 新增一条标题为「WMS设备离线告警」的记录。
10. **告警节流取证**：30 分钟内再调一次 `/reportStatus` 上报 `DDJ01` 为 `离线`
    → **不应**新增公告记录（`last_alert_time` 未变）。
    再手工把 `last_alert_time` 置为 40 分钟前 → 重发同一上报 → **应**新增一条公告。
11. **恢复在线取证**：`/reportStatus` 上报 `DDJ01` 为 `在线`
    → 该行 `status=在线`、`last_heartbeat` 已刷新、**`last_alert_time` 被清空**（§6.4 链路②）。
12. **扫描任务取证 · 注册器**：**重启应用**（不做任何手工点击）→ 后端日志应出现
    离线扫描任务的注册记录（或「已存在，跳过」）。这验证 §6.5 的坑已被堵住。
13. **扫描任务取证 · 跃迁**：手工把 `DDJ01` 改成 `status='在线'` 且
    `last_heartbeat = NOW() - INTERVAL 20 MINUTE`（模拟心跳中断）→
    到「系统管理 → 定时任务」点该任务的**立即执行** →
    该行 `status` 变 `离线`，日志出现转离线记录，`sys_announcement` 新增聚合告警一条。
14. **扫描任务取证 · 不误报**：新建一台**从没上报过心跳**的设备（`last_heartbeat IS NULL`）→
    点**立即执行** → 该行**保持原状态、不产生任何告警**（验证 §6.4 第 1 条）。
15. **扫描任务取证 · 不重复投递**：紧接着**再点一次立即执行** → 该设备已在上一轮被置 `离线`，
    本轮 `WHERE status != '离线'` 已把它排除 → **不应**再产生第二条公告。
16. **约束②取证**：把某库位状态改为 `占用` → 再点「属性」改承重 → 应提示
    「库位内有物料，不允许修改属性」；点「释放」→ 变 `空闲` → 此时可改承重。
17. 库区树筛选：点类型节点「立库」→ 列表只出现立库下的库位；点库区节点 `LB` → 只出现 LB 的。
18. **库区维度自由调节取证（§5.2 本轮修正的核心）**：
    a. 对已有 144 个库位的 `LB`，把**层数由 24 缩到 10** → **保存成功，无任何拦截**；
    b. **取证**：`SELECT location_code FROM wms_location WHERE warehouse_id=<LB的id> AND layer_no=24;`
       → 仍为 `LB-R01-C06-L24`，**编码逐字符未变**；
    c. 库区树 / 列表上 `LB` 应显示提示「**有 84 个库位位于当前维度之外**」
       （层 11~24 共 14 层 × 6 列 = 84 个）；
    d. 把层数由 10 **扩到 9999** → 保存成功；此后生成的 `第 100 层` 编码应为 `LB-R01-C01-L100`
       （**段宽自然变宽、不截断**，与既有 `L24` 并存而不冲突）；
    e. 清空三个维度（或置 0）→ 保存成功；此时点「批量生成」应被拒：
       「库区未配置网格维度，请先在库区上填写排 / 列 / 层上限」。

### 11.4 验收标准对照

| 验收标准 | 证据 |
| --- | --- |
| **能按规则批量生成库位** | §11.2 单测 ①（编码规则 + 段宽自适应 + 范围无关性）+ §11.3 步骤 4（144 条、首末编码）+ 步骤 5（幂等）+ 步骤 6（SQL 计数）+ 步骤 7（包络守卫 / 5 万上限守卫） |
| **设备绑定成功，离线设备有告警** | §11.2 单测（绑定成功 + `offlineDevices` 返回）+ §11.3 步骤 8（绑定成功且返回告警清单）+ 步骤 9–11（告警落库、节流、恢复在线）+ 步骤 12–15（扫描任务注册、跃迁、不误报、不重复投递） |
| 约束① 库位编码唯一 | DB 唯一索引 + `checkCode` + 生成时 `INSERT IGNORE`；§11.3 步骤 5 |
| **库区维度可自由增减**（需求方追加要求） | §11.2 单测（缩维/扩维均成功、三种配置下编码恒定）+ §11.3 步骤 18（真库缩维后编码不变、扩维后段宽自然变宽、空维度拒生成） |
| 约束② 有物料禁改属性 | §11.2 单测 ④ + §11.3 步骤 16 |
| 约束③ 设备离线告警 | §11.3 步骤 8–15 |

## 12. 已知遗留与风险

1. **「占用」依赖未来模块回写**：本期无库存表，`占用` 由谁置位尚未有自动化来源（入库模块落地后由它负责）。
   在入库模块上线前，`占用` 只能人工设置 —— 这也是 §5.3 增补 `release` 的原因。
2. **`insertIgnoreBatch` 无单测**：`INSERT IGNORE` 的真实跳过语义只能靠 §11.3 步骤 5–6 的真库验证。
   本设计中**测试覆盖最薄的一处**。
3. **`INSERT IGNORE` 会掩盖非重复类错误**（如字段超长、类型不匹配）。已用生成前的编码长度校验
   兜住最可能的一种；若有其它静默丢行，需靠步骤 5 的 `generated + skipped == 请求总数` 断言发现。
4. **单次 5 万上限 vs 60 万目标**：敏捷仓铺满需 ≥12 次请求，属**需求方主动选择**的口径（结论 #2）。
   操作员需自行按层/列分批，前端已给出明确的超限提示与实时预计数。
5. **库区维度可自由增减（已按需求方要求修正，见 §5.2）**：初稿曾用"禁止缩维 / 禁止跨段宽"
   来保编码稳定，属**把锁加错了地方**。正确做法是把库区维度从编码规则里移除：
   段宽只由库位自身数字决定（`max(2, 自身位数)`，只补不截），故维度随便改都不影响已有编码。
   **剩余的唯一后果**：缩维后已存在的库位可能落在新声明的包络之外 —— **不拦截**，
   但在库区树/列表上显示「有 N 个库位位于当前维度之外」，把不一致暴露给人看（§5.2）。
6. **告警通道单一**：仅站内消息 + ERROR/WARN 日志，未接钉钉 / 邮件。
7. **「下发设备注册信息」未实现**：蓝图步骤 5 有该动作，但 WCS/RMS 接入属 `YX-WMS-SB-002`，
   当前不存在。本期只落绑定关系；接入后应在 `bind` 成功后追加下发调用。
8. **中文字面量枚举**：可读性好，但改文案即等于改数据。若后续要国际化，需迁移到数据字典。
9. **两份同构的 Job 注册器**（§6.5）：刻意不抽象，见该节说明。
10. **线边柜库区只建档**：线边柜的最小存储单元是「格子」，与立库/敏捷仓的排-列-层网格不同构。
    本期线边柜库区可建档但**不应用库位网格生成**（生成规则对齐的是立体货架）。

## 13. 交付物清单

**后端新增 26 个文件**（`jeecg-boot-module-wms/src/main/java/org/jeecg/modules/wms/`）：

| 目录 | 文件 | 数 |
| --- | --- | --- |
| `entity/` | `WmsWarehouse.java`、`WmsLocation.java`、`WmsDevice.java` | 3 |
| `dto/` | `WmsLocationGenDTO.java`、`WmsLocationAttrDTO.java`、`WmsDeviceBindDTO.java`、`WmsDeviceStatusDTO.java` | 4 |
| `vo/` | `WmsLocationGenResultVO.java`、`WmsDeviceBindResultVO.java` | 2 |
| `mapper/` | `WmsWarehouseMapper.java`、`WmsLocationMapper.java`、`WmsDeviceMapper.java`、`xml/WmsLocationMapper.xml` | 4 |
| `service/` | `IWmsWarehouseService.java`、`IWmsLocationService.java`、`IWmsDeviceService.java`、`IWmsDeviceAlertService.java` | 4 |
| `service/impl/` | `WmsWarehouseServiceImpl.java`、`WmsLocationServiceImpl.java`、`WmsDeviceServiceImpl.java`、`WmsDeviceAlertServiceImpl.java` | 4 |
| `job/` | `WmsDeviceOfflineScanJob.java`、`WmsDeviceOfflineScanJobRegistrar.java` | 2 |
| `controller/` | `WmsWarehouseController.java`、`WmsLocationController.java`、`WmsDeviceController.java` | 3 |
| | **合计** | **26** |

**后端修改 1 个文件**：`constant/WmsConstants.java`（追加库区类型 / 库位状态 / 设备类型 / 设备状态 /
生成上限与批大小 / 离线阈值与节流 / 扫描任务常量）。

**测试新增**（`jeecg-boot-module-wms/src/test/java/org/jeecg/modules/wms/`）：
`WmsWarehouseServiceImplTest`、`WmsLocationServiceImplTest`、`WmsDeviceServiceImplTest`、
`WmsDeviceOfflineScanJobTest`、`WmsWarehouseControllerTest`、`WmsLocationControllerTest`、`WmsDeviceControllerTest`。

**Flyway 新增 1 个脚本**：`V3.9.5_5__wms_warehouse_location_device.sql`。

**前端新增 14 个文件**（`jeecgboot-vue3/src/views/wms/`）：

| 目录 | 文件 |
| --- | --- |
| `warehouse/` | `index.vue`、`warehouse.api.ts`、`warehouse.data.ts`、`location.api.ts`、`location.data.ts` |
| `warehouse/components/` | `WarehouseTree.vue`、`WarehouseModal.vue`、`GenerateModal.vue`、`LocationAttrModal.vue`、`LocationModal.vue` |
| `device/` | `index.vue`、`device.api.ts`、`device.data.ts` |
| `device/components/` | `DeviceModal.vue`、`DeviceBindModal.vue` |

**依赖变更**：无（§10）。