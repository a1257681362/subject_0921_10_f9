package com.evops.papercomp.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

@Data
public class MaterialRequest {
    @NotBlank(message = "题目编码不能为空")
    private String code;
    private String projectCode;
    @NotBlank(message = "知识点不能为空")
    private String knowledgePoint;
    /** EASY / MEDIUM / HARD */
    @NotBlank(message = "难度档不能为空")
    private String difficulty;
    /** SINGLE / MULTI / JUDGE / FILL / SHORT，缺省 SINGLE */
    private String questionType;
    @NotNull(message = "题目分值不能为空")
    private BigDecimal score;
    private String stemDigest;
    private String remark;
}
