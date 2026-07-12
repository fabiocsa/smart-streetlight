# 智慧路灯系统 — 重要设计决议

## v5 — 传感器数据按类型分离（2026-07）

**背景**：v4 中所有传感器类型共用同一套字段（light 也发温湿度，temperature 也发光照），导致后端告警检测和趋势聚合逻辑混乱。

**决议**：
1. **每种传感器只发送自己类型的字段**：
   - light → `illuminance`, `lightIntensity`, `cloudCover`, `status`
   - temperature → `temperature`
   - humidity → `humidity`
   - power → `voltage`, `power`
2. 模拟器 `data_generator.py` 通过 `SENSOR_TYPE_FIELDS` 字典控制每种类型的字段集
3. 后端告警检测按 sensorType 分流：电压告警仅 light/power 触发，温度告警仅 temperature 触发，功率告警仅 light/power 触发
4. 趋势查询 `DashboardService.sensorTypeForMetric()` 按指标名动态映射传感器类型（不再硬编码 light）

**涉及文件**：`data_generator.py`, `MqttMessageHandler.java`, `SensorDataServiceImpl.java`, `DashboardService.java`

---

## v4 — 传感器独立，去设备化（2026-07）

**背景**：v3 中传感器通过 `deviceId` 关联设备，模拟器端需要知道设备信息，耦合度高。

**决议**：
1. **模拟器端完全不接触 deviceId**，只认 sensorId。后端通过 `device_sensor` 关联表解析绑定关系
2. 传感器（Sensor）与设备（Device）是独立实体，通过 `device_sensor` 关联表 N:M 绑定
3. 传感器上报数据时可能未绑定设备，`deviceId` 以 `sensor_{id}` 作为临时标识
4. **控制指令通过传感器 cmd 主题下发**：`streetlight/sensor/{sensorId}/cmd`。后端查找设备绑定的 sensor，逐传感器发送
5. **MQTT Topic 重新设计**（详见系统设计.md）

**涉及文件**：全项目重构

---

## v3 — JSON 列存储方案

**背景**：传统 EAV 或宽表方案无法灵活适配多类型传感器数据。

**决议**：
1. `sensor_data` 表使用 JSON 列（`data_json`）存储传感器全量数据，支持异构多维数据，无需 ALTER TABLE
2. JPA `ddl-auto: update`，Hibernate 自动同步表结构
3. Repository 使用 MySQL `JSON_EXTRACT` 函数查询 JSON 字段
4. 设备与传感器之间通过 `device_sensor` 关联表实现 N:M 绑定

## v2 — 设备支持多传感器决策策略

**决议**：
1. Device 表新增 `sensorStrategy` 字段：`single`（指定主传感器）+ `primarySensorId`，或 `average`（取所有 bound sensor 平均值）
2. 自动联动使用 `SELECT ... FOR UPDATE` 悲观行锁防止并发重复触发
3. 告警由 `HeartbeatChecker`（`@Scheduled` 定时任务）每 5 秒检测心跳超时

## v1 — 架构基础

**决议**：
1. 通信协议统一使用 MQTT
2. 前后端通过 REST + WebSocket 通信
3. 后端 Java 17 + Spring Boot 3.2.5
4. 前端 Vue 3 + Element Plus + ECharts
5. 数据库 MySQL 8.0 远程服务器
6. MQTT Broker EMQX 远程服务器
