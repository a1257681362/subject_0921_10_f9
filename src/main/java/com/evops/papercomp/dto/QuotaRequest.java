package com.evops.papercomp.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 知识点×难度档配额：该知识点该难度档抽 count 题。
 */
@Data
public class QuotaRequest {
    @NotBlank(message = "配比知识点不能为空")
    private String knowledgePoint;
    @NotBlank(message = "配比难度档不能为空")
    private String difficulty;
    @NotNull(message = "配比题量不能为空")
    @Min(value = 1, message = "每个配比桶题量至少 1 题")
    private Integer count;
}
