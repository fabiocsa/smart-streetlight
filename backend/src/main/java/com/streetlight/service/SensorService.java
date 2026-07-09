package com.streetlight.service;

import com.streetlight.dto.SensorFrequencyRequest;
import com.streetlight.dto.SensorRequest;
import com.streetlight.dto.SensorUpdateRequest;
import com.streetlight.entity.Sensor;

import java.util.List;
import java.util.Optional;

public interface SensorService {

    /**
     * 获取设备的所有传感器
     */
    List<Sensor> getSensorsByDeviceId(String deviceId);

    /**
     * 获取单个传感器
     */
    Optional<Sensor> getSensorById(Long id);

    /**
     * 绑定传感器到设备
     */
    Sensor addSensor(String deviceId, SensorRequest request);

    /**
     * 更新传感器配置
     */
    Sensor updateSensor(Long id, SensorUpdateRequest request);

    /**
     * 解绑传感器
     */
    void deleteSensor(Long id);

    /**
     * 调整传感器上报频率
     */
    Sensor updateFrequency(Long id, SensorFrequencyRequest request);

    /**
     * 同步设备的传感器配置到模拟器（通过MQTT）
     */
    int syncToMock(String deviceId);

    /**
     * 获取所有传感器（含未绑定设备的传感器）
     */
    List<Sensor> getAllSensors();

    /**
     * 解绑传感器（设 device_id = NULL，不删除记录）
     */
    Sensor unbindSensor(Long id);

    /**
     * 传感器换绑到另一设备
     */
    Sensor rebindSensor(Long id, String newDeviceId);
}
