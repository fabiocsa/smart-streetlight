package com.streetlight.repository;

import com.streetlight.entity.AlarmRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AlarmRuleRepository extends JpaRepository<AlarmRule, Long> {

    /** 所有启用的规则 */
    List<AlarmRule> findByEnabledTrue();
}
