# 智慧路灯系统 - API接口文档

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
**请求体：**
```json
{
  "displayName": "光照传感器A-更新",
  "dataTopic": "streetlight/SL-001/sensor/data",
  "reportFrequency": 10,
  "enabled": true,
  "configJson": "{\"min\": 0, \"max\": 1000}"
}
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
**请求体：**
```json
{
  "reportFrequency": 3
}
```

### 2.7 同步传感器配置到模拟器
```
POST /devices/{deviceId}/sensors/sync-to-mock
```
**说明：** 将当前设备的传感器配置通过 MQTT 下发到模拟器，通知其调整行为。

**响应：**
```json
{
  "message": "传感器配置已同步到模拟器",
  "syncedCount": 2
}
```

---

## 3. 传感器数据

### 3.1 获取设备最新传感器数据
```
GET /devices/{deviceId}/sensor-data/latest
```
**响应：**
```json
{
  "deviceId": "SL-001",
  "lightIntensity": 125.5,
  "reportedAt": "2026-07-01T10:00:00"
}
```

### 3.2 获取历史传感器数据
```
GET /devices/{deviceId}/sensor-data?start=2026-07-01T00:00:00&end=2026-07-01T23:59:59
```
**响应：**
```json
[
  {
    "id": 1,
    "deviceId": "SL-001",
    "lightIntensity": 125.5,
    "reportedAt": "2026-07-01T10:00:00"
  }
]
```

### 3.3 获取聚合统计
```
GET /devices/{deviceId}/sensor-data/stats?start=...&end=...
```
**响应：**
```json
{
  "avg": 120.5,
  "max": 850.0,
  "min": 5.0,
  "count": 1440
}
```

---

## 3. 设备控制

### 3.1 下发控制指令
```
POST /devices/{deviceId}/control
```
**请求体：**
```json
{
  "command": "on",
  "source": "manual"
}
```
**响应：**
```json
{
  "command": "on",
  "source": "manual",
  "result": "success",
  "createdAt": "2026-07-01T10:00:01"
}
```

### 3.2 设置控制模式
```
PUT /devices/{id}/control-mode
```
**请求体：**
```json
{
  "controlMode": "auto"
}
```

### 3.3 设置光照阈值
```
PUT /devices/{id}/threshold
```
**请求体：**
```json
{
  "thresholdOn": 50.0,
  "thresholdOff": 100.0
}
```

---

## 4. 告警管理

### 4.1 获取告警列表
```
GET /alarms?status=pending&type=offline&page=0&size=20
```
**响应：**
```json
{
  "content": [
    {
      "id": 1,
      "deviceId": "SL-001",
      "alarmType": "offline",
      "content": "设备 SL-001 已离线超过30秒",
      "severity": "warning",
      "status": "pending",
      "createdAt": "2026-07-01T10:00:00"
    }
  ],
  "totalElements": 1,
  "totalPages": 1
}
```

### 4.2 处理告警
```
PUT /alarms/{id}/resolve
```
**请求体：**
```json
{
  "resolvedBy": "管理员张三"
}
```

---

## 5. Dashboard统计

### 5.1 获取总览统计
```
GET /dashboard/stats
```
**响应：**
```json
{
  "totalDevices": 10,
  "onlineDevices": 8,
  "offlineDevices": 2,
  "lightsOn": 3,
  "lightsOff": 7,
  "pendingAlarms": 2,
  "todayAlarms": 5
}
```

---

## 6. WebSocket接口

### 连接地址
```
ws://localhost:8080/ws/monitor
```

### 推送消息格式
**传感器实时数据：**
```json
{
  "type": "SENSOR_DATA",
  "deviceId": "SL-001",
  "data": {
    "lightIntensity": 125.5,
    "reportedAt": "2026-07-01T10:00:00"
  }
}
```

**设备状态变更：**
```json
{
  "type": "DEVICE_STATUS",
  "deviceId": "SL-001",
  "data": {
    "status": "offline",
    "lightStatus": "off"
  }
}
```

**新告警推送：**
```json
{
  "type": "NEW_ALARM",
  "data": {
    "id": 1,
    "deviceId": "SL-001",
    "alarmType": "offline",
    "content": "设备 SL-001 已离线超过30秒",
    "severity": "warning"
  }
}
```

**控制结果反馈：**
```json
{
  "type": "CONTROL_RESULT",
  "deviceId": "SL-001",
  "data": {
    "command": "on",
    "result": "success"
  }
}
```
