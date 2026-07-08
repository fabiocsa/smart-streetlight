package com.streetlight.repository;

import com.streetlight.entity.AlarmRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AlarmRuleRepository extends JpaRepository<AlarmRule, Long> {

    /** 所有启用的规则 */
    List<AlarmRule> findByEnabledTrue();

    /** 按规则类型查找启用的规则 */
    List<AlarmRule> findByRuleTypeAndEnabledTrue(String ruleType);

    /** 按设备查找启用的规则（含全局规则 deviceId=null） */
    List<AlarmRule> findByDeviceIdOrDeviceIdIsNullAndEnabledTrue(String deviceId);
}
