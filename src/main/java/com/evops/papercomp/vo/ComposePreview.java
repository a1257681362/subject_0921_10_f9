package com.evops.papercomp.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 组卷预检结果：可行性、池不足明细、拟用算法版本/种子/阈值，不真正落库。
 */
@Data
public class ComposePreview {
    private String projectCode;
    private Long examBatchId;
    private Integer paperCount;
    private Integer questionCountPerPaper;
    private String pickVersionNo;
    private Long baseSeed;
    private BigDecimal maxOverlapRate;
    /** 每个配比桶的当前可用题量（预检按当前素材池计算） */
    private List<QuotaAvailability> availabilities;
    /** 是否存在配比桶题量不足（硬约束） */
    private boolean poolSufficient;
    private List<String> warnings;
    private String summary;
}
