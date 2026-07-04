-- ============================================================================
-- 智慧路灯系统 - 数据库初始化脚本
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
-- 3. 传感器数据表 (sensor_data)
--    存储光照传感器上报的历史数据（只追加，不修改）
-- ============================================================================
DROP TABLE IF EXISTS sensor_data;
CREATE TABLE sensor_data (
    id               BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '主键',
    device_id        VARCHAR(50)  NOT NULL                 COMMENT '设备标识(FK → device.device_id)',
    light_intensity  DOUBLE       NOT NULL                 COMMENT '光照强度(Lux)，范围 0~2000',
    reported_at      DATETIME     NOT NULL                 COMMENT '设备上报时间',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录写入时间',

    PRIMARY KEY (id),
    INDEX idx_device_reported (device_id, reported_at),
    INDEX idx_reported_at (reported_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='传感器数据表';

-- ============================================================================
-- 4. 告警日志表 (alarm_log)
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
-- 5. 控制日志表 (control_log)
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
-- 6. 插入示例数据
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

-- 插入传感器示例数据（过去2小时，每5分钟一条，共24条/设备 × 前4个设备）
INSERT INTO sensor_data (device_id, light_intensity, reported_at) VALUES
    ('SL-001', 120.5, DATE_SUB(NOW(), INTERVAL 120 MINUTE)),
    ('SL-001', 95.0,  DATE_SUB(NOW(), INTERVAL 115 MINUTE)),
    ('SL-001', 80.2,  DATE_SUB(NOW(), INTERVAL 110 MINUTE)),
    ('SL-001', 60.5,  DATE_SUB(NOW(), INTERVAL 105 MINUTE)),
    ('SL-001', 45.0,  DATE_SUB(NOW(), INTERVAL 100 MINUTE)),
    ('SL-001', 35.2,  DATE_SUB(NOW(), INTERVAL 95 MINUTE)),
    ('SL-001', 28.5,  DATE_SUB(NOW(), INTERVAL 90 MINUTE)),
    ('SL-001', 25.0,  DATE_SUB(NOW(), INTERVAL 85 MINUTE)),
    ('SL-001', 30.5,  DATE_SUB(NOW(), INTERVAL 80 MINUTE)),
    ('SL-001', 42.0,  DATE_SUB(NOW(), INTERVAL 75 MINUTE)),
    ('SL-001', 55.8,  DATE_SUB(NOW(), INTERVAL 70 MINUTE)),
    ('SL-001', 70.2,  DATE_SUB(NOW(), INTERVAL 65 MINUTE)),
    ('SL-001', 88.5,  DATE_SUB(NOW(), INTERVAL 60 MINUTE)),
    ('SL-001', 110.0, DATE_SUB(NOW(), INTERVAL 55 MINUTE)),
    ('SL-001', 135.2, DATE_SUB(NOW(), INTERVAL 50 MINUTE)),
    ('SL-001', 150.5, DATE_SUB(NOW(), INTERVAL 45 MINUTE)),
    ('SL-001', 165.0, DATE_SUB(NOW(), INTERVAL 40 MINUTE)),
    ('SL-001', 180.2, DATE_SUB(NOW(), INTERVAL 35 MINUTE)),
    ('SL-001', 200.5, DATE_SUB(NOW(), INTERVAL 30 MINUTE)),
    ('SL-001', 185.0, DATE_SUB(NOW(), INTERVAL 25 MINUTE)),
    ('SL-001', 160.2, DATE_SUB(NOW(), INTERVAL 20 MINUTE)),
    ('SL-001', 140.5, DATE_SUB(NOW(), INTERVAL 15 MINUTE)),
    ('SL-001', 125.0, DATE_SUB(NOW(), INTERVAL 10 MINUTE)),
    ('SL-001', 115.5, DATE_SUB(NOW(), INTERVAL 5 MINUTE));

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
-- 7. 创建常用视图
-- ============================================================================

-- 7.1 Dashboard总览统计视图
DROP VIEW IF EXISTS v_dashboard_stats;
CREATE VIEW v_dashboard_stats AS
SELECT
    COUNT(*)                                    AS total_devices,
    SUM(CASE WHEN status = 'online'  THEN 1 ELSE 0 END)  AS online_devices,
    SUM(CASE WHEN status = 'offline' THEN 1 ELSE 0 END)  AS offline_devices,
    SUM(CASE WHEN light_status = 'on'  THEN 1 ELSE 0 END) AS lights_on,
    SUM(CASE WHEN light_status = 'off' THEN 1 ELSE 0 END) AS lights_off,
    (SELECT COUNT(*) FROM alarm_log WHERE status = 'pending') AS pending_alarms,
    (SELECT COUNT(*) FROM alarm_log WHERE DATE(created_at) = CURDATE()) AS today_alarms
FROM device;

-- 7.2 设备最新传感器数据视图
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
    s.light_intensity,
    s.reported_at AS last_reported_at
FROM device d
LEFT JOIN sensor_data s ON s.id = (
    SELECT s2.id FROM sensor_data s2
    WHERE s2.device_id = d.device_id
    ORDER BY s2.reported_at DESC
    LIMIT 1
);

-- ============================================================================
-- 8. 验证：查看表结构和示例数据
-- ============================================================================
-- 取消注释以验证:
-- SHOW TABLES;
-- SELECT * FROM device;
-- SELECT * FROM v_dashboard_stats;
-- SELECT * FROM v_device_latest_sensor;
