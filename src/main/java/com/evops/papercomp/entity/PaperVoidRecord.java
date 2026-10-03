package com.evops.papercomp.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 卷面废弃记录（只追加）：卷面只读，纠错走废弃后另组新卷。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pc_paper_void_record")
public class PaperVoidRecord extends BaseEntity {
    private Long paperId;
    private String paperNo;
    private String reason;
    private String operator;
    private LocalDateTime voidedAt;
}
