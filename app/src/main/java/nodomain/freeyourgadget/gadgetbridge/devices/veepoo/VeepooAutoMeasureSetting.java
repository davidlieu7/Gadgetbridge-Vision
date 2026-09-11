package nodomain.freeyourgadget.gadgetbridge.devices.veepoo;

/**
 * Auto-measure (periodic measurement) configuration for a single metric as reported by the band
 * in its 0xB3 settings reply, or used to build a 0xB3 set command. Field names mirror the vendor
 * AutoMeasureData model. Times are absolute minutes within a day (0..1439); the interval and step
 * unit are expressed in minutes.
 */
public class VeepooAutoMeasureSetting {

    private int funType;
    private int protocolType;
    private boolean enabled;
    private int stepUnit;
    private boolean slotModify;
    private boolean intervalModify;
    private int supportStartMinute;
    private int supportEndMinute;
    private int measureInterval;
    private int currentStartMinute;
    private int currentEndMinute;

    public VeepooAutoMeasureSetting() {
    }

    public VeepooAutoMeasureSetting(VeepooAutoMeasureSetting other) {
        this.funType = other.funType;
        this.protocolType = other.protocolType;
        this.enabled = other.enabled;
        this.stepUnit = other.stepUnit;
        this.slotModify = other.slotModify;
        this.intervalModify = other.intervalModify;
        this.supportStartMinute = other.supportStartMinute;
        this.supportEndMinute = other.supportEndMinute;
        this.measureInterval = other.measureInterval;
        this.currentStartMinute = other.currentStartMinute;
        this.currentEndMinute = other.currentEndMinute;
    }

    public int getFunType() {
        return funType;
    }

    public void setFunType(int funType) {
        this.funType = funType;
    }

    public int getProtocolType() {
        return protocolType;
    }

    public void setProtocolType(int protocolType) {
        this.protocolType = protocolType;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getStepUnit() {
        return stepUnit;
    }

    public void setStepUnit(int stepUnit) {
        this.stepUnit = stepUnit;
    }

    public boolean isSlotModify() {
        return slotModify;
    }

    public void setSlotModify(boolean slotModify) {
        this.slotModify = slotModify;
    }

    public boolean isIntervalModify() {
        return intervalModify;
    }

    public void setIntervalModify(boolean intervalModify) {
        this.intervalModify = intervalModify;
    }

    public int getSupportStartMinute() {
        return supportStartMinute;
    }

    public void setSupportStartMinute(int supportStartMinute) {
        this.supportStartMinute = supportStartMinute;
    }

    public int getSupportEndMinute() {
        return supportEndMinute;
    }

    public void setSupportEndMinute(int supportEndMinute) {
        this.supportEndMinute = supportEndMinute;
    }

    public int getMeasureInterval() {
        return measureInterval;
    }

    public void setMeasureInterval(int measureInterval) {
        this.measureInterval = measureInterval;
    }

    public int getCurrentStartMinute() {
        return currentStartMinute;
    }

    public void setCurrentStartMinute(int currentStartMinute) {
        this.currentStartMinute = currentStartMinute;
    }

    public int getCurrentEndMinute() {
        return currentEndMinute;
    }

    public void setCurrentEndMinute(int currentEndMinute) {
        this.currentEndMinute = currentEndMinute;
    }

    @Override
    public String toString() {
        return "VeepooAutoMeasureSetting{funType=" + funType + ", enabled=" + enabled
                + ", interval=" + measureInterval + ", window=" + currentStartMinute + "-" + currentEndMinute + "}";
    }
}
