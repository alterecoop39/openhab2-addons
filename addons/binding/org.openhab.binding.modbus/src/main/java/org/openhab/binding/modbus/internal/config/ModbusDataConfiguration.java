package org.openhab.binding.modbus.internal.config;

public class ModbusDataConfiguration {

    private String readStart;
    private String readTransform;
    private String readValueType;
    private Integer writeStart;
    private String writeType;
    private String writeTransform;
    private String writeValueType;
    private boolean writeMultipleEvenWithSingleRegisterOrCoil;

    public String getReadStart() {
        return readStart;
    }

    public void setReadStart(String readStart) {
        this.readStart = readStart;
    }

    public String getReadTransform() {
        return readTransform;
    }

    public void setReadTransform(String readTransform) {
        this.readTransform = readTransform;
    }

    public String getReadValueType() {
        return readValueType;
    }

    public void setReadValueType(String readValueType) {
        this.readValueType = readValueType;
    }

    public Integer getWriteStart() {
        return writeStart;
    }

    public void setWriteStart(Integer writeStart) {
        this.writeStart = writeStart;
    }

    public String getWriteType() {
        return writeType;
    }

    public void setWriteType(String writeType) {
        this.writeType = writeType;
    }

    public String getWriteTransform() {
        return writeTransform;
    }

    public void setWriteTransform(String writeTransform) {
        this.writeTransform = writeTransform;
    }

    public String getWriteValueType() {
        return writeValueType;
    }

    public void setWriteValueType(String writeValueType) {
        this.writeValueType = writeValueType;
    }

    public boolean isWriteMultipleEvenWithSingleRegisterOrCoil() {
        return writeMultipleEvenWithSingleRegisterOrCoil;
    }

    public void setWriteMultipleEvenWithSingleRegisterOrCoil(boolean writeMultipleEvenWithSingleRegisterOrCoil) {
        this.writeMultipleEvenWithSingleRegisterOrCoil = writeMultipleEvenWithSingleRegisterOrCoil;
    }

}
