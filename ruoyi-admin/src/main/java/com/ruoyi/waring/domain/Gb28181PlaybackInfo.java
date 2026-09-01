package com.ruoyi.waring.domain;

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
