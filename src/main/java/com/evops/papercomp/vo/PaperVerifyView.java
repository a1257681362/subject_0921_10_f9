package com.evops.papercomp.vo;

import lombok.Data;

/**
 * 卷面公平性复算结果：用组卷时刻冻结的池快照+种子+配比重算，与存档指纹比对。
 */
@Data
public class PaperVerifyView {
    private String paperNo;
    private String storedFingerprint;
    /** 按冻结输入重算得到的指纹 */
    private String recomputedFingerprint;
    /** 重算入选题 code 序列与卷面存档是否逐题一致 */
    private boolean itemsMatch;
    /** 指纹是否一致（一致即抽取过程可复算、卷面未被篡改） */
    private boolean verified;
    /** 使用的种子/算法版本/池快照指纹，便于人工核对 */
    private Long seed;
    private String pickVersionNo;
    private String poolSnapshotFingerprint;
    private String message;
}
