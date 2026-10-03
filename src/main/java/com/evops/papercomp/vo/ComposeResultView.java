package com.evops.papercomp.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 组卷执行结果：批次概要 + 已生成卷面 + 重抽/池不足提示。
 */
@Data
public class ComposeResultView {
    private Long composeBatchId;
    private String batchNo;
    private String projectCode;
    private Integer paperCount;
    private Integer generatedCount;
    private Integer questionCountPerPaper;
    private String pickVersionNo;
    private Long baseSeed;
    private BigDecimal maxOverlapRate;
    private BigDecimal maxActualOverlapRate;
    /** COMPOSED 全部生成 / PARTIAL 部分生成（池不足或重合率压不下来） */
    private String status;
    private List<String> warnings;
    private List<PaperView> papers;
    private String summary;
}
