package com.hprof.bitmap;

import lombok.Data;
import lombok.ToString;

@Data
@ToString
public class ReportBean {
    private int duplcateCount;
    private String bufferHash;
    private int width;
    private int height;
    private int bufferSize;
    private String stacks;
    private byte[] data;
}
