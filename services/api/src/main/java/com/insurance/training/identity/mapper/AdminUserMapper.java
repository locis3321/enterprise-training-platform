package com.insurance.training.identity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insurance.training.identity.entity.AdminUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AdminUserMapper extends BaseMapper<AdminUser> {
}
