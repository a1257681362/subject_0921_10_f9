package com.evops.papercomp.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 素材池增删改留痕（只追加）：ADD / UPDATE / DISABLE / ENABLE。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pc_material_change")
public class MaterialChange extends BaseEntity {
    private Long materialId;
    private String code;
    private String changeType;
    private String fieldName;
    private String oldValue;
    private String newValue;
    private String reason;
    private String operator;
    private LocalDateTime changedAt;
}
