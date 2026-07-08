package com.streetlight.service;

import com.streetlight.entity.AlarmRule;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface AlarmRuleService {

    List<AlarmRule> getAllRules();

    List<AlarmRule> getEnabledRules();

    AlarmRule getRule(Long id);

    AlarmRule createRule(AlarmRule rule);

    AlarmRule updateRule(Long id, AlarmRule updated);

    void deleteRule(Long id);

    AlarmRule toggleRule(Long id, boolean enabled);
}
