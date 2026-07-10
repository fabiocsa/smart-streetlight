package com.streetlight.config;

import java.util.Map;
import java.util.Set;

/**
 * AI 工具权限配置：角色 → 可用工具映射 + 动态 System Prompt 生成。
 *
 * 角色定义：
 *   admin     — 系统管理员，可使用全部工具（预留未来系统级扩展）
 *   manager   — 路灯管理员，可使用全部 16 个业务工具
 *   municipal — 市政人员，仅可使用查询/监测/控制/阈值相关工具
 */
public class ToolPermissionConfig {

    // ======================== 角色常量 ========================

    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_MANAGER = "manager";
    public static final String ROLE_OPERATOR = "municipal";

    // ======================== 工具名称常量 ========================

    // 查询/监测类（所有角色可用）
    public static final String TOOL_GET_DEVICE_SUMMARY = "get_device_summary";
    public static final String TOOL_GET_DEVICE_LIST    = "get_device_list";
    public static final String TOOL_GET_DEVICE_DETAIL  = "get_device_detail";
    public static final String TOOL_GET_LATEST_SENSOR  = "get_latest_sensor";
    public static final String TOOL_GET_SENSOR_HISTORY = "get_sensor_history";
    public static final String TOOL_GET_ALARMS         = "get_alarms";
    public static final String TOOL_GET_CONTROL_LOGS   = "get_control_logs";

    // 控制/阈值类（所有角色可用）
    public static final String TOOL_CONTROL_LIGHT       = "control_light";
    public static final String TOOL_SET_THRESHOLD       = "set_threshold";
    public static final String TOOL_SWITCH_CONTROL_MODE = "switch_control_mode";

    // 设备管理类（仅 admin / manager）
    public static final String TOOL_ADD_DEVICE    = "add_device";
    public static final String TOOL_UPDATE_DEVICE = "update_device";
    public static final String TOOL_DELETE_DEVICE = "delete_device";
    public static final String TOOL_BIND_SENSOR   = "bind_sensor";
    public static final String TOOL_UNBIND_SENSOR = "unbind_sensor";

    // 告警处理类（仅 admin / manager）
    public static final String TOOL_RESOLVE_ALARM = "resolve_alarm";

    // ======================== 角色 → 工具映射 ========================

    /** 市政人员（OPERATOR）可用工具：查询 + 控制 + 阈值 */
    private static final Set<String> OPERATOR_TOOLS = Set.of(
            TOOL_GET_DEVICE_SUMMARY,
            TOOL_GET_DEVICE_LIST,
            TOOL_GET_DEVICE_DETAIL,
            TOOL_GET_LATEST_SENSOR,
            TOOL_GET_SENSOR_HISTORY,
            TOOL_GET_ALARMS,
            TOOL_GET_CONTROL_LOGS,
            TOOL_CONTROL_LIGHT,
            TOOL_SET_THRESHOLD,
            TOOL_SWITCH_CONTROL_MODE
    );

    /** 管理员（ADMIN）可用工具：全部（与 manager 相同，预留未来系统级工具扩展） */
    private static final Set<String> ADMIN_TOOLS = Set.of(
            TOOL_GET_DEVICE_SUMMARY,
            TOOL_GET_DEVICE_LIST,
            TOOL_GET_DEVICE_DETAIL,
            TOOL_GET_LATEST_SENSOR,
            TOOL_GET_SENSOR_HISTORY,
            TOOL_GET_ALARMS,
            TOOL_GET_CONTROL_LOGS,
            TOOL_CONTROL_LIGHT,
            TOOL_SET_THRESHOLD,
            TOOL_SWITCH_CONTROL_MODE,
            TOOL_ADD_DEVICE,
            TOOL_UPDATE_DEVICE,
            TOOL_DELETE_DEVICE,
            TOOL_BIND_SENSOR,
            TOOL_UNBIND_SENSOR,
            TOOL_RESOLVE_ALARM
    );

    /** 路灯管理员（MANAGER）可用工具：全部 16 个业务工具 */
    private static final Set<String> MANAGER_TOOLS = Set.of(
            TOOL_GET_DEVICE_SUMMARY,
            TOOL_GET_DEVICE_LIST,
            TOOL_GET_DEVICE_DETAIL,
            TOOL_GET_LATEST_SENSOR,
            TOOL_GET_SENSOR_HISTORY,
            TOOL_GET_ALARMS,
            TOOL_GET_CONTROL_LOGS,
            TOOL_CONTROL_LIGHT,
            TOOL_SET_THRESHOLD,
            TOOL_SWITCH_CONTROL_MODE,
            TOOL_ADD_DEVICE,
            TOOL_UPDATE_DEVICE,
            TOOL_DELETE_DEVICE,
            TOOL_BIND_SENSOR,
            TOOL_UNBIND_SENSOR,
            TOOL_RESOLVE_ALARM
    );

    private static final Map<String, Set<String>> ROLE_TOOLS = Map.of(
            ROLE_ADMIN, ADMIN_TOOLS,
            ROLE_MANAGER, MANAGER_TOOLS,
            ROLE_OPERATOR, OPERATOR_TOOLS
    );

    // ======================== 工具描述（用于 System Prompt） ========================

    private static final String COMMON_TOOLS_DESC = """
            ## 可用工具（查询/监测类）：
            - get_device_summary: 获取设备总览统计（总数、在线数、离线数、开灯数、关灯数、待处理告警数）。无参数。
            - get_device_list: 获取所有设备的简要信息（ID、名称、状态、灯光、位置）。无参数。
            - get_device_detail: 获取指定设备的详细信息（含阈值、传感器）。参数: deviceId(字符串,必填)。
            - get_latest_sensor: 获取指定设备的最新光照值。参数: deviceId(字符串,必填)。
            - get_sensor_history: 获取指定设备最近一段时间的光照历史统计。参数: deviceId(字符串,必填), hours(数字,可选,默认1)。
            - get_alarms: 获取告警列表。参数: status(字符串,可选,值: pending/resolved), limit(数字,可选,默认5)。
            - get_control_logs: 获取最近的控制日志。参数: deviceId(字符串,可选), limit(数字,可选,默认5)。

            ## 可用工具（控制/阈值类）：
            - control_light: 下发开灯/关灯指令。参数: deviceId(字符串,必填), command(字符串,必填,值: on/off)。
            - set_threshold: 修改设备的开灯/关灯光照阈值。参数: deviceId(字符串,必填), thresholdOn(数字,必填,开灯阈值), thresholdOff(数字,必填,关灯阈值)。注意: thresholdOn 必须小于 thresholdOff。
            - switch_control_mode: 切换设备的自动/手动控制模式。参数: deviceId(字符串,必填), mode(字符串,必填,值: auto/manual)。""";

    private static final String ADMIN_ONLY_TOOLS_DESC = """

            ## 可用工具（设备管理类）：
            - add_device: 添加新路灯设备。参数: deviceId(字符串,必填,设备编号如SL-009), name(字符串,必填,设备名称), location(字符串,可选,安装位置)。
            - update_device: 修改设备信息（名称/位置等）。参数: deviceId(字符串,必填), name(字符串,可选), location(字符串,可选)。
            - delete_device: 删除（解绑）路灯设备。参数: deviceId(字符串,必填)。注意：此操作不可逆。
            - bind_sensor: 为设备绑定传感器。参数: deviceId(字符串,必填), sensorId(数字,必填,传感器数据库ID)。
            - unbind_sensor: 为设备解绑传感器。参数: deviceId(字符串,必填), sensorId(数字,必填,传感器数据库ID)。

            ## 可用工具（告警处理类）：
            - resolve_alarm: 处理（解决）告警。参数: alarmId(数字,必填,告警ID)。""";

    // ======================== 通用规则（两种角色共用） ========================

    private static final String TOOL_RULES = """

            ## 规则：
            1. 如果用户问题涉及系统实时数据或操作（设备状态、光照、告警、控制、阈值、设备管理等），请返回JSON格式的工具调用：
               {"tool": "工具名", "params": {"参数名": "参数值"}}
            2. 如果用户问题不涉及系统数据或操作（闲聊、通用知识等），请返回：{"tool": null}
            3. 只返回JSON，不要返回其他文字解释。

            ## 示例：
            用户：有多少路灯在线？ → {"tool": "get_device_summary", "params": {}}
            用户：SL-001的最新光照是多少？ → {"tool": "get_latest_sensor", "params": {"deviceId": "SL-001"}}
            用户：有哪些未处理的告警？ → {"tool": "get_alarms", "params": {"status": "pending"}}
            用户：你好，介绍一下自己 → {"tool": null}
            用户：帮我把SL-001的灯打开 → {"tool": "control_light", "params": {"deviceId": "SL-001", "command": "on"}}""";

    private static final String ADMIN_TOOL_EXAMPLES = """
            用户：删除SL-003这台设备 → {"tool": "delete_device", "params": {"deviceId": "SL-003"}}
            用户：添加一台新设备SL-009，放在操场 → {"tool": "add_device", "params": {"deviceId": "SL-009", "name": "操场路灯", "location": "操场"}}
            用户：处理告警#5 → {"tool": "resolve_alarm", "params": {"alarmId": 5}}""";

    // ======================== 公开方法 ========================

    /** 判断角色是否拥有完整管理权限（非市政人员即有完整权限） */
    private static boolean hasFullAccess(String role) {
        return !ROLE_OPERATOR.equals(role);
    }

    /** 根据角色获取允许的工具名称集合（非市政人员默认拥有全部工具） */
    public static Set<String> getAllowedToolsForRole(String role) {
        return ROLE_TOOLS.getOrDefault(role, ADMIN_TOOLS);
    }

    /** 检查指定角色是否有权使用指定工具 */
    public static boolean isToolAllowed(String role, String toolName) {
        return getAllowedToolsForRole(role).contains(toolName);
    }

    /** 生成工具选择阶段的 System Prompt（仅列出该角色可用的工具） */
    public static String getToolsPrompt(String role) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个智慧路灯管理系统的智能助手。你可以调用以下工具来获取实时数据或执行操作，回答用户关于系统状态的问题。\n");
        sb.append(COMMON_TOOLS_DESC);
        if (hasFullAccess(role)) {
            sb.append(ADMIN_ONLY_TOOLS_DESC);
        }
        sb.append(TOOL_RULES);
        if (hasFullAccess(role)) {
            sb.append(ADMIN_TOOL_EXAMPLES);
        }
        return sb.toString();
    }

    /** 生成回答阶段的 System Prompt（含角色行为约束） */
    public static String getAnswerSystemPrompt(String role) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是智慧路灯管理系统的智能助手，用中文简洁自然地回答用户问题。\n");
        sb.append("以下是系统查询到的实时数据，请基于这些数据回答用户问题。\n");
        sb.append("如果数据中有 error 字段，请友好地告知用户查询出了问题。\n");
        sb.append("不要编造数据中没有的信息。\n");
        if (!hasFullAccess(role)) {
            sb.append("\n注意：你是市政人员角色，不具备设备管理和告警处理权限。如果用户要求执行这些操作，请回复：您的权限暂不支持此操作，请联系路灯管理员处理。\n");
        }
        return sb.toString();
    }

    /** 生成直接回答（无工具调用）阶段的 System Prompt */
    public static String getDirectSystemPrompt(String role) {
        if (hasFullAccess(role)) {
            return "你是智慧路灯管理系统的智能助手，拥有全部管理权限，可以进行设备管理（添加、修改、删除设备）和告警处理。\n"
                    + "你可以帮助用户了解系统状态、分析数据、解答问题、执行管理操作。\n"
                    + "用中文简洁自然地回答用户问题。";
        }
        return "你是智慧路灯系统的智能助手，你的职责是帮助市政人员监测设备、查看数据、控制路灯和调整阈值。\n"
                + "用中文简洁自然地回答用户问题。\n"
                + "如果用户提出的问题涉及设备管理或告警处理，请礼貌地回复：您的权限暂不支持此操作，请联系路灯管理员处理。";
    }

    private ToolPermissionConfig() {}
}
