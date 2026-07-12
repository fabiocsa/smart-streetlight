# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

智慧路灯系统（Smart Streetlight）— 重庆交通大学 × 中软国际实训项目。通过 MQTT 接收传感器数据，根据光照阈值自动控制路灯开关，支持手动远程控制、离线告警、历史趋势可视化和 AI 智能问答。

## 架构与数据流

```
模拟器/鸿蒙设备 ──MQTT──→ EMQX Broker ──MQTT──→ Spring Boot 后端 ──WebSocket──→ Vue 3 前端
                                         ←──MQTT── (控制指令下行 → 传感器 cmd 主题)
```

**核心数据流 (v4 — 传感器独立，去设备化)：**
1. mock-sender（`tools/mock-sender/`）或真实设备通过 MQTT 上报传感器数据，topic 格式 `streetlight/sensor/{sensorId}/data`，**payload 不含 deviceId**
2. `MqttMessageHandler` 接收 → 通过 `device_sensor` 关联表解析 sensorId → deviceId → `SensorDataServiceImpl.saveAndAutoControl()` 保存到 `sensor_data` 表，同时判断光照阈值自动联动开关灯
3. 实时数据通过 `WebSocketHandler.pushSensorData()` 推送到前端 `/ws/monitor`
4. 控制指令（开关灯、模式切换）通过 MQTT 下行到传感器 cmd 主题 → 传感器响应 `streetlight/sensor/{sensorId}/cmd/response`

**关键设计决策 (v5 — 传感器数据按类型分离)：**
- **每种传感器只发送自己类型的字段**：light → illuminance/cloudCover/status，temperature → temperature，humidity → humidity，power → voltage/power。不再跨类型混发
- 模拟器 `data_generator.py` 通过 `SENSOR_TYPE_FIELDS` 字典控制每种类型的字段集
- 后端告警检测按 sensorType 分流：电压告警仅 light/power 触发，温度告警仅 temperature 触发，功率告警仅 light/power 触发
- 趋势查询 `DashboardService.sensorTypeForMetric()` 按指标名动态映射传感器类型（不再硬编码 light）
- 设备支持**多传感器决策策略**（`Device.sensorStrategy` 字段）：`single`（指定主传感器）+ `primarySensorId`，或 `average`（取所有 bound light 传感器平均值）
- 仪表盘"设备最新传感器数据"表：后端 `getLatestSensorData()` 按 deviceId 分组合并多种传感器数据为一行；前端 `dedupSensorRows()` 去重

**关键设计决策 (v4 — 传感器独立，去设备化)：**
- 模拟器端**完全不接触 deviceId**，只认 sensorId。后端通过 `device_sensor` 关联表解析绑定关系
- 传感器（`Sensor`）与设备（`Device`）是独立实体，通过 `device_sensor` 关联表 N:M 绑定。传感器上报数据时可能未绑定设备，`deviceId` 会以 `sensor_{id}` 作为临时标识
- 控制指令通过传感器 cmd 主题下发：`streetlight/sensor/{sensorId}/cmd`。后端查找设备绑定的 light 传感器，逐传感器发送
- 传感器数据统一存 JSON 列（`sensor_data.data_json`），支持异构多维数据，无需 ALTER TABLE
- JPA `ddl-auto: update`，Hibernate 自动同步表结构；`application.yml` 连接远程 MySQL
- MQTT 使用 Eclipse Paho v5，订阅通配符 topic `streetlight/sensor/+/data`、`streetlight/sensor/+/status`、`streetlight/sensor/+/cmd/response`
- **多实例部署**：所有实例完全相同。数据主题使用 EMQX 共享订阅 `$share/backend/` 前缀，EMQX 轮询分发；控制响应使用普通订阅，所有实例都收到。DB 唯一约束 `UNIQUE(sensor_id, reported_at)` 作为兜底。Client ID 使用 UUID 后缀确保唯一
- 自动联动仅对 `sensorType=light` 且在 `auto` 模式的设备生效：光照 < `thresholdOn` 开灯，光照 > `thresholdOff` 关灯
- 自动联动使用 `SELECT ... FOR UPDATE` 悲观行锁防止并发重复触发
- 告警由 `HeartbeatChecker`（`@Scheduled` 定时任务）检测设备心跳超时自动生成，设备恢复上线时**仅**自动解除 OFFLINE 类型告警
- **WebSocket 状态推送**：不仅在心跳超时时推送，还在开关灯、模式切换、设备恢复上线时主动推送 `DEVICE_STATUS`，确保前端实时刷新
- **MQTT 时间戳**：模拟器发送 UTC 时间（带 Z 后缀），后端 `MqttMessageHandler` 检测 Z 后缀后 +8 小时转为北京时间存入 DB

## 常用命令

```bash
# 后端（Java 17 + Spring Boot 3.2.5 + Maven）
cd backend
mvn spring-boot:run                   # 启动后端（端口 8080）
mvn clean package -DskipTests         # 构建 JAR
mvn test                              # 运行测试（当前无测试用例）

# 前端（Vue 3 + Vite + Element Plus）
cd frontend
npm install                            # 安装依赖
npm run dev                            # 启动开发服务器（端口 3000，/api 和 /ws 代理到 :8080）
npm run build                          # 生产构建 → frontend/dist/
npm run preview                        # 预览生产构建

# Mock 数据发生器（Python）
cd tools/mock-sender
pip install -r requirements.txt
python app.py                          # 启动 Web 管理界面（端口 5000）+ MQTT 模拟器

# 数据库（连接远程 MySQL，初始化脚本）
mysql -h 8.130.102.89 -u remote_user -p streetlight < docs/init.sql
```

### 环境要求

- **JDK 17** + Maven 3.6+
- **Node.js 18+** + npm 9+
- **Python 3.8+**（仅 mock-sender）
- **MySQL 8.0**（远程服务器 `8.130.102.89:3306`）
- **EMQX MQTT Broker**（`8.130.102.89:1883`，已部署）

开发和构建均依赖远程数据库和 MQTT Broker，本地无需安装 MySQL 或 EMQX。

## 后端分层

| 层 | 位置 | 职责 |
|---|---|---|
| Controller | `controller/` | REST API，`/api/*` 前缀 |
| Service | `service/` + `service/impl/` | 业务逻辑 |
| Repository | `repository/` | Spring Data JPA，含原生 SQL 与 JPQL 聚合查询 |
| Entity | `entity/` | JPA 实体：Device, Sensor, SensorData(JSON列), AlarmLog, ControlLog, ChatMessage, ChatSession, User |
| MQTT | `mqtt/` | `MqttClientManager`（连接/订阅/主题路由）、`MqttMessageHandler`（消息分发）、`MqttPublishService`（发送指令） |
| WebSocket | `websocket/` | `WebSocketHandler`，端点 `/ws/monitor`，广播 SENSOR_DATA / DEVICE_STATUS / NEW_ALARM / CONTROL_RESULT |
| Config | `config/` | MQTT、WebSocket、CORS、DeepSeek、RestTemplate |
| Security | `security/` | JWT 鉴权 + `AuthInterceptor` |
| Common | `common/` | `Result`（统一响应体）、`GlobalExceptionHandler`、`BusinessException` |

### RBAC 权限模型

系统有三个角色：`admin`（管理员）、`operator`（操作员）和 `municipal`（市政人员）。

| 模块 | admin | operator | municipal |
|---|---|---|---|
| 设备管理（查看） | ✅ | ✅ | ✅ |
| 设备管理（增删改/批量删除） | ✅ | ✅ | ❌ |
| 传感器管理（查看） | ✅ | ✅ | ✅ |
| 传感器管理（增删改/绑定） | ✅ | ✅ | ❌ |
| 设备控制（开关灯/模式切换） | ✅ | ✅ | ✅ |
| 批量阈值设置 | ✅ | ✅ | ❌ |
| 告警管理 & 告警规则 | ✅ | ❌ | ❌ |
| AI 智能问答 | ✅ | ✅ | ✅ |
| 历史趋势 | ✅ | ✅ | ✅ |

权限在 `AuthInterceptor.preHandle()` 中按 URI 路径匹配校验。前端路由通过 `meta.roles` 字段控制菜单可见性，`router.beforeEach` 做二次校验。
前端 store：`authStore.isAdmin`、`authStore.isOperator`、`authStore.isMunicipal`。

### 关键枚举

| 枚举 | 位置 | 可选值 |
|---|---|---|
| `ControlMode` | `enums/` | `auto`（自动联动）, `manual`（手动控制） |
| `LightStatus` | `enums/` | `on`（开灯）, `off`（关灯） |
| `DeviceStatus` | `enums/` | `online`, `offline` |
| `AlarmType` | `enums/` | `OFFLINE`, `SENSOR_ABNORMAL`, `VOLTAGE_ABNORMAL`, `TEMPERATURE_HIGH`, `POWER_HIGH` |
| `AlarmSeverity` | `enums/` | `INFO`, `WARNING`, `CRITICAL` |
| `AlarmStatus` | `enums/` | `PENDING`（待处理）, `RESOLVED`（已解除） |

### 速查：关键 Service (v5)

| Service | 职责 |
|---|---|
| `SensorDataServiceImpl.saveAndAutoControl()` | **核心方法**：保存数据 → 更新心跳 → 告警检测（按 sensorType 分流） → `SELECT ... FOR UPDATE` 悲观锁读设备 → 多传感器策略（single/average） → 光照阈值判定 → 下发 MQTT cmd → WebSocket 推送状态 |
| `ControlServiceImpl` | 设备控制（开关灯/模式切换/阈值/传感器策略），通过 light 传感器 cmd 主题下发 |
| `DashboardService` | 仪表盘数据聚合。`getLatestSensorData()` 按 deviceId 合并多种传感器数据；`getSensorTrend()` 通过 `sensorTypeForMetric()` 动态映射 sensorType |
| `AlarmServiceImpl` | 5 种告警类型（OFFLINE/VOLTAGE_ABNORMAL/TEMPERATURE_HIGH/POWER_HIGH/SENSOR_ABNORMAL），自动创建/解除。电压/温度/功率告警仅对相应的 sensorType 触发 |
| `SensorRegistrationService` | 传感器自动注册/注销，`autoRegisterFromData()` 使用 `REQUIRES_NEW` 事务 |
| `HeartbeatChecker.checkHeartbeats()` | `@Scheduled(fixedRate=5000)` 每 5 秒检查心跳超时，标记离线并生成告警 |
| `ChatServiceImpl` | DeepSeek API 多轮对话 |

### 循环依赖处理

`MqttMessageHandler` ↔ `MqttClientManager` 和 `MqttMessageHandler` → `DeviceService` → `SensorRepository` → … → `MqttPublishService` → `MqttClientManager` 形成间接循环。通过 `@Lazy` 注解打破：`MqttMessageHandler` 构造函数中注入 `DeviceService` 和 `MqttClientManager` 时使用 `@Lazy`，Spring 注入代理对象，首次调用时才解析实际 bean。

## 前端架构

### 路由与权限

| 路由 | 组件 | 说明 | 权限 |
|---|---|---|---|
| `/login` | Login.vue | 登录页 | 公开 |
| `/dashboard` | Dashboard.vue | 仪表盘总览 | admin, municipal |
| `/devices` | DeviceList.vue | 设备管理 CRUD | admin, municipal |
| `/devices/:id` | DeviceDetail.vue | 设备详情 + 传感器绑定 | admin, municipal |
| `/sensors` | SensorList.vue | 传感器管理 | admin, municipal |
| `/light-trend` | LightTrend.vue | 历史趋势（ECharts 多指标） | admin, municipal |
| `/alarms` | AlarmList.vue | 告警管理 | **仅 admin** |
| `/chat` | ChatView.vue | AI 智能问答 | admin, municipal |

路由使用 `createWebHashHistory`（hash 模式），`router.beforeEach` 守卫检查 token 和角色权限。

状态管理：`stores/authStore.js`（登录/登出/角色）、`stores/chatStore.js`（AI 对话历史）。

### WebSocket 实时数据

前端在 `Dashboard.vue` 等页面建立 WebSocket 连接到 `ws://localhost:8080/ws/monitor`（开发环境通过 Vite proxy）。收到 JSON 消息后根据 `type` 字段分发：

| type | 触发动作 |
|---|---|
| `SENSOR_DATA` | 更新仪表盘实时数据面板（光照/温度/湿度/功率） |
| `DEVICE_STATUS` | 更新设备在线/离线状态指示 |
| `NEW_ALARM` | 弹出告警通知 + 刷新告警列表 |
| `CONTROL_RESULT` | 显示控制指令执行结果通知 |

## AI 智能问答（DeepSeek）

后端通过 RestTemplate 调用 DeepSeek API（`api.deepseek.com`），配置在 `application.yml` 的 `deepseek` 段：
- 默认模型 `deepseek-chat`，可通过环境变量覆盖
- `system-prompt` 注入角色：你是一个智慧路灯管理系统的智能助手…
- 支持多轮对话：`ChatMessage` 表存储历史消息，按 `ChatSession` 分组
- 前端 `/chat` 页面实现流式打字机效果（非真实 SSE 流，前端模拟逐字显示）

## Mock 数据发生器 (v5)

`tools/mock-sender/` 是基于真实时钟 + 太阳位置模型的 Python 模拟器：
- `data_generator.py`：使用 Spencer(1971) 太阳赤纬公式 + Beer-Lambert 大气光程模型。**v5: `SENSOR_TYPE_FIELDS` 字典控制每种传感器类型只发送自己的字段**（light 不发温湿度，temperature 只发温度等）
- 重庆默认坐标：lat=29.5, lon=106.5, UTC+8
- `app.py`：Flask Web 管理界面（端口 **5050**），可动态增删传感器、调整上报间隔
- `config.json`：传感器配置持久化文件（不含 deviceId）
- `sender/db.py`：**SQLite 状态持久化**（`state.db`），保存每个传感器的 running/enabled/controlMode，**模拟器重启后自动恢复关闭前的状态**（上次停止的传感器保持停止）
- `mqtt_client.py`：封装 paho-mqtt
- `sensor_manager.py`：`SensorWorker.on_cmd()` 处理控制指令，`SensorManager.on_sensor_cmd()` 分发。`load_from_config()` 会合并 SQLite 中保存的状态
- **UI 功能**：传感器卡片网格 + 模糊搜索 + 类型筛选 chips + SortableJS 拖拽排序（带弹簧动画）

## MQTT Topic 设计 (v5)

| Topic | 方向 | 说明 |
|---|---|---|
| `streetlight/sensor/{sensorId}/data` | 上行 | 传感器数据上报（不含 deviceId） |
| `streetlight/sensor/{sensorId}/status` | 上行 | 传感器心跳 |
| `streetlight/sensor/{sensorId}/cmd` | **下行** | 后端 → 传感器控制指令 |
| `streetlight/sensor/{sensorId}/cmd/response` | 上行 | 传感器 → 后端控制响应 |
| `streetlight/sensor/register` | 上行 | 传感器自动注册 |
| `streetlight/sensor/unregister` | 上行 | 传感器注销 |

**已删除的旧 topic：** `streetlight/{deviceId}/control`、`streetlight/{deviceId}/control/response`、`streetlight/mock-sender/config`

## SensorData JSON 列设计 (v5)

`sensor_data` 表的 `data_json` 列存储 JSON 数据。**v5: 每种传感器类型只含自己的字段**：

| 传感器类型 | data_json 字段 |
|-----------|---------------|
| light | `illuminance`, `lightIntensity`, `cloudCover`, `status` |
| temperature | `temperature` |
| humidity | `humidity` |
| power | `voltage`, `power` |

Repository 使用 MySQL `JSON_EXTRACT` 函数查询 JSON 字段。趋势查询中 `sensorTypeForMetric()` 将指标名映射到传感器类型（`lightIntensity`→light, `temperature`→temperature 等），按正确类型聚合。

## 配置要点

### application.yml 关键配置

```yaml
streetlight.heartbeat.timeout: 30      # 心跳超时阈值（秒），超时后 HeartbeatChecker 标记离线
mqtt.topic-prefix: streetlight          # MQTT 主题前缀，MqttClientManager 据此路由消息
deepseek.enabled: true                  # 设为 false 可禁用 AI 问答（ChatController 返回 503）
```

MQTT Client ID 必须唯一（当前为 `backend-server-v2`，mock-sender 为 `mock-sender-v4`），多实例部署时需改为动态生成。

### 无测试覆盖

当前项目没有单元测试或集成测试。`pom.xml` 引入了 `spring-boot-starter-test` 但 `src/test/` 目录为空。添加新功能时建议从零开始搭建测试基础设施。

### 启动顺序

1. 确保远程 MySQL 和 EMQX Broker 可访问
2. 启动后端（Hibernate 自动建表/更新表结构）
3. 启动前端
4. （可选）启动 mock-sender 进行数据模拟
