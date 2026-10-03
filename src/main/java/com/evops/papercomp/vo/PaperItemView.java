package com.evops.papercomp.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 卷面题目视图（冻结属性，含题号）。
 */
@Data
public class PaperItemView {
    private Integer seqNo;
    private Long materialId;
    private String materialCode;
    private String knowledgePoint;
    private String difficulty;
    private String questionType;
    private BigDecimal score;
    private String stemDigest;
}
