-- ============================================================================
-- 智慧路灯系统 - 性能优化索引
-- 使用方法: mysql -h 8.130.102.89 -u remote_user -p streetlight < docs/optimize_indexes.sql
-- ============================================================================

USE streetlight;

-- 1. sensor_data 复合索引: 按传感器查最新数据（saveAndAutoControl 调用链路）
ALTER TABLE sensor_data ADD INDEX IF NOT EXISTS idx_sd_sensor_time (sensor_id, reported_at DESC);

-- 2. sensor_data 复合索引: 按设备 + 时间查趋势（Dashboard 24h/7d/30d 聚合）
ALTER TABLE sensor_data ADD INDEX IF NOT EXISTS idx_sd_device_time (device_id, reported_at DESC);

-- 3. sensor_data 索引: 按时间范围统计（Dashboard getStats countToday）
ALTER TABLE sensor_data ADD INDEX IF NOT EXISTS idx_sd_reported_at (reported_at);

-- 4. illuminance 生成列 + 索引（让 JSON_EXTRACT($.illuminance) 走索引，加速趋势查询）
ALTER TABLE sensor_data
    ADD COLUMN IF NOT EXISTS illuminance_val DOUBLE
    GENERATED ALWAYS AS (CAST(JSON_UNQUOTE(JSON_EXTRACT(data_json, '$.illuminance')) AS DOUBLE)) STORED;
ALTER TABLE sensor_data ADD INDEX IF NOT EXISTS idx_sd_illuminance (illuminance_val);

-- 5. device 表: 补充控制模式计数索引（getStats 用）
ALTER TABLE device ADD INDEX IF NOT EXISTS idx_device_control_mode (control_mode);

-- 6. alarm_log 表: 按状态 + 类型快速查 PENDING 告警
ALTER TABLE alarm_log ADD INDEX IF NOT EXISTS idx_alarm_status_type (status, alarm_type);
