package com.evops.papercomp.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 素材池题目：组卷只从 ACTIVE 题目抽；停用不物理删除，历史卷面靠冻结快照自洽。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pc_material")
public class Material extends BaseEntity {
    /** 题目业务编码，项目内唯一（复算定位用） */
    private String code;
    private String projectCode;
    private String knowledgePoint;
    /** EASY / MEDIUM / HARD */
    private String difficulty;
    /** SINGLE / MULTI / JUDGE / FILL / SHORT */
    private String questionType;
    private BigDecimal score;
    private String stemDigest;
    /** ACTIVE / DISABLED */
    private String status;
    private String remark;
}
