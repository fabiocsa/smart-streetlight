package com.streetlight.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ControlModeConverter implements AttributeConverter<ControlMode, String> {

    @Override
    public String convertToDatabaseColumn(ControlMode attribute) {
        return attribute.name().toLowerCase();
    }

    @Override
    public ControlMode convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        return ControlMode.valueOf(dbData.toUpperCase());
    }
}
