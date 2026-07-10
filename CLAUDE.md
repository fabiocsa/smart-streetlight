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

**关键设计决策 (v4)：**
- 模拟器端**完全不接触 deviceId**，只认 sensorId。后端通过 `device_sensor` 关联表解析绑定关系
- 传感器（`Sensor`）与设备（`Device`）是独立实体，通过 `device_sensor` 关联表 N:M 绑定。传感器上报数据时可能未绑定设备，`deviceId` 会以 `sensor_{id}` 作为临时标识
- 控制指令通过传感器 cmd 主题下发：`streetlight/sensor/{sensorId}/cmd`。后端查找设备绑定的 light 传感器，逐传感器发送
- 传感器数据统一存 JSON 列（`sensor_data.data_json`），支持异构多维数据（光照、温度、湿度、功率等），无需 ALTER TABLE
- JPA `ddl-auto: update`，Hibernate 自动同步表结构；`application.yml` 连接远程 MySQL
- MQTT 使用 Eclipse Paho v5，订阅通配符 topic `streetlight/sensor/+/data`、`streetlight/sensor/+/status`、`streetlight/sensor/+/cmd/response`
- 自动联动仅对 `sensorType=light` 且在 `auto` 模式的设备生效：光照 < `thresholdOn` 开灯，光照 > `thresholdOff` 关灯
- 自动联动使用 `SELECT ... FOR UPDATE` 悲观行锁防止并发重复触发
- 告警由 `HeartbeatChecker`（`@Scheduled` 定时任务）检测设备心跳超时自动生成，设备恢复上线时**仅**自动解除 OFFLINE 类型告警
- 前端为纯 REST 通信，MQTT 主题对前端透明；WebSocket 用于实时推送控制结果与设备状态

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

系统有两个角色：`admin`（管理员）和 `municipal`（市政人员）。

| 模块 | admin | municipal |
|---|---|---|
| 设备管理（查看） | ✅ | ✅ |
| 设备管理（增删改） | ✅ | ❌ |
| 传感器管理（查看） | ✅ | ✅ |
| 传感器管理（增删改/绑定） | ✅ | ❌ |
| 设备控制（开关灯/模式切换） | ✅ | ✅ |
| 告警管理 & 告警规则 | ✅ | ❌ |
| AI 智能问答 | ✅ | ✅ |
| 历史趋势 | ✅ | ✅ |

权限在 `AuthInterceptor.preHandle()` 中按 URI 路径匹配校验。前端路由通过 `meta.roles` 字段控制菜单可见性，`router.beforeEach` 做二次校验。

### 关键枚举

| 枚举 | 位置 | 可选值 |
|---|---|---|
| `ControlMode` | `enums/` | `auto`（自动联动）, `manual`（手动控制） |
| `LightStatus` | `enums/` | `on`（开灯）, `off`（关灯） |
| `DeviceStatus` | `enums/` | `online`, `offline` |
| `AlarmType` | `enums/` | `OFFLINE`, `ABNORMAL_DATA` 等 |
| `AlarmSeverity` | `enums/` | `INFO`, `WARNING`, `CRITICAL` |
| `AlarmStatus` | `enums/` | `PENDING`（待处理）, `RESOLVED`（已解除） |

### 速查：关键 Service (v4)

| Service | 职责 |
|---|---|
| `SensorDataServiceImpl.saveAndAutoControl()` | **核心方法**：保存传感器数据 → 更新心跳 → `SELECT ... FOR UPDATE` 悲观锁读设备 → 判断光照阈值自动开关灯 → 查绑定的 light 传感器 → 下发 MQTT cmd → 推送 WebSocket |
| `ControlServiceImpl` | 执行设备控制指令（开/关灯、模式切换），通过查找设备绑定的 light 传感器，经 `streetlight/sensor/{sensorId}/cmd` 下发 |
| `SensorRegistrationService` | 处理模拟器传感器自动注册/注销、从数据报文恢复传感器记录。`autoRegisterFromData()` 使用 `REQUIRES_NEW` 事务 |
| `HeartbeatChecker.checkHeartbeats()` | `@Scheduled(fixedRate=5000)` 每 5 秒检查心跳超时，标记离线并委托 `AlarmService.createOfflineAlarm()` 生成告警 |
| `ChatServiceImpl` | 调用 DeepSeek API，注入系统角色上下文（路灯数量/传感器数据等），支持多轮对话 |
| `AlarmServiceImpl.autoResolveOfflineAlarm()` | 设备恢复上线时**仅**将 OFFLINE 类型的 PENDING 告警标记为 RESOLVED（不误关其他类型告警） |

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

## Mock 数据发生器 (v4)

`tools/mock-sender/` 是基于真实时钟 + 太阳位置模型的 Python 模拟器：
- `data_generator.py`：使用 Spencer(1971) 太阳赤纬公式 + Beer-Lambert 大气光程模型生成符合物理规律的光照数据，包含昼夜变化、云量随机游走、温度季节联动
- 重庆默认坐标：lat=29.5, lon=106.5, UTC+8
- `app.py`：Flask Web 管理界面（端口 5050），可动态增删传感器、调整上报间隔
- `config.json`：传感器配置持久化文件（**v4: 不含 deviceId**）
- **v4 关键变更**：模拟器只认 sensorId，订阅 `streetlight/sensor/{sensorId}/cmd` 接收控制指令，响应到 `streetlight/sensor/{sensorId}/cmd/response`
- `mqtt_client.py`：封装 paho-mqtt，提供传感器 cmd 订阅/发布
- `sensor_manager.py`：`SensorWorker.on_cmd()` 处理控制指令，`SensorManager.on_sensor_cmd()` 分发

## MQTT Topic 设计 (v4)

| Topic | 方向 | 说明 |
|---|---|---|
| `streetlight/sensor/{sensorId}/data` | 上行 | 传感器数据上报（不含 deviceId） |
| `streetlight/sensor/{sensorId}/status` | 上行 | 传感器心跳 |
| `streetlight/sensor/{sensorId}/cmd` | **下行** | 后端 → 传感器控制指令 |
| `streetlight/sensor/{sensorId}/cmd/response` | 上行 | 传感器 → 后端控制响应 |
| `streetlight/sensor/register` | 上行 | 传感器自动注册 |
| `streetlight/sensor/unregister` | 上行 | 传感器注销 |

**已删除的旧 topic：** `streetlight/{deviceId}/control`、`streetlight/{deviceId}/control/response`、`streetlight/mock-sender/config`

## SensorData JSON 列设计 (v4)

`sensor_data` 表的 `data_json` 列存储 JSON 数据，支持异构多维传感器数据，无需 ALTER TABLE。
**v4: payload 不再含 `deviceId` 字段**，`device_id` 列由后端通过 `device_sensor` 关联表解析后填充。

```java
// SensorData.from() 静态工厂 — 将 Map 序列化到 dataJson 列
SensorData sd = SensorData.from(deviceId, sensorId, sensorType, data, reportedAt);
// data 示例 (v4 — 无 deviceId): {"illuminance": 320.5, "temperature": 28.3, "humidity": 65.0, "voltage": 220.1, "power": 45.0}
```

Repository 使用 MySQL `JSON_EXTRACT` 函数在 SQL 层直接查询 JSON 字段：

```java
@Query(value = "SELECT AVG(CAST(JSON_UNQUOTE(JSON_EXTRACT(data_json, CONCAT('$.', :field))) AS DOUBLE)) " +
       "FROM sensor_data WHERE device_id = :deviceId AND ...")
Double avgByField(@Param("deviceId") String deviceId, @Param("field") String field, ...);
```

添加新的传感器指标只需模拟器端在 JSON 中多传一个字段，无需修改表结构或实体类。

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
