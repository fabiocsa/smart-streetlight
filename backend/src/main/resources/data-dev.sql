-- 开发环境测试数据
-- 适配 H2 数据库语法

-- 插入设备
INSERT INTO device (name, device_id, status, threshold_on, threshold_off, light_status, control_mode, location, last_heartbeat) VALUES
    ('路灯A-01', 'SL-001', 'online',  50.0, 100.0, 'off', 'auto',   '校门口',      CURRENT_TIMESTAMP()),
    ('路灯A-02', 'SL-002', 'online',  50.0, 100.0, 'on',  'auto',   '图书馆前',    CURRENT_TIMESTAMP()),
    ('路灯A-03', 'SL-003', 'offline', 50.0, 100.0, 'off', 'auto',   '操场东侧',    DATEADD('MINUTE', -1, CURRENT_TIMESTAMP())),
    ('路灯B-01', 'SL-004', 'online',  30.0,  80.0, 'off', 'manual', '行政楼南',    CURRENT_TIMESTAMP()),
    ('路灯B-02', 'SL-005', 'online',  50.0, 100.0, 'on',  'auto',   '食堂门口',    CURRENT_TIMESTAMP()),
    ('路灯B-03', 'SL-006', 'offline', 50.0, 100.0, 'off', 'auto',   '宿舍区1栋',   DATEADD('MINUTE', -2, CURRENT_TIMESTAMP())),
    ('路灯C-01', 'SL-007', 'online',  40.0,  90.0, 'off', 'auto',   '教学楼A',     CURRENT_TIMESTAMP()),
    ('路灯C-02', 'SL-008', 'online',  50.0, 100.0, 'off', 'auto',   '教学楼B',     CURRENT_TIMESTAMP());

-- 插入传感器数据（过去2小时，每5分钟一条）
INSERT INTO sensor_data (device_id, light_intensity, reported_at) VALUES
    ('SL-001', 120.5, DATEADD('MINUTE', -120, CURRENT_TIMESTAMP())),
    ('SL-001', 95.0,  DATEADD('MINUTE', -115, CURRENT_TIMESTAMP())),
    ('SL-001', 80.2,  DATEADD('MINUTE', -110, CURRENT_TIMESTAMP())),
    ('SL-001', 60.5,  DATEADD('MINUTE', -105, CURRENT_TIMESTAMP())),
    ('SL-001', 45.0,  DATEADD('MINUTE', -100, CURRENT_TIMESTAMP())),
    ('SL-001', 35.2,  DATEADD('MINUTE', -95, CURRENT_TIMESTAMP())),
    ('SL-001', 28.5,  DATEADD('MINUTE', -90, CURRENT_TIMESTAMP())),
    ('SL-001', 25.0,  DATEADD('MINUTE', -85, CURRENT_TIMESTAMP())),
    ('SL-001', 30.5,  DATEADD('MINUTE', -80, CURRENT_TIMESTAMP())),
    ('SL-001', 42.0,  DATEADD('MINUTE', -75, CURRENT_TIMESTAMP())),
    ('SL-001', 55.8,  DATEADD('MINUTE', -70, CURRENT_TIMESTAMP())),
    ('SL-001', 70.2,  DATEADD('MINUTE', -65, CURRENT_TIMESTAMP())),
    ('SL-001', 88.5,  DATEADD('MINUTE', -60, CURRENT_TIMESTAMP())),
    ('SL-001', 110.0, DATEADD('MINUTE', -55, CURRENT_TIMESTAMP())),
    ('SL-001', 135.2, DATEADD('MINUTE', -50, CURRENT_TIMESTAMP())),
    ('SL-001', 150.5, DATEADD('MINUTE', -45, CURRENT_TIMESTAMP())),
    ('SL-001', 165.0, DATEADD('MINUTE', -40, CURRENT_TIMESTAMP())),
    ('SL-001', 180.2, DATEADD('MINUTE', -35, CURRENT_TIMESTAMP())),
    ('SL-001', 200.5, DATEADD('MINUTE', -30, CURRENT_TIMESTAMP())),
    ('SL-001', 185.0, DATEADD('MINUTE', -25, CURRENT_TIMESTAMP())),
    ('SL-001', 160.2, DATEADD('MINUTE', -20, CURRENT_TIMESTAMP())),
    ('SL-001', 140.5, DATEADD('MINUTE', -15, CURRENT_TIMESTAMP())),
    ('SL-001', 125.0, DATEADD('MINUTE', -10, CURRENT_TIMESTAMP())),
    ('SL-001', 115.5, DATEADD('MINUTE', -5, CURRENT_TIMESTAMP()));

-- 插入告警数据
INSERT INTO alarm_log (device_id, alarm_type, content, severity, status, created_at) VALUES
    ('SL-003', 'offline',          '设备 SL-003 已离线超过30秒',               'warning',  'pending',  DATEADD('MINUTE', -2, CURRENT_TIMESTAMP())),
    ('SL-006', 'offline',          '设备 SL-006 已离线超过30秒',               'warning',  'pending',  DATEADD('MINUTE', -5, CURRENT_TIMESTAMP())),
    ('SL-002', 'sensor_abnormal',  '设备 SL-002 传感器数据异常(光照值=9999)',  'critical', 'resolved', DATEADD('HOUR', -1, CURRENT_TIMESTAMP()));

-- 插入控制日志
INSERT INTO control_log (device_id, command, source, result, created_at) VALUES
    ('SL-001', 'off', 'auto',   'success', DATEADD('MINUTE', -120, CURRENT_TIMESTAMP())),
    ('SL-001', 'on',  'auto',   'success', DATEADD('MINUTE', -90, CURRENT_TIMESTAMP())),
    ('SL-001', 'off', 'auto',   'success', DATEADD('MINUTE', -30, CURRENT_TIMESTAMP())),
    ('SL-002', 'on',  'auto',   'success', DATEADD('MINUTE', -120, CURRENT_TIMESTAMP())),
    ('SL-004', 'off', 'manual', 'success', DATEADD('MINUTE', -60, CURRENT_TIMESTAMP()));
