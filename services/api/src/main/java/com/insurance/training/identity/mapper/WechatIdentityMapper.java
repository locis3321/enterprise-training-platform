package com.insurance.training.identity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insurance.training.identity.entity.WechatIdentity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WechatIdentityMapper extends BaseMapper<WechatIdentity> {
}
