package com.ruoyi.waring.domain;

/**
 * 设备监控启停动作的统一业务结果。
 *
 * <p>只承载三条信息：{@code success} 是否成功、{@code shortMessage} 面向前端的
 * 简短提示、{@code data} 最新设备快照。HTTP 响应包装由 Controller 负责，本类
 * 不引入 AjaxResult，保持纯业务 POJO。</p>
 */
public class DeviceMonitorResult {

    private boolean success;
    private String shortMessage;
    private HDevice data;

    public DeviceMonitorResult() {
    }

    public DeviceMonitorResult(boolean success, String shortMessage, HDevice data) {
        this.success = success;
        this.shortMessage = shortMessage;
        this.data = data;
    }

    public static DeviceMonitorResult ok(HDevice device) {
        return new DeviceMonitorResult(true, null, device);
    }

    public static DeviceMonitorResult fail(String shortMessage, HDevice device) {
        return new DeviceMonitorResult(false, shortMessage, device);
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getShortMessage() {
        return shortMessage;
    }

    public void setShortMessage(String shortMessage) {
        this.shortMessage = shortMessage;
    }

    public HDevice getData() {
        return data;
    }

    public void setData(HDevice data) {
        this.data = data;
    }
}
