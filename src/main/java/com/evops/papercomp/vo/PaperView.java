package com.evops.papercomp.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 卷面视图：元信息 + 指纹 + 题目明细。
 */
@Data
public class PaperView {
    private Long id;
    private Long composeBatchId;
    private String batchNo;
    private String projectCode;
    private Integer paperIndex;
    private String paperNo;
    private Long seed;
    private Integer attemptNo;
    private Integer questionCount;
    private BigDecimal totalScore;
    private BigDecimal maxOverlapRate;
    private String fingerprint;
    private String poolSnapshotFingerprint;
    private String status;
    private String operator;
    private String composedAt;
    private List<PaperItemView> items;
}
