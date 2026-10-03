package com.evops.papercomp.vo;

import lombok.Data;

/**
 * 配比桶可用性（预检/池不足提示）：需求 vs 当前可用。
 */
@Data
public class QuotaAvailability {
    private String knowledgePoint;
    private String difficulty;
    private Integer required;
    private Integer available;
    /** available >= required */
    private boolean sufficient;
}
