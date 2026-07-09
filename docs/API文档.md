# 智慧路灯系统 - API接口文档 (v2)

> 基础URL: `http://localhost:8080/api`
> 数据格式: JSON

---

## 1. 设备管理

### 1.1 获取所有设备列表
```
GET /devices
```
**响应：**
```json
[
  {
    "id": 1,
    "name": "路灯A-01",
    "deviceId": "SL-001",
    "status": "online",
    "thresholdOn": 50.0,
    "thresholdOff": 100.0,
    "lightStatus": "on",
    "controlMode": "auto",
    "location": "校门口",
    "lastHeartbeat": "2026-07-01T10:00:00",
    "createdAt": "2026-07-01T09:00:00"
  }
]
```

### 1.2 添加设备
```
POST /devices
```
**请求体：**
```json
{
  "name": "路灯A-01",
  "deviceId": "SL-001",
  "location": "校门口"
}
```
**响应：** 201 Created + 设备对象

### 1.3 获取单个设备
```
GET /devices/{id}
```

### 1.4 删除设备
```
DELETE /devices/{id}
```
**响应：** 204 No Content

### 1.5 更新设备
```
PUT /devices/{id}
```

---

## 2. 传感器管理

### 2.1 获取设备的所有传感器
```
GET /devices/{deviceId}/sensors
```
**响应：**
```json
[
  {
    "id": 1,
    "deviceId": "SL-001",
    "sensorType": "light",
    "displayName": "光照传感器A",
    "dataTopic": "streetlight/SL-001/sensor/data",
    "reportFrequency": 5,
    "enabled": true,
    "configJson": "{\"min\": 0, \"max\": 800}",
    "createdAt": "2026-07-01T09:00:00"
  }
]
```

### 2.2 获取单个传感器
```
GET /devices/{deviceId}/sensors/{id}
```

### 2.3 绑定传感器到设备
```
POST /devices/{deviceId}/sensors
```
**请求体：**
```json
{
  "sensorType": "light",
  "displayName": "光照传感器A",
  "dataTopic": "streetlight/SL-001/sensor/data",
  "reportFrequency": 5,
  "configJson": "{\"min\": 0, \"max\": 800}"
}
```
**响应：** 201 Created + 传感器对象

### 2.4 更新传感器配置
```
PUT /devices/{deviceId}/sensors/{id}
```

### 2.5 解绑传感器
```
DELETE /devices/{deviceId}/sensors/{id}
```
**响应：** 204 No Content

### 2.6 调整传感器上报频率
```
PUT /devices/{deviceId}/sensors/{id}/frequency
```

### 2.7 同步传感器配置到模拟器
```
POST /devices/{deviceId}/sensors/sync-to-mock
```

---

## 3. 传感器数据 (v2 — JSON 多维数据)

### 3.1 获取设备最新传感器数据
```
GET /devices/{deviceId}/sensor-data/latest
GET /devices/{deviceId}/sensor-data/latest/{sensorType}
```
**响应 (v2)：**
```json
{
  "id": 1,
  "deviceId": "SL-001",
  "sensorId": 1,
  "sensorType": "light",
  "dataJson": "{\"lightIntensity\":125.5,\"temperature\":28.3,\"humidity\":65.0,\"voltage\":226.0,\"power\":65.0,\"cloudCover\":0.3}",
  "reportedAt": "2026-07-01T10:00:00",
  "createdAt": "2026-07-01T10:00:01"
}
```
`dataJson` 字段包含所有传感器指标。前端可通过 `JSON.parse(dataJson)` 获取完整 Map。

### 3.2 获取历史传感器数据
```
GET /devices/{deviceId}/sensor-data?start=...&end=...&sensorType=light
```
- `sensorType` (可选): 筛选特定传感器类型
- 响应为 SensorData 数组，每条记录包含完整 `dataJson`

### 3.3 获取聚合统计（支持任意指标）
```
GET /devices/{deviceId}/sensor-data/stats?metric=temperature&start=...&end=...
```
**参数：**
- `metric` (默认 `lightIntensity`): 指标名，如 `temperature`, `humidity`, `power`, `voltage`

**响应：**
```json
{
  "field": "temperature",
  "avg": 28.5,
  "max": 35.2,
  "min": 22.1,
  "count": 1440
}
```

---

## 4. 设备控制

### 4.1 下发控制指令
```
POST /devices/{deviceId}/control
```

### 4.2 设置控制模式
```
PUT /devices/{id}/control-mode
```

### 4.3 设置光照阈值
```
PUT /devices/{id}/threshold
```

---

## 5. 告警管理

### 5.1 获取告警列表
```
GET /alarms?status=pending&type=offline&page=0&size=20
```

### 5.2 处理告警
```
PUT /alarms/{id}/resolve
```

---

## 6. Dashboard统计

### 6.1 获取总览统计
```
GET /dashboard/stats
```

### 6.2 获取设备状态分布
```
GET /dashboard/device-status-distribution
```

### 6.3 获取各设备最新传感器数据
```
GET /dashboard/latest-sensor-data
GET /dashboard/latest-sensor-data/{sensorType}
```
响应中每条记录包含 `data` (Map 格式) 和 `lightIntensity` (兼容字段)。

### 6.4 传感器趋势（新接口）
```
GET /dashboard/sensor-trend?deviceId=SL-001&metric=temperature&range=24h
```
**参数：**
| 参数 | 默认值 | 说明 |
|------|--------|------|
| `deviceId` | 全部设备 | 可选，指定单个设备 |
| `metric` | `lightIntensity` | 指标名: lightIntensity / temperature / humidity / power |
| `range` | `24h` | 时间范围: 24h / 7d / 30d |

**响应：**
```json
{
  "labels": ["00:00", "01:00", ...],
  "values": [0, 0, ..., 125.5, ...],
  "metric": "temperature",
  "range": "24h",
  "granularity": "hour",
  "deviceId": "SL-001",
  "avg": 28.5, "max": 35.2, "min": 22.1,
  "totalPoints": 288,
  "demoMode": false,
  "lastDataTime": "2026-07-01T10:00:00"
}
```

### 6.5 光照趋势（旧接口，保留兼容）
```
GET /dashboard/light-trend?deviceId=SL-001&range=24h
```
内部重定向到 `/sensor-trend?metric=lightIntensity`，行为不变。

### 6.6 告警统计
```
GET /dashboard/alarm-stats
```

### 6.7 近期数据
```
GET /dashboard/recent-alarms
GET /dashboard/recent-controls
```

---

## 7. WebSocket接口

### 连接地址
```
ws://localhost:8080/ws/monitor
```

### 推送消息格式

**传感器实时数据 (v2 — 多字段版)：**
```json
{
  "type": "SENSOR_DATA",
  "deviceId": "SL-001",
  "sensorType": "light",
  "data": {
    "lightIntensity": 125.5,
    "illuminance": 125.5,
    "temperature": 28.3,
    "humidity": 65.0,
    "voltage": 226.0,
    "power": 65.0,
    "cloudCover": 0.3,
    "status": "OFF"
  },
  "reportedAt": "2026-07-01T10:00:00"
}
```

**设备状态变更：**
```json
{
  "type": "DEVICE_STATUS",
  "deviceId": "SL-001",
  "data": { "status": "offline", "lightStatus": "off" }
}
```

**新告警推送：**
```json
{
  "type": "NEW_ALARM",
  "data": {
    "id": 1, "deviceId": "SL-001",
    "alarmType": "offline", "content": "设备 SL-001 已离线超过30秒",
    "severity": "warning"
  }
}
```

**控制结果反馈：**
```json
{
  "type": "CONTROL_RESULT",
  "deviceId": "SL-001",
  "data": { "command": "on", "result": "success" }
}
```
