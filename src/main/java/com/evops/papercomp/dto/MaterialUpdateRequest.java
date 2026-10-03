package com.evops.papercomp.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 素材关键属性修改（code 不可改）；任一字段为空表示不改该字段。前后值写留痕表。
 */
@Data
public class MaterialUpdateRequest {
    private String knowledgePoint;
    /** EASY / MEDIUM / HARD */
    private String difficulty;
    private String questionType;
    private BigDecimal score;
    private String stemDigest;
    private String remark;
    /** 修改原因（建议必填，留档） */
    private String reason;
}
