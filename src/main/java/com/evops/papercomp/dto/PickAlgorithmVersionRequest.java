package com.evops.papercomp.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

@Data
public class PickAlgorithmVersionRequest {
    @Valid
    @NotNull(message = "抽选算法参数不能为空")
    private PickParams params;
    private String remark;
}
