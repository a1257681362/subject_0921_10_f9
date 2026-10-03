package com.evops.papercomp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.papercomp.entity.Paper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PaperMapper extends BaseMapper<Paper> {
}
