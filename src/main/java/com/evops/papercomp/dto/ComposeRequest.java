package com.evops.papercomp.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;

/**
 * 多套并行组卷请求。
 * - projectCode：素材池/算法所属项目（缺省 DEFAULT）；
 * - examBatchId：可空，关联考试批次；
 * - paperCount：并行生成套数；
 * - quotas：知识点×难度档配比（每套相同）；
 * - seed：基础随机种子，可空（系统取确定性种子）；同种子+同池快照+同配比可完整复算；
 * - maxOverlapRate：可空，覆盖算法版本阈值（须在 [0,1]）；不传用生效算法版本/默认值。
 */
@Data
public class ComposeRequest {
    private String projectCode;
    private Long examBatchId;
    @NotNull(message = "组卷套数不能为空")
    private Integer paperCount;
    @NotEmpty(message = "配比清单不能为空")
    @Valid
    private List<QuotaRequest> quotas;
    private Long seed;
    private Double maxOverlapRate;
    private String remark;
}
