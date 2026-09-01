package com.ruoyi.waring.service;

import com.ruoyi.waring.domain.Gb28181PlaybackInfo;

public interface Gb28181PlaybackService {

    Gb28181PlaybackInfo start(String deviceId, String channelId);

    void stop(String deviceId, String channelId);
}
