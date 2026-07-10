package com.streetlight.repository;

import com.streetlight.entity.Sensor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SensorRepository extends JpaRepository<Sensor, Long> {

    /**
     * 通过模拟器内部 sensorId 查找传感器（用于注册去重）。
     */
    Optional<Sensor> findBySimulatorSensorId(Long simulatorSensorId);

    /**
     * 查找所有未绑定到任何设备的传感器（sensor.id 不在 device_sensor 中）。
     */
    @Query(value = "SELECT s.* FROM sensor s WHERE s.id NOT IN (SELECT sensor_id FROM device_sensor)", nativeQuery = true)
    List<Sensor> findUnboundSensors();

    /**
     * 查找所有未绑定且处于指定启用状态的传感器。
     */
    @Query(value = "SELECT s.* FROM sensor s WHERE s.id NOT IN (SELECT sensor_id FROM device_sensor) AND s.enabled = :enabled", nativeQuery = true)
    List<Sensor> findUnboundSensorsByEnabled(@Param("enabled") Boolean enabled);

    /**
     * 通过传感器 DB ID 反查其绑定的设备 business key（如 "SL-001"）。
     * 未绑定时返回空。
     */
    @Query("SELECT d.deviceId FROM Device d JOIN d.sensors s WHERE s.id = :sensorId")
    Optional<String> findDeviceIdBySensorId(@Param("sensorId") Long sensorId);

    /**
     * 通过模拟器内部 sensorId 反查其绑定的设备 business key。
     * 用于 MQTT 数据/心跳 topic 中提取的 sensorId 是模拟器 ID 的场景。
     * 使用 native SQL 直接 JOIN 三张表，避免 JPQL @ManyToMany 映射可能的问题。
     */
    @Query(value = "SELECT d.device_id FROM device d " +
           "INNER JOIN device_sensor ds ON d.id = ds.device_id " +
           "INNER JOIN sensor s ON s.id = ds.sensor_id " +
           "WHERE s.simulator_sensor_id = :simSensorId", nativeQuery = true)
    Optional<String> findDeviceIdBySimulatorSensorId(@Param("simSensorId") Long simSensorId);

    /**
     * 批量获取所有已绑定传感器的设备 business key。
     * 返回 [sensorId, deviceId] 对，用于一次性填充 Sensor.boundDeviceId。
     */
    @Query("SELECT s.id, d.deviceId FROM Device d JOIN d.sensors s")
    List<Object[]> findAllSensorDeviceBindings();

    /**
     * 删除传感器与设备之间的所有绑定关系（device_sensor 关联表），
     * 在删除传感器之前调用，解除 FK 约束。
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM device_sensor WHERE sensor_id = :sensorId", nativeQuery = true)
    void removeDeviceBindings(@Param("sensorId") Long sensorId);

    /**
     * 查找指定设备绑定的指定类型传感器（启用状态）。
     * 用于控制指令下发：后端需要知道通过哪个传感器的 cmd 主题发送指令。
     */
    @Query("SELECT s FROM Device d JOIN d.sensors s " +
           "WHERE d.deviceId = :deviceId AND s.sensorType = :sensorType AND s.enabled = true")
    List<Sensor> findBoundSensorsByDeviceIdAndType(@Param("deviceId") String deviceId,
                                                    @Param("sensorType") String sensorType);
}
