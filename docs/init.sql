-- ============================================================================
-- 智慧路灯系统 - 数据库初始化脚本 (v2 — JSON 传感器数据)
-- Smart Streetlight System - Database Initialization Script
-- 技术栈: MySQL 8.x
-- 字符集: utf8mb4 (支持emoji和特殊字符)
-- ============================================================================

-- 1. 创建数据库
-- ============================================================================
DROP DATABASE IF EXISTS streetlight;
CREATE DATABASE IF NOT EXISTS streetlight
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE streetlight;

-- ============================================================================
-- 2. 设备表 (device)
--    存储路灯设备的静态信息与实时状态
-- ============================================================================
DROP TABLE IF EXISTS device;
CREATE TABLE device (
    id              BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键',
    name            VARCHAR(100)    NOT NULL                 COMMENT '设备名称(如: 路灯A-01)',
    device_id       VARCHAR(50)     NOT NULL                 COMMENT '设备唯一标识(如: SL-001)',
    status          VARCHAR(20)     NOT NULL DEFAULT 'offline' COMMENT '设备状态: online / offline',
    threshold_on    DOUBLE          NOT NULL DEFAULT 50.0    COMMENT '开灯光照阈值(Lux)，低于此值开灯',
    threshold_off   DOUBLE          NOT NULL DEFAULT 100.0   COMMENT '关灯光照阈值(Lux)，高于此值关灯',
    light_status    VARCHAR(10)     NOT NULL DEFAULT 'off'   COMMENT '当前灯光状态: on / off',
    control_mode    VARCHAR(10)     NOT NULL DEFAULT 'auto'  COMMENT '控制模式: auto(自动) / manual(手动)',
    location        VARCHAR(200)    DEFAULT NULL             COMMENT '安装位置(如: 校门口、图书馆前)',
    last_heartbeat  DATETIME        DEFAULT NULL             COMMENT '最后心跳时间',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_device_id (device_id),
    INDEX idx_status (status),
    INDEX idx_control_mode (control_mode)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设备表';

-- ============================================================================
-- 3. 传感器定义表 (sensor)
--    存储传感器的定义信息。传感器独立存在，不持有设备 ID。
--    设备与传感器的绑定关系通过 device_sensor 关联表管理。
-- ============================================================================
DROP TABLE IF EXISTS sensor;
CREATE TABLE sensor (
    id               BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '主键',
    sensor_type      VARCHAR(30)  NOT NULL DEFAULT 'light' COMMENT '传感器类型: light(光照) / temperature(温度) / humidity(湿度) / power(功率)',
    display_name     VARCHAR(100) DEFAULT NULL             COMMENT '传感器显示名称(如: 光照传感器A)',
    data_topic       VARCHAR(200) NOT NULL                 COMMENT '数据上报MQTT主题',
    report_frequency INT          NOT NULL DEFAULT 5       COMMENT '上报频率(秒)',
    enabled          TINYINT(1)   NOT NULL DEFAULT 1       COMMENT '是否启用: 1启用 / 0禁用',
    config_json      VARCHAR(500) DEFAULT NULL             COMMENT '传感器配置JSON(如数据范围、精度等)',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='传感器定义表';

-- ============================================================================
-- 3.1 设备-传感器关联表 (device_sensor)
--     设备绑定传感器（N:M），传感器不持有设备 ID
-- ============================================================================
DROP TABLE IF EXISTS device_sensor;
CREATE TABLE device_sensor (
    device_id   BIGINT  NOT NULL COMMENT 'FK → device.id',
    sensor_id   BIGINT  NOT NULL COMMENT 'FK → sensor.id',
    bound_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '绑定时间',
    PRIMARY KEY (device_id, sensor_id),
    INDEX idx_ds_sensor (sensor_id),
    CONSTRAINT fk_ds_device FOREIGN KEY (device_id) REFERENCES device(id) ON DELETE CASCADE,
    CONSTRAINT fk_ds_sensor FOREIGN KEY (sensor_id) REFERENCES sensor(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设备-传感器绑定关系表';

-- ============================================================================
-- 4. 传感器数据表 (sensor_data) — JSON 存储，支持异构多维数据
--    每个传感器上报的完整 JSON payload 直接存入 data_json 列。
--    无需 ALTER TABLE 即可支持新增传感器类型。
-- ============================================================================
DROP TABLE IF EXISTS sensor_data;
CREATE TABLE sensor_data (
    id              BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '主键',
    device_id       VARCHAR(50)  NOT NULL                 COMMENT '设备标识(FK → device.device_id)',
    sensor_id       BIGINT       DEFAULT NULL             COMMENT '传感器定义ID(FK → sensor.id)，可空',
    sensor_type     VARCHAR(30)  NOT NULL DEFAULT 'light' COMMENT '传感器类型(冗余, 便于筛选和聚合)',
    data_json       JSON         NOT NULL                 COMMENT '传感器全量数据(任意字段, 如 lightIntensity/temperature/humidity/power/voltage)',
    reported_at     DATETIME     NOT NULL                 COMMENT '设备上报时间',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录写入时间',

    PRIMARY KEY (id),
    INDEX idx_device_reported (device_id, reported_at),
    INDEX idx_sensor_reported (sensor_id, reported_at),
    INDEX idx_type_reported  (sensor_type, reported_at),
    INDEX idx_reported_at    (reported_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='传感器数据表(JSON存储,支持异构数据)';

-- ============================================================================
-- 5. 告警日志表 (alarm_log)
--    记录设备告警信息（离线告警、传感器异常等）
-- ============================================================================
DROP TABLE IF EXISTS alarm_log;
CREATE TABLE alarm_log (
    id              BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '主键',
    device_id       VARCHAR(50)  NOT NULL                 COMMENT '设备标识(FK → device.device_id)',
    alarm_type      VARCHAR(30)  NOT NULL                 COMMENT '告警类型: offline(离线) / sensor_abnormal(传感器异常)',
    content         VARCHAR(500) DEFAULT NULL             COMMENT '告警内容描述',
    severity        VARCHAR(10)  NOT NULL DEFAULT 'warning' COMMENT '严重级别: info(提示) / warning(警告) / critical(严重)',
    status          VARCHAR(20)  NOT NULL DEFAULT 'pending' COMMENT '处理状态: pending(待处理) / resolved(已处理)',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '告警触发时间',
    resolved_at     DATETIME     DEFAULT NULL             COMMENT '处理时间',
    resolved_by     VARCHAR(50)  DEFAULT NULL             COMMENT '处理人',

    PRIMARY KEY (id),
    INDEX idx_device_id (device_id),
    INDEX idx_alarm_status (status),
    INDEX idx_alarm_type (alarm_type),
    INDEX idx_created_at (created_at),
    INDEX idx_device_status (device_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='告警日志表';

-- ============================================================================
-- 6. 控制日志表 (control_log)
--    记录每一次开关灯指令的执行记录
-- ============================================================================
DROP TABLE IF EXISTS control_log;
CREATE TABLE control_log (
    id              BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '主键',
    device_id       VARCHAR(50)  NOT NULL                 COMMENT '设备标识(FK → device.device_id)',
    command         VARCHAR(10)  NOT NULL                 COMMENT '控制指令: on(开灯) / off(关灯)',
    source          VARCHAR(20)  NOT NULL                 COMMENT '指令来源: auto(自动联动) / manual(手动控制)',
    result          VARCHAR(10)  DEFAULT NULL             COMMENT '执行结果: success(成功) / fail(失败)',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '指令时间',

    PRIMARY KEY (id),
    INDEX idx_device_id (device_id),
    INDEX idx_source (source),
    INDEX idx_created_at (created_at),
    INDEX idx_device_created (device_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='控制日志表';

-- ============================================================================
-- 7. 插入示例数据
-- ============================================================================
INSERT INTO device (name, device_id, status, threshold_on, threshold_off, light_status, control_mode, location, last_heartbeat) VALUES
    ('路灯A-01', 'SL-001', 'online',  50.0, 100.0, 'off', 'auto',   '校门口',      NOW()),
    ('路灯A-02', 'SL-002', 'online',  50.0, 100.0, 'on',  'auto',   '图书馆前',    NOW()),
    ('路灯A-03', 'SL-003', 'offline', 50.0, 100.0, 'off', 'auto',   '操场东侧',    DATE_SUB(NOW(), INTERVAL 60 SECOND)),
    ('路灯B-01', 'SL-004', 'online',  30.0,  80.0, 'off', 'manual', '行政楼南',    NOW()),
    ('路灯B-02', 'SL-005', 'online',  50.0, 100.0, 'on',  'auto',   '食堂门口',    NOW()),
    ('路灯B-03', 'SL-006', 'offline', 50.0, 100.0, 'off', 'auto',   '宿舍区1栋',   DATE_SUB(NOW(), INTERVAL 120 SECOND)),
    ('路灯C-01', 'SL-007', 'online',  40.0,  90.0, 'off', 'auto',   '教学楼A',     NOW()),
    ('路灯C-02', 'SL-008', 'online',  50.0, 100.0, 'off', 'auto',   '教学楼B',     NOW());

-- 插入传感器定义示例数据（多种类型，均为独立传感器，未绑定设备）
INSERT INTO sensor (sensor_type, display_name, data_topic, report_frequency, enabled, config_json) VALUES
    ('light',       '光照传感器A',  'streetlight/sensor/1/data',  5, 1, '{"min": 0, "max": 800}'),
    ('power',       '功率传感器',   'streetlight/sensor/2/data', 10, 1, '{"min": 0, "max": 100}'),
    ('light',       '光照传感器',   'streetlight/sensor/3/data',  5, 1, '{"min": 0, "max": 800}'),
    ('temperature', '温度传感器',   'streetlight/sensor/4/data', 10, 1, '{"min": -10, "max": 50}'),
    ('light',       '光照传感器',   'streetlight/sensor/5/data',  5, 1, '{"min": 0, "max": 600}');

-- 插入设备-传感器绑定关系（通过关联表）
INSERT INTO device_sensor (device_id, sensor_id) VALUES
    (1, 1),  -- SL-001 绑定光照传感器A + 功率传感器
    (1, 2),
    (2, 3),  -- SL-002 绑定光照传感器 + 温度传感器
    (2, 4),
    (4, 5);  -- SL-004 绑定光照传感器

-- 插入传感器数据示例（JSON 格式，含多维度数据）
INSERT INTO sensor_data (device_id, sensor_id, sensor_type, data_json, reported_at) VALUES
    -- SL-001 光照数据（前24条，每5分钟一条）
    ('SL-001', 1, 'light', '{"lightIntensity": 120.5, "illuminance": 120.5, "temperature": 25.3, "voltage": 226.0, "power": 0.5, "cloudCover": 0.3, "status": "OFF"}', DATE_SUB(NOW(), INTERVAL 120 MINUTE)),
    ('SL-001', 1, 'light', '{"lightIntensity": 95.0,  "illuminance": 95.0,  "temperature": 25.8, "voltage": 225.5, "power": 0.4, "cloudCover": 0.3, "status": "OFF"}', DATE_SUB(NOW(), INTERVAL 115 MINUTE)),
    ('SL-001', 1, 'light', '{"lightIntensity": 80.2,  "illuminance": 80.2,  "temperature": 26.1, "voltage": 227.2, "power": 0.5, "cloudCover": 0.2, "status": "OFF"}', DATE_SUB(NOW(), INTERVAL 110 MINUTE)),
    ('SL-001', 1, 'light', '{"lightIntensity": 60.5,  "illuminance": 60.5,  "temperature": 27.0, "voltage": 226.8, "power": 0.4, "cloudCover": 0.1, "status": "OFF"}', DATE_SUB(NOW(), INTERVAL 105 MINUTE)),
    ('SL-001', 1, 'light', '{"lightIntensity": 45.0,  "illuminance": 45.0,  "temperature": 27.5, "voltage": 225.0, "power": 65.0, "cloudCover": 0.1, "status": "ON"}',  DATE_SUB(NOW(), INTERVAL 100 MINUTE)),
    ('SL-001', 1, 'light', '{"lightIntensity": 35.2,  "illuminance": 35.2,  "temperature": 27.8, "voltage": 223.5, "power": 68.2, "cloudCover": 0.2, "status": "ON"}',  DATE_SUB(NOW(), INTERVAL 95 MINUTE)),
    ('SL-001', 1, 'light', '{"lightIntensity": 28.5,  "illuminance": 28.5,  "temperature": 28.1, "voltage": 224.0, "power": 70.1, "cloudCover": 0.3, "status": "ON"}',  DATE_SUB(NOW(), INTERVAL 90 MINUTE)),
    ('SL-001', 1, 'light', '{"lightIntensity": 25.0,  "illuminance": 25.0,  "temperature": 28.3, "voltage": 222.8, "power": 72.5, "cloudCover": 0.4, "status": "ON"}',  DATE_SUB(NOW(), INTERVAL 85 MINUTE)),
    -- SL-001 功率数据
    ('SL-001', 2, 'power', '{"power": 72.5, "voltage": 222.8, "current": 0.33, "energy": 1.15}', DATE_SUB(NOW(), INTERVAL 85 MINUTE)),
    -- SL-002 多类型数据
    ('SL-002', 3, 'light',       '{"lightIntensity": 200.5, "illuminance": 200.5, "temperature": 29.0, "voltage": 228.0, "power": 0.3, "cloudCover": 0.5, "status": "OFF"}', DATE_SUB(NOW(), INTERVAL 30 MINUTE)),
    ('SL-002', 4, 'temperature', '{"temperature": 29.0, "humidity": 62.0}', DATE_SUB(NOW(), INTERVAL 30 MINUTE));

-- 插入告警示例数据
INSERT INTO alarm_log (device_id, alarm_type, content, severity, status, created_at) VALUES
    ('SL-003', 'offline',          '设备 SL-003 已离线超过30秒',               'warning',  'pending',  DATE_SUB(NOW(), INTERVAL 2 MINUTE)),
    ('SL-006', 'offline',          '设备 SL-006 已离线超过30秒',               'warning',  'pending',  DATE_SUB(NOW(), INTERVAL 5 MINUTE)),
    ('SL-002', 'sensor_abnormal',  '设备 SL-002 传感器数据异常(光照值=9999)',  'critical', 'resolved', DATE_SUB(NOW(), INTERVAL 1 HOUR));

-- 插入控制日志示例数据
INSERT INTO control_log (device_id, command, source, result, created_at) VALUES
    ('SL-001', 'off', 'auto',   'success', DATE_SUB(NOW(), INTERVAL 2 HOUR)),
    ('SL-001', 'on',  'auto',   'success', DATE_SUB(NOW(), INTERVAL 90 MINUTE)),
    ('SL-001', 'off', 'auto',   'success', DATE_SUB(NOW(), INTERVAL 30 MINUTE)),
    ('SL-002', 'on',  'auto',   'success', DATE_SUB(NOW(), INTERVAL 2 HOUR)),
    ('SL-004', 'off', 'manual', 'success', DATE_SUB(NOW(), INTERVAL 1 HOUR));

-- ============================================================================
-- 8. 创建常用视图
-- ============================================================================

-- 8.1 Dashboard总览统计视图
DROP VIEW IF EXISTS v_dashboard_stats;
CREATE VIEW v_dashboard_stats AS
SELECT
    COUNT(*)                                              AS total_devices,
    SUM(CASE WHEN status = 'online'  THEN 1 ELSE 0 END)   AS online_devices,
    SUM(CASE WHEN status = 'offline' THEN 1 ELSE 0 END)   AS offline_devices,
    SUM(CASE WHEN light_status = 'on'  THEN 1 ELSE 0 END) AS lights_on,
    SUM(CASE WHEN light_status = 'off' THEN 1 ELSE 0 END) AS lights_off,
    (SELECT COUNT(*) FROM alarm_log WHERE status = 'pending') AS pending_alarms,
    (SELECT COUNT(*) FROM alarm_log WHERE DATE(created_at) = CURDATE()) AS today_alarms
FROM device;

-- 8.2 设备最新传感器数据视图（提取 JSON 字段）
DROP VIEW IF EXISTS v_device_latest_sensor;
CREATE VIEW v_device_latest_sensor AS
SELECT
    d.id,
    d.name,
    d.device_id,
    d.status,
    d.light_status,
    d.control_mode,
    d.location,
    d.last_heartbeat,
    CAST(JSON_UNQUOTE(JSON_EXTRACT(s.data_json, '$.lightIntensity')) AS DOUBLE) AS light_intensity,
    CAST(JSON_UNQUOTE(JSON_EXTRACT(s.data_json, '$.temperature'))   AS DOUBLE) AS temperature,
    CAST(JSON_UNQUOTE(JSON_EXTRACT(s.data_json, '$.humidity'))      AS DOUBLE) AS humidity,
    CAST(JSON_UNQUOTE(JSON_EXTRACT(s.data_json, '$.power'))         AS DOUBLE) AS power,
    CAST(JSON_UNQUOTE(JSON_EXTRACT(s.data_json, '$.voltage'))       AS DOUBLE) AS voltage,
    s.sensor_type,
    s.reported_at AS last_reported_at
FROM device d
LEFT JOIN sensor_data s ON s.id = (
    SELECT s2.id FROM sensor_data s2
    WHERE s2.device_id = d.device_id
    ORDER BY s2.reported_at DESC
    LIMIT 1
);

-- ============================================================================
-- 9. 验证
-- ============================================================================
-- SHOW TABLES;
-- SELECT * FROM device;
-- SELECT * FROM v_dashboard_stats;
-- SELECT * FROM v_device_latest_sensor;
