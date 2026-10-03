package com.evops.papercomp.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 组卷批次：一次多套并行；方案（配比、套数、阈值、种子、素材池快照）冻结，卷面生成后不再变。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pc_compose_batch")
public class ComposeBatch extends BaseEntity {
    private String batchNo;
    private String projectCode;
    /** 可空：仅组卷不排考 */
    private Long examBatchId;
    private Integer paperCount;
    private Integer questionCountPerPaper;
    private Long pickVersionId;
    private String pickVersionNo;
    private Long baseSeed;
    /** 配比清单冻结 JSON */
    private String quotas;
    /** 可用素材池快照 JSON（配比覆盖范围内） */
    private String poolSnapshot;
    private String poolSnapshotFingerprint;
    private BigDecimal maxOverlapRate;
    /** COMPOSED / PARTIAL */
    private String status;
    private Integer generatedCount;
    private BigDecimal maxActualOverlapRate;
    private String warning;
    private String operator;
    private String remark;
    private LocalDateTime composedAt;
}
