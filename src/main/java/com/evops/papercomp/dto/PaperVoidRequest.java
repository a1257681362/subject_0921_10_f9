package com.evops.papercomp.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class PaperVoidRequest {
    @NotBlank(message = "废弃原因不能为空")
    private String reason;
}
