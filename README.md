# 智慧路灯系统 - Smart Streetlight

> 重庆交通大学 × 中软国际 集中实训项目  
> 基于「端-智-云一体化」架构的智能路灯节能控制系统

---

## 📋 项目简介

智慧路灯系统通过实时监测环境光照强度，根据光照阈值自动控制路灯开关，同时支持手动远程控制、设备状态监控、离线告警、历史数据可视化及智能问答等功能，实现路灯的智能节能管理。

### 系统架构

```
鸿蒙端/模拟器 ──MQTT──→ EMQX Broker ──MQTT──→ Spring Boot后端 ──WebSocket──→ Vue 3前端
```

---

## 🧑‍💻 团队分工

| 成员 | 角色 | 主要职责 |
|------|------|---------|
| **王军** | PM | 进度管理、文档编写、Mock数据发生器 |
| **范李阳** | 前端 | Dashboard总览、实时监测、历史趋势 |
| **赖筱寒** | 前端 | 设备控制、告警管理、设备管理、智能问答 |
| **邓德银** | 后端 | 设备管理API、MQTT客户端 |
| **侯俊旭** | 后端 | 传感器数据处理、WebSocket推送 |
| **杨坤** | 后端 | 告警管理、控制逻辑、智能体API |

---

## 🛠️ 技术栈

| 层级 | 技术 |
|------|------|
| **后端** | Java 17 + Spring Boot 3.x + Spring Data JPA + MySQL |
| **前端** | Vue 3 + Vite + Element Plus + ECharts |
| **通信** | MQTT (Eclipse Paho) + WebSocket |
| **消息代理** | EMQX |
| **硬件（基地）** | 鸿蒙开发板 Hi3861/Hi3516 + 光照传感器 + 继电器 |
| **智能体** | MaxKB + 大模型 |

---

## 📁 项目结构

```
smart-streetlight/
├── backend/              # Spring Boot 后端
│   ├── src/main/java/com/streetlight/
│   │   ├── config/       # MQTT、WebSocket、CORS配置
│   │   ├── controller/   # REST API控制器
│   │   ├── service/      # 业务逻辑层
│   │   ├── repository/   # JPA数据访问层
│   │   ├── entity/       # 实体类
│   │   ├── mqtt/         # MQTT客户端处理
│   │   └── websocket/    # WebSocket推送
│   └── pom.xml
├── frontend/             # Vue 3 前端
│   └── src/
│       ├── views/        # 页面组件
│       ├── components/   # 公共组件
│       ├── router/       # 路由配置
│       └── store/        # 状态管理
├── mock-device/          # Mock数据发生器
├── docs/                 # 项目文档
└── README.md
```

---

## 🚀 快速开始

### 环境要求

- **后端**: JDK 17+, Maven 3.8+, MySQL 8.0+
- **前端**: Node.js 18+, npm/pnpm
- **MQTT**: EMQX (或任一MQTT Broker)

### 1. 克隆项目

```bash
git clone https://github.com/fabiocsa/smart-streetlight.git
cd smart-streetlight
```

### 2. 启动后端

```bash
# 创建MySQL数据库
mysql -u root -p -e "CREATE DATABASE streetlight DEFAULT CHARACTER SET utf8mb4;"

# 修改配置文件
# 编辑 backend/src/main/resources/application.yml
# 配置MySQL账号密码、MQTT Broker地址

# 启动后端
cd backend
mvn spring-boot:run
```

### 3. 启动前端

```bash
cd frontend
npm install
npm run dev
```

### 4. 启动Mock数据发生器

```bash
cd mock-device
pip install paho-mqtt
python mqtt_simulator.py
```

### 5. 访问系统

打开浏览器访问：`http://localhost:5173`

---

## 📊 功能模块

| 模块 | 功能 | 说明 |
|------|------|------|
| 📈 Dashboard总览 | 设备状态概览 | 设备总数、在线率、告警统计 |
| 🌤️ 实时监测 | 光照强度实时展示 | WebSocket实时推送 |
| 🔦 设备控制 | 手动/自动开关灯 | 阈值联动控制 |
| 📉 历史趋势 | ECharts折线图 | 光照变化趋势 |
| ⚠️ 告警管理 | 离线告警、告警处理 | 设备状态监控 |
| 🔧 设备管理 | 设备CRUD | 添加、编辑、删除设备 |
| 🤖 智能问答 | AI问答 | 维护知识查询 |

---

## 📅 开发计划

| 阶段 | 时间 | 内容 |
|------|------|------|
| 需求与设计 | D2-D3 | 需求分析、系统设计、API设计 |
| 模拟数据传输 | D4-D5 | Mock→MQTT→后端→前端链路 |
| 智能体构建 | D6-D8 | MaxKB智能问答 |
| 可视化大屏 | D9-D11 | ECharts图表、所有页面 |
| 系统联调 | D12-D13 | 测试、修bug |
| 中期答辩 | D14-D15 | PPT、演示 |

---

## 📄 文档

- [需求分析](docs/需求分析.md)
- [系统设计](docs/系统设计.md)
- [API文档](docs/API文档.md)
- [团队分工与计划](docs/团队分工与计划.md)

---

## 🏗️ 后续（基地10天）

校内15天使用Mock数据发生器模拟硬件数据，基地10天接入真实鸿蒙开发板（Hi3861/Hi3516），软件代码基本不变，仅替换数据源。
66611
