package com.campusreserve.identity.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusreserve.identity.dao.entity.IdentityUserEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IdentityUserMapper extends BaseMapper<IdentityUserEntity> {
}
