package com.evops.papercomp.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 卷面：每套一条；生成即只读。废弃只切状态并留 t_pc_paper_void_record，不删除。
 * 指纹把"算法口径+池快照+种子+入选题"绑死，事后可验证公平。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pc_paper")
public class Paper extends BaseEntity {
    private Long composeBatchId;
    private String batchNo;
    private String projectCode;
    private Integer paperIndex;
    private String paperNo;
    private Long seed;
    private Integer attemptNo;
    private Integer questionCount;
    private BigDecimal totalScore;
    private BigDecimal maxOverlapRate;
    /** canonical 文本的 SHA-256 */
    private String fingerprint;
    private String poolSnapshotFingerprint;
    /** 入选题素材快照 JSON（自洽留档） */
    private String materialSnapshot;
    /** ACTIVE / VOID */
    private String status;
    private String operator;
    private LocalDateTime composedAt;
}
