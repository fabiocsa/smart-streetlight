-- ============================================================
-- 数据库索引优化脚本
-- 对应实体类已添加 @Table(indexes = {...}) 注解，
-- 若 Hibernate ddl-auto=update 未自动创建，可手动执行此脚本。
-- ============================================================

-- sensor_data 表：高频查询列
-- (device_id, reported_at) — 按设备查历史数据
CREATE INDEX idx_sd_device_reported ON sensor_data (device_id, reported_at);
-- (sensor_type, reported_at) — 按类型查历史数据
CREATE INDEX idx_sd_type_reported   ON sensor_data (sensor_type, reported_at);
-- (reported_at) — 时间范围过滤
CREATE INDEX idx_sd_reported        ON sensor_data (reported_at);

-- device 表：HeartbeatChecker 定时任务核心查询
-- (status, last_heartbeat) —"查找所有在线但心跳超时的设备"
CREATE INDEX idx_device_status_heartbeat ON device (status, last_heartbeat);

-- alarm_log 表：按设备+状态查找待处理告警
CREATE INDEX idx_alarm_device_status ON alarm_log (device_id, status);
-- 按创建时间范围统计
CREATE INDEX idx_alarm_created ON alarm_log (created_at);

-- control_log 表：更新控制指令响应结果
CREATE INDEX idx_cl_device_cmd_result ON control_log (device_id, command, result, created_at);
-- 按设备分页查询控制日志
CREATE INDEX idx_cl_device_created ON control_log (device_id, created_at);

-- sensor 表：按启用状态筛选
CREATE INDEX idx_sensor_enabled ON sensor (enabled);
