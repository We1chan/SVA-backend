package com.ruoyi.waring.service;

import com.ruoyi.waring.domain.Gb28181PtzCommand;

import java.util.Map;

/** 受控的 GB28181 云台操作边界。 */
public interface Gb28181PtzService {

    /** 校验业务设备后，经 WVP 下发 PTZ 指令。 */
    Map<String, Object> control(String apeId, Gb28181PtzCommand command);
}
