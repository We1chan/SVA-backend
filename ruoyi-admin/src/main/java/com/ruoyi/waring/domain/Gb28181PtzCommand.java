package com.ruoyi.waring.domain;

/** GB28181 云台控制请求。速度统一使用 0-100 的业务百分比。 */
public class Gb28181PtzCommand {

    private String command;
    private Integer panSpeed;
    private Integer tiltSpeed;
    private Integer zoomSpeed;

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public Integer getPanSpeed() {
        return panSpeed;
    }

    public void setPanSpeed(Integer panSpeed) {
        this.panSpeed = panSpeed;
    }

    public Integer getTiltSpeed() {
        return tiltSpeed;
    }

    public void setTiltSpeed(Integer tiltSpeed) {
        this.tiltSpeed = tiltSpeed;
    }

    public Integer getZoomSpeed() {
        return zoomSpeed;
    }

    public void setZoomSpeed(Integer zoomSpeed) {
        this.zoomSpeed = zoomSpeed;
    }
}
