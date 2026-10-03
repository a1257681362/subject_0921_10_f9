package com.evops.papercomp.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 卷面题目明细：seqNo 即卷面题号；属性为组卷时刻冻结值，不随素材主表变化。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pc_paper_item")
public class PaperItem extends BaseEntity {
    private Long paperId;
    private Integer seqNo;
    private Long materialId;
    private String materialCode;
    private String knowledgePoint;
    private String difficulty;
    private String questionType;
    private BigDecimal score;
    private String stemDigest;
}
