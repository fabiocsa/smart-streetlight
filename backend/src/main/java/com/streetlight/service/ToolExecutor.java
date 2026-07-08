package com.streetlight.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ControlLogRepository controlLogRepository;
    private final SensorService sensorService;
    private final ObjectMapper objectMapper;

    private static final String TOOLS_PROMPT = """
            你是一个智慧路灯管理系统的智能助手。你可以调用以下工具来获取实时数据，回答用户关于系统状态的问题。

            ## 可用工具：
            - get_device_summary: 获取设备总览统计（总数、在线数、离线数、开灯数、关灯数、待处理告警数）。无参数。
            - get_device_list: 获取所有设备的简要信息（ID、名称、状态、灯光、位置）。无参数。
            - get_device_detail: 获取指定设备的详细信息（含阈值、传感器）。参数: deviceId(字符串,必填)。
            - get_latest_sensor: 获取指定设备的最新光照值。参数: deviceId(字符串,必填)。
            - get_sensor_history: 获取指定设备最近一段时间的光照历史统计。参数: deviceId(字符串,必填), hours(数字,可选,默认1)。
            - get_alarms: 获取告警列表。参数: status(字符串,可选,值: pending/resolved), limit(数字,可选,默认5)。
            - get_control_logs: 获取最近的控制日志。参数: deviceId(字符串,可选), limit(数字,可选,默认5)。

            ## 规则：
            1. 如果用户问题涉及系统实时数据（设备状态、光照、告警、控制记录等），请返回JSON格式的工具调用：
               {"tool": "工具名", "params": {"参数名": "参数值"}}
            2. 如果用户问题不涉及系统数据（闲聊、通用知识等），请返回：{"tool": null}
            3. 只返回JSON，不要返回其他文字解释。

            ## 示例：
            用户：有多少路灯在线？ → {"tool": "get_device_summary", "params": {}}
            用户：SL-001的最新光照是多少？ → {"tool": "get_latest_sensor", "params": {"deviceId": "SL-001"}}
            用户：有哪些未处理的告警？ → {"tool": "get_alarms", "params": {"status": "pending"}}
            用户：你好，介绍一下自己 → {"tool": null}
            """;

    public String getToolsPrompt() {
        return TOOLS_PROMPT;
    }

    public String execute(String toolName, Map<String, Object> params) {
        try {
            return switch (toolName) {
                case "get_device_summary" -> getDeviceSummary();
                case "get_device_list" -> getDeviceList();
                case "get_device_detail" -> getDeviceDetail(params);
                case "get_latest_sensor" -> getLatestSensor(params);
                case "get_sensor_history" -> getSensorHistory(params);
                case "get_alarms" -> getAlarms(params);
                case "get_control_logs" -> getControlLogs(params);
                default -> toJson(Map.of("error", "未知工具: " + toolName));
            };
        } catch (Exception e) {
            log.error("工具执行失败: tool={}, params={}", toolName, params, e);
            return toJson(Map.of("error", "查询失败: " + e.getMessage()));
        }
    }

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
        Device d = deviceService.getDeviceByDeviceId(deviceId)
                .orElse(null);
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

        List<Sensor> sensors = sensorService.getSensorsByDeviceId(deviceId);
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

        Map<String, Object> stats = sensorDataService.getStats(deviceId, start, end);
        List<SensorData> history = sensorDataService.getHistory(deviceId, start, end);

        // 只取最近10条和首尾各2条，避免数据过多
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
        Page<AlarmLog> page = alarmService.listAlarms(0, limit, status, null);
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

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"error\":\"JSON序列化失败\"}";
        }
    }
}
