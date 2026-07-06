package com.streetlight.service.impl;

import com.streetlight.service.ChatService;
import org.springframework.stereotype.Service;

@Service
public class ChatServiceImpl implements ChatService {
    @Override
    public String answer(String question) {
        return "模拟回答，后续对接MaxKB";
    }
}
