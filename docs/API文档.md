# 智慧路灯系统 - API接口文档 (v5)

> 基础URL: `http://localhost:8080/api`
> 数据格式: JSON
> 鉴权: JWT Bearer Token (Authorization header)

---

## 1. 认证

### 1.1 登录
```
POST /api/auth/login
```
**请求体：**
```json
{ "username": "admin", "password": "admin123" }
```
**响应：**
```json
{ "code": 200, "data": { "token": "xxx", "user": { "username": "admin", "role": "admin" } } }
```

---

## 2. 设备管理

### 2.1 获取所有设备列表
```
GET /api/devices
```
**响应：**
```json
[{
  "id": 1, "name": "路灯A-01", "deviceId": "SL-001",
  "status": "online", "thresholdOn": 50.0, "thresholdOff": 100.0,
  "lightStatus": "on", "controlMode": "auto",
  "sensorStrategy": "single", "primarySensorId": 1,
  "location": "校门口", "lastHeartbeat": "2026-07-01T10:00:00"
}]
```

### 2.2 添加设备
```
POST /api/devices
```
```json
{ "name": "路灯A-01", "deviceId": "SL-001", "location": "校门口" }
```

### 2.3 获取/更新/删除单个设备
```
GET    /api/devices/{id}
PUT    /api/devices/{id}
DELETE /api/devices/{id}
```

### 2.4 批量删除设备
```
POST /api/devices/batch-delete
```
```json
{ "ids": [1, 2, 3] }
```

### 2.5 设备绑定/解绑传感器
```
POST /api/devices/{id}/bind-sensor
POST /api/devices/{id}/unbind-sensor/{sensorId}
```

### 2.6 获取设备已绑定传感器
```
GET /api/devices/{id}/sensors
```

### 2.7 批量设置阈值
```
PUT /api/devices/batch-threshold
```
```json
{ "deviceIds": ["SL-001", "SL-002"], "thresholdOn": 50, "thresholdOff": 100 }
```

---

## 3. 传感器管理

### 3.1 获取所有传感器
```
GET /api/sensors
```

### 3.2 获取未绑定传感器
```
GET /api/sensors/unbound
```

### 3.3 获取/创建/更新/删除传感器
```
GET    /api/sensors/{id}
POST   /api/sensors
PUT    /api/sensors/{id}
DELETE /api/sensors/{id}
```

### 3.4 调整传感器上报频率
```
PUT /api/sensors/{id}/frequency
```
```json
{ "frequency": 10 }
```

---

## 4. 传感器数据 (v5 — 按类型分离)

### 4.1 获取设备最新传感器数据
```
GET /api/devices/{deviceId}/sensor-data/latest
GET /api/devices/{deviceId}/sensor-data/latest/{sensorType}
```
**响应 (v5)：**
```json
{
  "id": 1, "deviceId": "SL-001", "sensorId": 1,
  "sensorType": "light",
  "dataJson": "{\"illuminance\":125.5,\"lightIntensity\":125.5,\"cloudCover\":0.3,\"status\":\"OFF\"}",
  "reportedAt": "2026-07-01T10:00:00"
}
```
每种类型的 `dataJson` 字段只包含该类型的字段（详见系统设计.md §3）。

### 4.2 获取历史传感器数据
```
GET /api/devices/{deviceId}/sensor-data?start=...&end=...&sensorType=light
```

### 4.3 获取聚合统计（支持任意指标）
```
GET /api/devices/{deviceId}/sensor-data/stats?metric=temperature&start=...&end=...
```
| 参数 | 默认值 | 说明 |
|------|--------|------|
| `metric` | `lightIntensity` | 指标名: lightIntensity / temperature / humidity / power / voltage |

**响应：**
```json
{ "field": "temperature", "avg": 28.5, "max": 35.2, "min": 22.1, "count": 1440 }
```

---

## 5. 设备控制

### 5.1 下发控制指令
```
POST /api/devices/{deviceId}/control
```
```json
{ "command": "on" }
```
后端查找设备绑定的 sensor，通过 `streetlight/sensor/{sensorId}/cmd` 下发。

### 5.2 设置控制模式
```
PUT /api/devices/{id}/control-mode
```
```json
{ "controlMode": "auto" }
```

### 5.3 设置光照阈值
```
PUT /api/devices/{id}/threshold
```
```json
{ "thresholdOn": 50, "thresholdOff": 100 }
```

### 5.4 设置传感器策略
```
PUT /api/devices/{id}/sensor-strategy
```
```json
{ "sensorStrategy": "average" }
```
可选值: `single`（指定主传感器）、`average`（取所有 bound sensor 平均值）。

---

## 6. 告警管理

### 6.1 获取告警列表
```
GET /api/alarms?status=pending&type=offline&page=0&size=20
```
| 参数 | 说明 |
|------|------|
| `status` | PENDING / RESOLVED |
| `type` | OFFLINE / SENSOR_ABNORMAL / VOLTAGE_ABNORMAL / TEMPERATURE_HIGH / POWER_HIGH |
| `severity` | INFO / WARNING / CRITICAL |

### 6.2 处理告警
```
PUT /api/alarms/{id}/resolve
```

### 6.3 告警规则管理（仅 admin）
```
GET    /api/alarm-rules
PUT    /api/alarm-rules/{id}
```

---

## 7. Dashboard统计

### 7.1 获取总览统计
```
GET /api/dashboard/stats
```
```json
{
  "totalDevices": 10, "onlineDevices": 8, "offlineDevices": 2,
  "lightsOn": 3, "lightsOff": 7,
  "pendingAlarms": 2, "todayControls": 15
}
```

### 7.2 获取设备状态分布
```
GET /api/dashboard/device-status-distribution
```

### 7.3 获取各设备最新传感器数据（多传感器合并）
```
GET /api/dashboard/latest-sensor-data
```
按 deviceId 分组，合并 light/temperature/humidity/power 多种传感器数据为一行。

### 7.4 传感器趋势（多指标）
```
GET /api/dashboard/sensor-trend?deviceId=SL-001&metric=temperature&range=24h
```
| 参数 | 默认值 | 说明 |
|------|--------|------|
| `deviceId` | 全部设备 | 可选，指定单个设备 |
| `metric` | `lightIntensity` | 指标名 |
| `range` | `24h` | 24h / 7d / 30d |

后端通过 `sensorTypeForMetric()` 动态映射指标到传感器类型：
- `lightIntensity` / `illuminance` → light
- `temperature` → temperature
- `humidity` → humidity
- `power` / `voltage` → power

### 7.5 告警统计 & 近期数据
```
GET /api/dashboard/alarm-stats
GET /api/dashboard/recent-alarms
GET /api/dashboard/recent-controls
```

---

## 8. WebSocket接口

### 连接地址
```
ws://localhost:8080/ws/monitor
```

### 推送消息格式

**传感器实时数据 (SENSOR_DATA) — v5 按类型分离：**
```json
{
  "type": "SENSOR_DATA",
  "deviceId": "SL-001",
  "sensorType": "light",
  "data": { "illuminance": 125.5, "lightIntensity": 125.5, "cloudCover": 0.3, "status": "OFF" },
  "reportedAt": "2026-07-01T10:00:00"
}
```

**设备状态变更 (DEVICE_STATUS)：**
```json
{
  "type": "DEVICE_STATUS",
  "deviceId": "SL-001",
  "data": { "status": "offline", "lightStatus": "off" }
}
```

**新告警推送 (NEW_ALARM)：**
```json
{
  "type": "NEW_ALARM",
  "data": {
    "id": 1, "deviceId": "SL-001",
    "alarmType": "OFFLINE", "content": "设备 SL-001 已离线超过30秒",
    "severity": "WARNING"
  }
}
```

**控制结果反馈 (CONTROL_RESULT)：**
```json
{
  "type": "CONTROL_RESULT",
  "deviceId": "SL-001",
  "data": { "command": "on", "result": "success", "sensorId": 1 }
}
```

---

## 9. AI 智能问答

### 9.1 发送消息
```
POST /api/chat/send
```
```json
{ "message": "路灯不亮怎么办？", "sessionId": "xxx" }
```

### 9.2 获取会话历史
```
GET /api/chat/history?sessionId=xxx
```

### 9.3 新建会话 / 会话列表
```
POST /api/chat/session
GET  /api/chat/sessions
```
