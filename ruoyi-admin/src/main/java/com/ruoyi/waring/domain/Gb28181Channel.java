package com.ruoyi.waring.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A GB28181 channel supplied by the SIP/GB catalog.  The three GB identifiers
 * are authoritative; the ZLM tuple is an explicit media binding only.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Gb28181Channel {
    private Long id;
    private String platformId;
    private String deviceId;
    private String channelId;
    private String name;
    private boolean catalogOnline;
    private Long zlmServerId;
    private String vhost;
    private String app;
    private String stream;
    private String playUrl;

    public Gb28181Channel(String platformId, String deviceId, String channelId, String name,
                          boolean catalogOnline, Long zlmServerId, String vhost, String app,
                          String stream, String playUrl) {
        this(null, platformId, deviceId, channelId, name, catalogOnline, zlmServerId,
            vhost, app, stream, playUrl);
    }
}
