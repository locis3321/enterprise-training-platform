package com.insurance.training.tenancy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insurance.training.tenancy.entity.Enterprise;
import com.insurance.training.tenancy.entity.InsuranceCompany;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InsuranceCompanyMapper extends BaseMapper<InsuranceCompany> {
}
