package com.evops.papercomp.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 组卷试抽痕迹：每次自动重抽一条（含被否套），证明重合率超阈自动重抽确实发生、为何被否。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pc_compose_attempt")
public class ComposeAttempt extends BaseEntity {
    private Long composeBatchId;
    private Integer paperIndex;
    private Integer attemptNo;
    private Long seed;
    private String pickedCodes;
    private BigDecimal overlapRate;
    /** ACCEPTED / REJECTED_OVERLAP / REJECTED_POOL */
    private String result;
}
