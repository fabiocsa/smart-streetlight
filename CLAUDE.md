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
python app.py                          # 启动 Web 管理界面（端口 5050）+ MQTT 模拟器

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
| Entity | `entity/` | JPA 实体。Device 含 `@Formula` 计算的 `sensorCount`（从 device_sensor 表子查询）；SensorData 含 JSON 列 `data_json`；默认 `Device.lightStatus = "unknown"` |
| MQTT | `mqtt/` | `MqttClientManager`（连接/订阅/主题路由）、`MqttMessageHandler`（消息分发）、`MqttPublishService`（发送指令） |
| WebSocket | `websocket/` | `WebSocketHandler`，端点 `/ws/monitor`，广播 SENSOR_DATA / DEVICE_STATUS / NEW_ALARM / CONTROL_RESULT |
| Config | `config/` | MQTT、WebSocket、CORS、DeepSeek、Embedding、MaxKB、RestTemplate、WebMVC |
| Security | `security/` | JWT 鉴权 + `AuthInterceptor` |
| Common | `common/` | `Result`（统一响应体）、`GlobalExceptionHandler`（含 `MethodArgumentNotValidException`→400, `HttpMessageNotReadableException`→400, `MethodArgumentTypeMismatchException`→400, `DataIntegrityViolationException`→409, `BusinessException`, `Exception`→500）、`BusinessException` |

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
| 告警管理 & 告警规则 | ✅ | ✅ | ❌ |
| AI 智能问答 | ✅ | ✅ | ✅ |
| 历史趋势 | ✅ | ✅ | ✅ |

权限在 `AuthInterceptor.preHandle()` 中按 URI 路径匹配校验。前端路由通过 `meta.roles` 字段控制菜单可见性，`router.beforeEach` 做二次校验。
前端 store：`authStore.isAdmin`、`authStore.isOperator`、`authStore.isMunicipal`。

### 关键枚举

| 枚举 | 位置 | 可选值 |
|---|---|---|
| `ControlMode` | `enums/` | `auto`（自动联动）, `manual`（手动控制） |
| `LightStatus` | `enums/` | `on`（开灯）, `off`（关灯）, `unknown`（未知 — 设备离线或新创建时） |
| `DeviceStatus` | `enums/` | `online`, `offline` |
| `AlarmType` | `enums/` | `OFFLINE`, `SENSOR_ABNORMAL`, `VOLTAGE_ABNORMAL`, `TEMPERATURE_HIGH`, `POWER_HIGH` |
| `AlarmSeverity` | `enums/` | `INFO`, `WARNING`, `CRITICAL` |
| `AlarmStatus` | `enums/` | `PENDING`（待处理）, `RESOLVED`（已解除） |
| `AssignmentMode` | `enums/` | `manual`（手动指派）, `auto`（自动轮流分配） |

### 速查：关键 Service (v5)

| Service | 职责 |
|---|---|
| `SensorDataServiceImpl.saveAndAutoControl()` | **核心方法**：保存数据 → 更新心跳 + 设备恢复在线自动解除告警 → 告警检测（按 sensorType 分流） → `SELECT ... FOR UPDATE` 悲观锁读设备 → **unknown 状态恢复**（光照 < 开灯阈值则设 on，否则设 off） → 多传感器策略（single/average） → 迟滞双阈值联动决策 → 下发 MQTT cmd → WebSocket 推送状态 |
| `ControlServiceImpl` | 设备控制（开关灯/模式切换/阈值/传感器策略），通过 light 传感器 cmd 主题下发 |
| `DashboardService` | 仪表盘数据聚合。`getLatestSensorData()` 按 deviceId 合并多种传感器数据；`getSensorTrend()` 通过 `sensorTypeForMetric()` 动态映射 sensorType |
| `AlarmServiceImpl` | 5 种告警类型（OFFLINE/VOLTAGE_ABNORMAL/TEMPERATURE_HIGH/POWER_HIGH/SENSOR_ABNORMAL），自动创建/解除。电压/温度/功率告警仅对相应的 sensorType 触发 |
| `SensorRegistrationService` | 传感器自动注册/注销，`autoRegisterFromData()` 使用 `REQUIRES_NEW` 事务 |
| `HeartbeatChecker.checkHeartbeats()` | `@Scheduled(fixedRate=5000)` 每 5 秒检查心跳超时，标记离线并设 `lightStatus = "unknown"`，生成 OFFLINE 告警 |
| `ChatServiceImpl` | DeepSeek API 多轮对话 |
| `RagServiceImpl` | RAG 检索增强：向量检索 → context 注入 → DeepSeek 生成回答 |
| `EmbeddingService` / `OpenAiEmbeddingServiceImpl` | 文本向量化（智谱 embedding-2，1024 维），用于知识库检索 |
| `VectorStore` | 向量持久化 + 余弦相似度检索（JSON 文件存储） |
| `ToolExecutor` | AI Function Calling 执行器：将工具调用请求映射到 Spring Bean 方法 |
| `AlarmRuleServiceImpl` | 告警规则 CRUD + 启用/禁用 |
| `HandlerServiceImpl` | 告警处理人 CRUD + 指派模式切换 + 自动轮流分配 |
| `MaxKBClient` | 外部知识库（MaxKB）集成客户端 |
| `KnowledgeBaseInitializer` | 应用启动时初始化向量存储 |

### 循环依赖处理

`MqttMessageHandler` ↔ `MqttClientManager` 和 `MqttMessageHandler` → `DeviceService` → `SensorRepository` → … → `MqttPublishService` → `MqttClientManager` 形成间接循环。通过 `@Lazy` 注解打破：`MqttMessageHandler` 构造函数中注入 `DeviceService` 和 `MqttClientManager` 时使用 `@Lazy`，Spring 注入代理对象，首次调用时才解析实际 bean。

### 批量操作事务隔离

`AlarmServiceImpl` 和 `ControlServiceImpl` 的批量方法（`batchResolve`、`batchUpdateResolvedBy`、`sendBatchControlCommand`）使用 `@Lazy @Autowired` 自注入模式：注入自身代理 `private XxxServiceImpl self`，通过 `self.method()` 而非 `this.method()` 调用，确保每个子项在独立事务中运行，单个失败不影响其余子项。批量方法自身不加 `@Transactional`。

## 前端架构

### 主题系统（暗色/亮色双模）

- `styles/global.css` — 全局 CSS 变量 + Element Plus 全套暗色覆盖。通过 `<html data-theme="dark|light">` 切换，所有颜色属性使用 `* { transition: ... }` 平滑过渡
- `composables/useTheme.js` — `toggle()` 切换、`isDark` 状态、`localStorage` 持久化、监听系统 `prefers-color-scheme`
- 所有组件和页面中使用 `var(--text-primary)` / `var(--bg-card)` / `var(--blue)` 等 CSS 变量，**禁止硬编码颜色**
- Element Plus 按钮配色已全局重写（hover 上浮 + 发光阴影），通过 `--el-button-*` 变量控制

### 导航栏（NavBar）

`components/NavBar.vue` — 顶部固定导航栏，替代旧版侧边栏：
- **顶部透明**：scrollY=0 时完全透明融入背景，滚动后 `has-bg` class 触发半透明毛玻璃
- **智能显隐**：向下滚动隐藏，向上滚动/鼠标靠近顶部 60px/在页面顶部时显示
- 菜单：仪表盘、设备、传感器、趋势、告警（admin/operator）、AI问答
- 右侧：WS 状态灯、时钟、主题切换按钮（太阳↔月亮旋转动画）、告警铃铛、用户头像下拉

### 路由与权限

| 路由 | 组件 | 说明 | 权限 |
|---|---|---|---|
| `/login` | Login.vue | 登录页 | 公开 |
| `/dashboard` | Dashboard.vue | 仪表盘总览 | admin, operator, municipal |
| `/devices` | DeviceList.vue | 设备管理 CRUD | admin, operator, municipal |
| `/devices/:id` | DeviceDetail.vue | 设备详情 + 传感器绑定 | admin, operator, municipal |
| `/sensors` | SensorList.vue | 传感器管理 | admin, operator, municipal |
| `/light-trend` | LightTrend.vue | 历史趋势（ECharts 多指标） | admin, operator, municipal |
| `/alarms` | AlarmList.vue | 告警管理 | admin, operator |
| `/chat` | ChatView.vue | AI 智能问答 | admin, operator, municipal |

路由使用 `createWebHashHistory`（hash 模式），`router.beforeEach` 守卫检查 token 和角色权限。

状态管理：`stores/authStore.js`（登录/登出/角色）、`stores/chatStore.js`（AI 对话历史）、`store/device.js`（设备 CRUD）、`store/sensor.js`（传感器管理）。注意：存在 `stores/` 和 `store/` 两个独立目录，均为 Pinia store，命名未统一。

通用工具函数：`utils/common.js`（`formatTime`、`typeLabel`、`sensorTypeTag`、`debounce`、`clone`、`lightStatusLabel`、`lightStatusTagType`、`isLightOn`）。灯光相关函数统一处理离线/unknown 状态，前端各组件应使用这些工具函数而非直接判断 `lightStatus === 'on'`。

地图组件：`DeviceMap.vue` 使用高德地图 JSAPI Loader（`@amap/amap-jsapi-loader`）在仪表盘展示设备位置分布。支持 `focusDeviceId` prop（外部触发地图聚焦）和 `select-device` emit（点击标记通知父组件）。暗色模式下通过 CSS `filter: invert(0.88) hue-rotate(180deg)` 反转地图颜色。

### API 请求层

`api/request.js` — axios 实例，`timeout: 15000`：
- **错误去重**：`showErrorOnce()` 3 秒内相同消息不重复弹
- **超时静默**：`ECONNABORTED` / `ERR_CANCELED` 不弹全局提示，由调用方自行处理
- **控制 API**：`sendControl` / `setControlMode` 等单独设 `timeout: 8000`
- 401 用 `router.push('/login')` 而非 `window.location.hash`

### WebSocket 实时数据

前端在 `Dashboard.vue`、`DeviceList.vue`、`ControlPanel.vue` 建立 WebSocket 连接到 `ws://localhost:8080/ws/monitor`（开发环境通过 Vite proxy）。收到 JSON 消息后根据 `type` 字段分发：

**重连策略**：指数退避（2s→4s→8s…最大60s），最多重试 10 次，成功后重置计数器。组件卸载时必须清理 WS 连接和重连 timer（`onBeforeUnmount`）。

| type | 触发动作 |
|---|---|
| `SENSOR_DATA` | 更新仪表盘实时数据面板（光照/温度/湿度/功率） |
| `DEVICE_STATUS` | 更新设备在线/离线状态指示 |
| `NEW_ALARM` | 弹出告警通知 + 刷新告警列表 |
| `CONTROL_RESULT` | 显示控制指令执行结果通知（含 `source` 字段，前端据此判断是否切换模式） |

### 控制流注意事项

- **CONTROL_RESULT.source**：后端 `updateControlResult` 从 ControlLog 提取 source 并通过 WebSocket 推送。前端 `handleControlResult` 检查 `data.source !== 'auto'` 才切换为手动模式，自动联动不改变模式
- **DEVICE_STATUS 冲突**：手动操作期间（`sending=true`），DEVICE_STATUS 的 lightStatus 更新被阻塞，等 CONTROL_RESULT 确认
- **toggleLight**：HTTP 成功后不立即改 controlMode，等 CONTROL_RESULT（3s fallback 兜底）
- **controlMode 同步**：ControlPanel 中使用 writable computed（`get: props.device.controlMode, set: 写回 props`），不再手动 watch 同步，消除闪烁
- **亮度控制已移除**：硬件（BearPi GPIO）不支持 PWM，前端已删除亮度滑块

## AI 智能问答（DeepSeek）

后端通过 RestTemplate 调用 DeepSeek API（`api.deepseek.com`），配置在 `application.yml` 的 `deepseek` 段：
- 默认模型 `deepseek-chat`，可通过环境变量覆盖
- `system-prompt` 注入角色：你是一个智慧路灯管理系统的智能助手…
- 支持多轮对话：`ChatMessage` 表存储历史消息，按 `ChatSession` 分组
- 前端 `/chat` 页面实现流式打字机效果（非真实 SSE 流，前端模拟逐字显示）

## RAG 知识库 & 告警处理人

### RAG 增强问答

项目通过 Spring AI + 嵌入模型实现了基于知识库的 RAG（检索增强生成）问答：

- **嵌入服务**：`EmbeddingService` / `OpenAiEmbeddingServiceImpl` 使用智谱 `embedding-2` 模型（1024 维，OpenAI 兼容接口），配置在 `application.yml` 的 `embedding` 段，支持环境变量覆盖（`EMBEDDING_API_BASE`、`EMBEDDING_API_KEY`、`EMBEDDING_MODEL`）
- **向量存储**：`VectorStore` 将文档切片向量持久化到 JSON 文件（`data/knowledge-vector-store.json`），启动时自动加载，支持简单的余弦相似度检索
- **知识文件**：`KnowledgeController`（`/api/knowledge`）支持上传 PDF/Word/txt 文件，后端使用 PDFBox 3.0.3 + POI 5.3.0 解析文本后切片向量化存入 `VectorStore`
- **RAG 问答**：`RagController`（`POST /api/rag/ask`）接收用户问题 → `RagServiceImpl` 检索相关知识库片段 → 拼接为 context 注入 system prompt → 调用 DeepSeek API 生成回答
- **工具调用**：`ToolExecutor` 支持 Function Calling 模式，将 AI 对工具调用的请求（如查设备状态、查告警列表）映射到对应的 Spring Bean 方法执行
- **MaxKB 集成**：`MaxKBClient` + `MaxKBProperties` 为可选的外部知识库集成预留接口
- **前端**：`ChatView.vue` 侧边栏包含 `KnowledgePanel.vue`（文件上传/列表管理），`KnowledgeBaseInitializer` 在应用启动时初始化向量存储

依赖：Spring AI 1.0.0-M4（Milestone 仓库），`spring-ai-openai-spring-boot-starter` 用于嵌入调用。注意 `application.yml` 中排除了 `OpenAiAutoConfiguration`（Spring AI 的 OpenAI 自动配置）以避免冲突。

### 告警处理人系统

告警可指派给处理人（`HandlerList` 实体），支持两种模式（`AssignmentMode` 枚举）：

- **手动模式** (`manual`)：管理员从处理人列表中手动选择指派
- **自动模式** (`auto`)：新告警触发时，系统轮流分配给启用的处理人

前端 `AlarmList.vue` 集成处理人指派 UI。`HandlerController`（`/api/handlers`）提供处理人 CRUD 和模式切换接口。

### 告警规则引擎

`AlarmRule` 实体支持为不同传感器类型配置告警阈值规则，`AlarmRuleController`（`/api/alarm-rules`）提供 CRUD + 启用/禁用切换。告警阈值（电压/温度/功率）可通过 `AlarmController` 的配置接口动态调整，替代硬编码阈值。

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

## 真实硬件（鸿蒙 BearPi-HM_Nano）

`hard/sample/my_first_app/` — 基于 OpenHarmony 的智慧路灯设备端 C 代码：

- **硬件平台**：BearPi-HM_Nano + E53_SC1 扩展板（BH1750 光照传感器 + LED）
- **传感器**：BH1750 I2C 光照传感器（0x23 地址），连续高分辨率模式
- **MQTT**：连接远程 EMQX Broker，topic 设计同后端。上报 `illuminance` + `lightStatus`（设备端 `g_light_on` 变量，反映 LED 实际状态）
- **上报周期**：数据 5s/次，心跳 30s/次（每 6 次数据发一次）
- **控制指令**：订阅 cmd 主题，匹配 `command:on/off` 控制 LED 开关，执行后发 cmd/response
- **遗嘱消息**：MQTT connect 时设置 will，断连时自动发 offline 状态到 status 主题
- **注意**：设备端 `g_light_on` 初始为 0（关灯），重启后 LED 灭
- 源码：`hello_world.c`（主逻辑）+ `src/wifi_connect.c`（WiFi 连接）+ `include/wifi_connect.h`

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

### 依赖要点

- **Spring AI**：通过 Spring Milestones 仓库引入 `1.0.0-M4` 版本（BOM `spring-ai-bom`），使用 `spring-ai-openai-spring-boot-starter` 做嵌入调用。注意 `application.yml` 中排除了 `OpenAiAutoConfiguration` 避免自动配置冲突
- **文档解析**：PDFBox 3.0.3（PDF）+ POI 5.3.0（Word）用于知识库文件上传时的文本提取
- **JVM 构建参数**：`-Xmx512m -Xms256m`（在 pom.xml 中配置）

### 安全注意事项

**API 密钥已硬编码在 `application.yml` 中**（DeepSeek API Key 和智谱 Embedding API Key），不适合生产环境。建议迁移到环境变量或密钥管理服务。Embedding 配置已支持环境变量覆盖（`EMBEDDING_API_KEY` 等），但 DeepSeek 密钥尚未支持。

### 无测试覆盖

当前项目没有单元测试或集成测试。`pom.xml` 引入了 `spring-boot-starter-test` 但 `src/test/` 目录为空。添加新功能时建议从零开始搭建测试基础设施。

### 启动顺序

1. 确保远程 MySQL 和 EMQX Broker 可访问
2. 启动后端（Hibernate 自动建表/更新表结构）
3. 启动前端
4. （可选）启动 mock-sender 进行数据模拟
