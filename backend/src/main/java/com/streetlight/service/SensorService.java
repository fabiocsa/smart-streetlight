package com.streetlight.service;

import com.streetlight.dto.SensorFrequencyRequest;
import com.streetlight.dto.SensorRequest;
import com.streetlight.dto.SensorUpdateRequest;
import com.streetlight.entity.Sensor;

import java.util.List;
import java.util.Optional;

/**
 * 传感器服务接口 (v2)
 * 传感器独立存在，不再持有设备 ID。
 * 设备与传感器的绑定通过 DeviceService 管理。
 */
public interface SensorService {

    /** 获取所有传感器 */
    List<Sensor> getAllSensors();

    /** 获取未绑定到任何设备的传感器 */
    List<Sensor> getUnboundSensors();

    /** 获取单个传感器 */
    Optional<Sensor> getSensorById(Long id);

    /** 创建独立传感器（不绑定设备，由管理员后续绑定） */
    Sensor createSensor(SensorRequest request);

    /** 更新传感器配置 */
    Sensor updateSensor(Long id, SensorUpdateRequest request);

    /** 删除传感器（同时删除绑定关系） */
    void deleteSensor(Long id);

    /** 调整传感器上报频率 */
    Sensor updateFrequency(Long id, SensorFrequencyRequest request);

    /** 同步传感器配置到模拟器（通过 MQTT） */
    int syncToMock(Long sensorId);
}
