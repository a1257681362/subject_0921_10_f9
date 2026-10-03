package com.evops.papercomp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.papercomp.entity.PaperItem;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PaperItemMapper extends BaseMapper<PaperItem> {
}
