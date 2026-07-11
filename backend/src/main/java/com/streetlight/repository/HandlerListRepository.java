package com.streetlight.repository;

import com.streetlight.entity.HandlerList;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HandlerListRepository extends JpaRepository<HandlerList, Long> {

    Optional<HandlerList> findByHandlerName(String handlerName);

    /** 查询空闲处理人，按优先级升序（数字越小优先级越高） */
    List<HandlerList> findByIsOccupiedOrderByPriorityAsc(Integer isOccupied);

    /** 全部处理人按处理次数升序、优先级升序 */
    List<HandlerList> findAllByOrderByHandlerCountAscPriorityAsc();
}
