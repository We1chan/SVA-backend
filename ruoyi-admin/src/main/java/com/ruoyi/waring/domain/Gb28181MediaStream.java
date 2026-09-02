package com.ruoyi.waring.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Minimal, identity-free projection of one ZLM media record. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Gb28181MediaStream {
    private String vhost;
    private String app;
    private String stream;
}
