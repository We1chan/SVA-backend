package com.ruoyi.waring.domain;

/**
 * GB28181 点播会话信息。
 *
 * <p>模块：流媒体协议组 / GB28181 取流适配。该对象只承载 WVP 启动点播后
 * 返回的媒体标识与播放地址，不负责设备在线状态或会话生命周期管理。</p>
 */
public class Gb28181PlaybackInfo {

    private String streamId;
    private String mediaServerId;
    private String playUrl;
    private String rtspUrl;

    public String getStreamId() {
        return streamId;
    }

    public void setStreamId(String streamId) {
        this.streamId = streamId;
    }

    public String getMediaServerId() {
        return mediaServerId;
    }

    public void setMediaServerId(String mediaServerId) {
        this.mediaServerId = mediaServerId;
    }

    public String getPlayUrl() {
        return playUrl;
    }

    public void setPlayUrl(String playUrl) {
        this.playUrl = playUrl;
    }

    public String getRtspUrl() {
        return rtspUrl;
    }

    public void setRtspUrl(String rtspUrl) {
        this.rtspUrl = rtspUrl;
    }
}
