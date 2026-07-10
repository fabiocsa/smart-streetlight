package com.streetlight.service;

import com.streetlight.entity.ChatMessage;
import com.streetlight.entity.ChatSession;

import java.util.List;
import java.util.Map;

public interface ChatService {

    /** 获取用户的所有会话列表 */
    List<ChatSession> getSessions(String userId);

    /** 创建新会话 */
    ChatSession createSession(String userId, String title);

    /** 删除会话（级联删除消息） */
    void deleteSession(Long sessionId);

    /** 重命名会话 */
    ChatSession renameSession(Long sessionId, String title);

    /** 获取某会话的所有消息 */
    List<ChatMessage> getMessages(Long sessionId);

    /** 在指定会话中发送消息并获取 AI 回复 */
    Map<String, Object> sendMessage(Long sessionId, String question);

    /** 在指定会话中发送消息并获取 AI 回复（含角色信息用于 RBAC 权限控制） */
    Map<String, Object> sendMessage(Long sessionId, String question, String role);
}
