package com.evops.papercomp.dto;

import lombok.Data;

/**
 * 抽选算法参数（发布时冻结为 JSON）。
 * - strategy：抽选策略，当前仅支持 KP_DIFFICULTY_QUOTA（按知识点×难度档配额抽题）；
 * - maxOverlapRate：套间重合率上限（0~1），后套与任一已生成套的重合率不得超过它；
 * - tries：单套超阈时的最大试抽轮数（含首轮）；
 * - allowReplacement：是否允许换种子重抽替换超阈题目（false 则首轮超阈即判失败）。
 */
@Data
public class PickParams {
    private String strategy;
    private Double maxOverlapRate;
    private Integer tries;
    private Boolean allowReplacement;
}
