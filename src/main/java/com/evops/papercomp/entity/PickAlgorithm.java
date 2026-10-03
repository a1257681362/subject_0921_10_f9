package com.evops.papercomp.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 抽选算法模块：一个项目可建一套（或多套），算法下挂多个版本。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pc_pick_algorithm")
public class PickAlgorithm extends BaseEntity {
    private String code;
    private String name;
    private String projectCode;
    private String remark;
}
