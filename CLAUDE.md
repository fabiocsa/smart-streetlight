# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

智慧路灯系统（Smart Streetlight）— 重庆交通大学 × 中软国际实训项目。通过 MQTT 接收传感器数据，根据光照阈值自动控制路灯开关，支持手动远程控制、离线告警、历史趋势可视化和 AI 智能问答。

## 架构与数据流

```
模拟器/鸿蒙设备 ──MQTT──→ EMQX Broker ──MQTT──→ Spring Boot 后端 ──WebSocket──→ Vue 3 前端
                                         ←──MQTT── (控制指令下行)
```

**核心数据流：**
1. mock-sender（`tools/mock-sender/`）或真实设备通过 MQTT 上报传感器数据，topic 格式 `streetlight/sensor/{sensorId}/data`
2. `MqttMessageHandler` 接收 → `SensorDataServiceImpl.saveAndAutoControl()` 保存到 `sensor_data` 表，同时判断光照阈值自动联动开关灯
3. 实时数据通过 `WebSocketHandler.pushSensorData()` 推送到前端 `/ws/monitor`
4. 控制指令（开关灯、模式切换）通过 MQTT 下行 → 设备响应 `streetlight/{deviceId}/control/response`

**关键设计决策：**
- 传感器（`Sensor`）与设备（`Device`）是独立实体，通过 `device_sensor` 关联表 N:M 绑定。传感器上报数据时可能未绑定设备，`deviceId` 会以 `sensor_{id}` 作为临时标识
- 传感器数据统一存 JSON 列（`sensor_data.data_json`），支持异构多维数据（光照、温度、湿度、功率等），无需 ALTER TABLE
- JPA `ddl-auto: update`，Hibernate 自动同步表结构；`application.yml` 连接远程 MySQL
- MQTT 使用 Eclipse Paho v5，订阅通配符 topic `streetlight/sensor/+/data` 和 `streetlight/sensor/+/status`
- 自动联动仅对 `sensorType=light` 且在 `auto` 模式的设备生效：光照 < `thresholdOn` 开灯，光照 > `thresholdOff` 关灯
- 告警由 `HeartbeatChecker`（`@Scheduled` 定时任务）检测设备心跳超时自动生成，设备恢复上线时自动解除

## 常用命令

```bash
# 后端（Java 17 + Spring Boot 3.2.5 + Maven）
cd backend
mvn spring-boot:run                   # 启动后端（端口 8080）

# 前端（Vue 3 + Vite + Element Plus）
cd frontend
npm install                            # 安装依赖
npm run dev                            # 启动开发服务器（端口 3000，/api 代理到 :8080）
npm run build                          # 生产构建 → frontend/dist/

# Mock 数据发生器（Python）
cd tools/mock-sender
pip install -r requirements.txt
python app.py                          # 启动 Web 管理界面 + MQTT 模拟器

# 数据库（连接远程 MySQL，初始化脚本）
mysql -h 8.130.102.89 -u remote_user -p streetlight < docs/init.sql
```

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

## 前端路由

| 路由 | 组件 | 说明 |
|---|---|---|
| `/login` | Login.vue | 登录页（公开） |
| `/dashboard` | Dashboard.vue | 仪表盘总览 |
| `/devices` | DeviceList.vue | 设备管理 CRUD |
| `/devices/:id` | DeviceDetail.vue | 设备详情 + 传感器绑定 |
| `/sensors` | SensorList.vue | 传感器管理 |
| `/light-trend` | LightTrend.vue | 历史趋势（ECharts，多指标：光照/温度/湿度/功率） |
| `/alarms` | AlarmList.vue | 告警管理（仅 admin） |
| `/chat` | ChatView.vue | AI 智能问答（DeepSeek） |

状态管理：`stores/authStore.js`（认证）、`store/device.js`、`store/sensor.js`（设备/传感器 Pinia stores）。

## Mock 数据发生器

`tools/mock-sender/` 是基于真实时钟 + 太阳位置模型的 Python 模拟器：
- `data_generator.py`：使用 Spencer(1971) 太阳赤纬公式 + Beer-Lambert 大气光程模型生成符合物理规律的光照数据，包含昼夜变化、云量随机游走、温度季节联动
- 重庆默认坐标：lat=29.5, lon=106.5, UTC+8
- `app.py`：Flask Web 管理界面（端口 5000），可动态增删传感器、调整上报间隔
- `config.json`：传感器配置持久化文件

## MQTT Topic 设计

| Topic | 方向 | 说明 |
|---|---|---|
| `streetlight/sensor/{sensorId}/data` | 上行 | 传感器数据上报 |
| `streetlight/sensor/{sensorId}/status` | 上行 | 传感器心跳 |
| `streetlight/sensor/register` | 上行 | 传感器自动注册 |
| `streetlight/sensor/unregister` | 上行 | 传感器注销 |
| `streetlight/{deviceId}/control/response` | 上行 | 设备控制响应 |
| `streetlight/{deviceId}/control` | 下行 | 后端下发控制指令 |
| `streetlight/{deviceId}/config/frequency` | 下行 | 调整上报频率 |
