package com.streetlight.service.impl;

import com.streetlight.common.BusinessException;
import com.streetlight.entity.AlarmRule;
import com.streetlight.repository.AlarmRuleRepository;
import com.streetlight.service.AlarmRuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AlarmRuleServiceImpl implements AlarmRuleService {

    private final AlarmRuleRepository alarmRuleRepository;

    @Override
    public List<AlarmRule> getAllRules() {
        return alarmRuleRepository.findAll();
    }

    @Override
    public List<AlarmRule> getEnabledRules() {
        return alarmRuleRepository.findByEnabledTrue();
    }

    @Override
    public AlarmRule getRule(Long id) {
        return alarmRuleRepository.findById(id)
                .orElseThrow(() -> new BusinessException("告警规则不存在, id=" + id));
    }

    @Override
    @Transactional
    public AlarmRule createRule(AlarmRule rule) {
        if (rule.getEnabled() == null) rule.setEnabled(true);
        if (rule.getSeverity() == null) rule.setSeverity("WARNING");
        if (rule.getDurationSec() == null) rule.setDurationSec(30);
        AlarmRule saved = alarmRuleRepository.save(rule);
        log.info("创建告警规则: name={}, type={}", saved.getName(), saved.getRuleType());
        return saved;
    }

    @Override
    @Transactional
    public AlarmRule updateRule(Long id, AlarmRule updated) {
        AlarmRule rule = getRule(id);
        rule.setName(updated.getName());
        rule.setRuleType(updated.getRuleType());
        rule.setDeviceId(updated.getDeviceId());
        rule.setSensorType(updated.getSensorType());
        rule.setMetric(updated.getMetric());
        rule.setOperator(updated.getOperator());
        rule.setThresholdValue(updated.getThresholdValue());
        rule.setDurationSec(updated.getDurationSec());
        rule.setEnabled(updated.getEnabled());
        rule.setSeverity(updated.getSeverity());
        rule.setDescription(updated.getDescription());
        AlarmRule saved = alarmRuleRepository.save(rule);
        log.info("更新告警规则: id={}, name={}", id, saved.getName());
        return saved;
    }

    @Override
    @Transactional
    public void deleteRule(Long id) {
        if (!alarmRuleRepository.existsById(id)) {
            throw new BusinessException("告警规则不存在, id=" + id);
        }
        alarmRuleRepository.deleteById(id);
        log.info("删除告警规则: id={}", id);
    }

    @Override
    @Transactional
    public AlarmRule toggleRule(Long id, boolean enabled) {
        AlarmRule rule = getRule(id);
        rule.setEnabled(enabled);
        AlarmRule saved = alarmRuleRepository.save(rule);
        log.info("{}告警规则: id={}, name={}", enabled ? "启用" : "禁用", id, saved.getName());
        return saved;
    }
}
