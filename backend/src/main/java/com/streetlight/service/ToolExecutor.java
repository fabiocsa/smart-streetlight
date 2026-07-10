package com.streetlight.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.streetlight.config.ToolPermissionConfig;
import com.streetlight.entity.*;
import com.streetlight.repository.ControlLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolExecutor {

    private final DashboardService dashboardService;
    private final DeviceService deviceService;
    private final SensorDataService sensorDataService;
    private final AlarmService alarmService;
    private final ControlService controlService;
    private final ControlLogRepository controlLogRepository;
    private final ObjectMapper objectMapper;

    // ======================== 公开方法 ========================

    /** 根据角色生成工具选择阶段的 System Prompt（仅列出该角色可用的工具） */
    public String getToolsPrompt(String role) {
        return ToolPermissionConfig.getToolsPrompt(role);
    }

    /** 兼容旧调用（默认最低权限 OPERATOR） */
    public String execute(String toolName, Map<String, Object> params) {
        return execute(toolName, params, ToolPermissionConfig.ROLE_OPERATOR);
    }

    /** 执行工具（含角色权限校验） */
    public String execute(String toolName, Map<String, Object> params, String role) {
        if (!ToolPermissionConfig.isToolAllowed(role, toolName)) {
            log.warn("权限拒绝: role={}, tool={}", role, toolName);
            return toJson(Map.of("error", "权限不足：角色 " + role + " 无法执行工具 " + toolName));
        }
        try {
            return switch (toolName) {
                // 查询/监测类
                case ToolPermissionConfig.TOOL_GET_DEVICE_SUMMARY -> getDeviceSummary();
                case ToolPermissionConfig.TOOL_GET_DEVICE_LIST    -> getDeviceList();
                case ToolPermissionConfig.TOOL_GET_DEVICE_DETAIL  -> getDeviceDetail(params);
                case ToolPermissionConfig.TOOL_GET_LATEST_SENSOR  -> getLatestSensor(params);
                case ToolPermissionConfig.TOOL_GET_SENSOR_HISTORY -> getSensorHistory(params);
                case ToolPermissionConfig.TOOL_GET_ALARMS         -> getAlarms(params);
                case ToolPermissionConfig.TOOL_GET_CONTROL_LOGS   -> getControlLogs(params);
                // 控制/阈值类
                case ToolPermissionConfig.TOOL_CONTROL_LIGHT       -> controlLight(params);
                case ToolPermissionConfig.TOOL_SET_THRESHOLD       -> setThreshold(params);
                case ToolPermissionConfig.TOOL_SWITCH_CONTROL_MODE -> switchControlMode(params);
                // 设备管理类（仅 admin）
                case ToolPermissionConfig.TOOL_ADD_DEVICE    -> addDevice(params);
                case ToolPermissionConfig.TOOL_UPDATE_DEVICE -> updateDevice(params);
                case ToolPermissionConfig.TOOL_DELETE_DEVICE -> deleteDevice(params);
                case ToolPermissionConfig.TOOL_BIND_SENSOR   -> bindSensor(params);
                case ToolPermissionConfig.TOOL_UNBIND_SENSOR -> unbindSensor(params);
                // 告警处理类（仅 admin）
                case ToolPermissionConfig.TOOL_RESOLVE_ALARM -> resolveAlarm(params);
                default -> toJson(Map.of("error", "未知工具: " + toolName));
            };
        } catch (Exception e) {
            log.error("工具执行失败: tool={}, params={}", toolName, params, e);
            return toJson(Map.of("error", "操作失败: " + e.getMessage()));
        }
    }

    // ======================== 查询/监测工具 ========================

    private String getDeviceSummary() {
        return toJson(dashboardService.getStats());
    }

    private String getDeviceList() {
        List<Device> devices = deviceService.getAllDevices();
        List<Map<String, Object>> list = devices.stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("deviceId", d.getDeviceId());
            m.put("name", d.getName());
            m.put("status", d.getStatus());
            m.put("lightStatus", d.getLightStatus());
            m.put("controlMode", d.getControlMode());
            m.put("location", d.getLocation());
            m.put("lastHeartbeat", d.getLastHeartbeat() != null ? d.getLastHeartbeat().toString() : null);
            return m;
        }).toList();
        return toJson(Map.of("count", list.size(), "devices", list));
    }

    private String getDeviceDetail(Map<String, Object> params) {
        String deviceId = (String) params.get("deviceId");
        if (deviceId == null || deviceId.isBlank()) {
            return toJson(Map.of("error", "请提供设备ID"));
        }
        Device d = deviceService.getDeviceByDeviceId(deviceId).orElse(null);
        if (d == null) {
            return toJson(Map.of("error", "设备 " + deviceId + " 不存在"));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("deviceId", d.getDeviceId());
        m.put("name", d.getName());
        m.put("status", d.getStatus());
        m.put("lightStatus", d.getLightStatus());
        m.put("controlMode", d.getControlMode());
        m.put("thresholdOn", d.getThresholdOn());
        m.put("thresholdOff", d.getThresholdOff());
        m.put("location", d.getLocation());
        m.put("lastHeartbeat", d.getLastHeartbeat() != null ? d.getLastHeartbeat().toString() : null);

        List<Sensor> sensors = deviceService.getDeviceSensors(d.getId());
        m.put("sensors", sensors.stream().map(s -> Map.of(
                "displayName", s.getDisplayName() != null ? s.getDisplayName() : s.getDataTopic(),
                "sensorType", s.getSensorType(),
                "frequency", s.getReportFrequency() + "秒"
        )).toList());

        return toJson(m);
    }

    private String getLatestSensor(Map<String, Object> params) {
        String deviceId = (String) params.get("deviceId");
        if (deviceId == null || deviceId.isBlank()) {
            return toJson(Map.of("error", "请提供设备ID"));
        }
        SensorData data = sensorDataService.getLatestByDeviceId(deviceId).orElse(null);
        if (data == null) {
            return toJson(Map.of("deviceId", deviceId, "message", "暂无光照数据"));
        }
        return toJson(Map.of(
                "deviceId", deviceId,
                "lightIntensity", data.getLightIntensity(),
                "reportedAt", data.getReportedAt().toString()
        ));
    }

    private String getSensorHistory(Map<String, Object> params) {
        String deviceId = (String) params.get("deviceId");
        if (deviceId == null || deviceId.isBlank()) {
            return toJson(Map.of("error", "请提供设备ID"));
        }
        int hours = getIntParam(params, "hours", 1);
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = end.minusHours(hours);

        Map<String, Object> stats = sensorDataService.getStats(deviceId, "lightIntensity", start, end);
        List<SensorData> history = sensorDataService.getHistory(deviceId, start, end);

        List<Map<String, Object>> samples = new ArrayList<>();
        int size = history.size();
        if (size <= 14) {
            for (SensorData d : history) {
                samples.add(toDataPoint(d));
            }
        } else {
            for (int i = 0; i < 2 && i < size; i++) samples.add(toDataPoint(history.get(i)));
            samples.add(Map.of("...", "省略" + (size - 4) + "条"));
            for (int i = size - 2; i < size; i++) samples.add(toDataPoint(history.get(i)));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId);
        result.put("timeRange", start.toString() + " ~ " + end.toString());
        result.put("avg", stats.get("avg"));
        result.put("max", stats.get("max"));
        result.put("min", stats.get("min"));
        result.put("totalCount", stats.get("count"));
        result.put("samples", samples);
        return toJson(result);
    }

    private String getAlarms(Map<String, Object> params) {
        String status = (String) params.get("status");
        int limit = getIntParam(params, "limit", 5);
        Page<AlarmLog> page = alarmService.listAlarms(0, limit, status, null, null, null, null);
        List<Map<String, Object>> list = page.getContent().stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("deviceId", a.getDeviceId());
            m.put("type", a.getAlarmType() != null ? a.getAlarmType().name() : null);
            m.put("severity", a.getSeverity() != null ? a.getSeverity().name() : null);
            m.put("status", a.getStatus() != null ? a.getStatus().name() : null);
            m.put("content", a.getContent());
            m.put("createdAt", a.getCreatedAt() != null ? a.getCreatedAt().toString() : null);
            return m;
        }).toList();
        return toJson(Map.of("total", page.getTotalElements(), "count", list.size(), "alarms", list));
    }

    private String getControlLogs(Map<String, Object> params) {
        String deviceId = (String) params.get("deviceId");
        int limit = getIntParam(params, "limit", 5);
        List<ControlLog> logs;
        if (deviceId != null && !deviceId.isBlank()) {
            logs = controlLogRepository.findAll(
                    PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
            logs = logs.stream().filter(l -> deviceId.equals(l.getDeviceId())).toList();
        } else {
            logs = controlLogRepository.findAll(
                    PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
        }
        List<Map<String, Object>> list = logs.stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("deviceId", l.getDeviceId());
            m.put("command", l.getCommand());
            m.put("source", l.getSource());
            m.put("result", l.getResult());
            m.put("createdAt", l.getCreatedAt() != null ? l.getCreatedAt().toString() : null);
            return m;
        }).toList();
        return toJson(Map.of("count", list.size(), "logs", list));
    }

    // ======================== 控制/阈值工具 ========================

    private String controlLight(Map<String, Object> params) {
        String deviceId = (String) params.get("deviceId");
        String command = (String) params.get("command");
        if (deviceId == null || deviceId.isBlank()) {
            return toJson(Map.of("error", "请提供设备ID"));
        }
        if (command == null || (!"on".equals(command) && !"off".equals(command))) {
            return toJson(Map.of("error", "command 参数必须为 on 或 off"));
        }
        controlService.sendControlCommand(deviceId, command, "ai");
        return toJson(Map.of("result", "success", "deviceId", deviceId, "command", command,
                "message", "已向设备 " + deviceId + " 下发" + ("on".equals(command) ? "开灯" : "关灯") + "指令"));
    }

    private String setThreshold(Map<String, Object> params) {
        String deviceId = (String) params.get("deviceId");
        if (deviceId == null || deviceId.isBlank()) {
            return toJson(Map.of("error", "请提供设备ID"));
        }
        Device device = deviceService.getDeviceByDeviceId(deviceId).orElse(null);
        if (device == null) {
            return toJson(Map.of("error", "设备 " + deviceId + " 不存在"));
        }
        Object onObj = params.get("thresholdOn");
        Object offObj = params.get("thresholdOff");
        if (onObj == null || offObj == null) {
            return toJson(Map.of("error", "请提供 thresholdOn 和 thresholdOff 参数"));
        }
        double thresholdOn = toDouble(onObj);
        double thresholdOff = toDouble(offObj);
        controlService.setThreshold(device.getId(), thresholdOn, thresholdOff);
        return toJson(Map.of("result", "success", "deviceId", deviceId,
                "thresholdOn", thresholdOn, "thresholdOff", thresholdOff,
                "message", "已更新设备 " + deviceId + " 的光照阈值"));
    }

    private String switchControlMode(Map<String, Object> params) {
        String deviceId = (String) params.get("deviceId");
        String mode = (String) params.get("mode");
        if (deviceId == null || deviceId.isBlank()) {
            return toJson(Map.of("error", "请提供设备ID"));
        }
        if (mode == null || (!"auto".equals(mode) && !"manual".equals(mode))) {
            return toJson(Map.of("error", "mode 参数必须为 auto 或 manual"));
        }
        Device device = deviceService.getDeviceByDeviceId(deviceId).orElse(null);
        if (device == null) {
            return toJson(Map.of("error", "设备 " + deviceId + " 不存在"));
        }
        controlService.setControlMode(device.getId(), mode);
        return toJson(Map.of("result", "success", "deviceId", deviceId, "mode", mode,
                "message", "已将设备 " + deviceId + " 切换为" + ("auto".equals(mode) ? "自动" : "手动") + "模式"));
    }

    // ======================== 设备管理工具（仅 admin） ========================

    private String addDevice(Map<String, Object> params) {
        String deviceId = (String) params.get("deviceId");
        String name = (String) params.get("name");
        if (deviceId == null || deviceId.isBlank()) {
            return toJson(Map.of("error", "请提供设备编号 deviceId"));
        }
        if (name == null || name.isBlank()) {
            return toJson(Map.of("error", "请提供设备名称 name"));
        }
        // 检查 deviceId 是否已存在
        if (deviceService.getDeviceByDeviceId(deviceId).isPresent()) {
            return toJson(Map.of("error", "设备 " + deviceId + " 已存在，请使用其他编号"));
        }
        Device device = Device.builder()
                .deviceId(deviceId)
                .name(name)
                .location((String) params.getOrDefault("location", ""))
                .build();
        Device saved = deviceService.addDevice(device);
        return toJson(Map.of("result", "success", "deviceId", saved.getDeviceId(),
                "name", saved.getName(), "id", saved.getId(),
                "message", "已成功添加设备 " + deviceId));
    }

    private String updateDevice(Map<String, Object> params) {
        String deviceId = (String) params.get("deviceId");
        if (deviceId == null || deviceId.isBlank()) {
            return toJson(Map.of("error", "请提供设备ID"));
        }
        Device device = deviceService.getDeviceByDeviceId(deviceId).orElse(null);
        if (device == null) {
            return toJson(Map.of("error", "设备 " + deviceId + " 不存在"));
        }
        String name = (String) params.get("name");
        String location = (String) params.get("location");
        if (name == null && location == null) {
            return toJson(Map.of("error", "请至少提供 name 或 location 中的一个参数"));
        }
        Device updated = new Device();
        updated.setName(name != null ? name : device.getName());
        updated.setLocation(location != null ? location : device.getLocation());
        Device saved = deviceService.updateDevice(device.getId(), updated);
        return toJson(Map.of("result", "success", "deviceId", saved.getDeviceId(),
                "name", saved.getName(), "location", saved.getLocation(),
                "message", "已更新设备 " + deviceId + " 的信息"));
    }

    private String deleteDevice(Map<String, Object> params) {
        String deviceId = (String) params.get("deviceId");
        if (deviceId == null || deviceId.isBlank()) {
            return toJson(Map.of("error", "请提供设备ID"));
        }
        Device device = deviceService.getDeviceByDeviceId(deviceId).orElse(null);
        if (device == null) {
            return toJson(Map.of("error", "设备 " + deviceId + " 不存在"));
        }
        deviceService.deleteDevice(device.getId());
        return toJson(Map.of("result", "success", "deviceId", deviceId,
                "message", "已删除设备 " + deviceId));
    }

    private String bindSensor(Map<String, Object> params) {
        String deviceId = (String) params.get("deviceId");
        Object sensorIdObj = params.get("sensorId");
        if (deviceId == null || deviceId.isBlank()) {
            return toJson(Map.of("error", "请提供设备ID"));
        }
        if (sensorIdObj == null) {
            return toJson(Map.of("error", "请提供传感器ID sensorId"));
        }
        long sensorId = toLong(sensorIdObj);
        Device device = deviceService.getDeviceByDeviceId(deviceId).orElse(null);
        if (device == null) {
            return toJson(Map.of("error", "设备 " + deviceId + " 不存在"));
        }
        deviceService.bindSensor(device.getId(), sensorId);
        return toJson(Map.of("result", "success", "deviceId", deviceId, "sensorId", sensorId,
                "message", "已将传感器 " + sensorId + " 绑定到设备 " + deviceId));
    }

    private String unbindSensor(Map<String, Object> params) {
        String deviceId = (String) params.get("deviceId");
        Object sensorIdObj = params.get("sensorId");
        if (deviceId == null || deviceId.isBlank()) {
            return toJson(Map.of("error", "请提供设备ID"));
        }
        if (sensorIdObj == null) {
            return toJson(Map.of("error", "请提供传感器ID sensorId"));
        }
        long sensorId = toLong(sensorIdObj);
        Device device = deviceService.getDeviceByDeviceId(deviceId).orElse(null);
        if (device == null) {
            return toJson(Map.of("error", "设备 " + deviceId + " 不存在"));
        }
        deviceService.unbindSensor(device.getId(), sensorId);
        return toJson(Map.of("result", "success", "deviceId", deviceId, "sensorId", sensorId,
                "message", "已解绑传感器 " + sensorId + " 从设备 " + deviceId));
    }

    // ======================== 告警处理工具（仅 admin） ========================

    private String resolveAlarm(Map<String, Object> params) {
        Object alarmIdObj = params.get("alarmId");
        if (alarmIdObj == null) {
            return toJson(Map.of("error", "请提供告警ID alarmId"));
        }
        long alarmId = toLong(alarmIdObj);
        alarmService.resolveAlarm(alarmId, "AI助手");
        return toJson(Map.of("result", "success", "alarmId", alarmId,
                "message", "已处理告警 #" + alarmId));
    }

    // ======================== 工具方法 ========================

    private Map<String, Object> toDataPoint(SensorData d) {
        return Map.of("lightIntensity", d.getLightIntensity(), "time", d.getReportedAt().toString());
    }

    private int getIntParam(Map<String, Object> params, String key, int defaultValue) {
        Object v = params.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt((String) v); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    private double toDouble(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try { return Double.parseDouble((String) v); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    private long toLong(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try { return Long.parseLong((String) v); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"error\":\"JSON序列化失败\"}";
        }
    }
}
