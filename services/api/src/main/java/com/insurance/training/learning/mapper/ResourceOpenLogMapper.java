package com.insurance.training.learning.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insurance.training.learning.entity.ResourceOpenLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 非视频资源打开登记。
 *
 * <p>幂等由唯一键 {@code (assignment_id, resource_id)} 保证，调用方用普通 {@code insert}
 * 并捕获 {@link org.springframework.dao.DuplicateKeyException} 判定「非首次」。
 *
 * <p>**不用 {@code ON DUPLICATE KEY UPDATE opened_at = opened_at} 判断首次**：
 * 该语句在 SQL 层返回 0（值未变），但 MySQL JDBC 默认 {@code useAffectedRows=false}，
 * 驱动上报的是 matched rows = 1，会把重复调用误判成首次打开。
 * 改用唯一键冲突判定，不依赖驱动语义，也不会为了一个接口改动全局 JDBC 参数
 * （那会改变所有 CAS 判断所依赖的 affected-rows 语义）。
 */
@Mapper
public interface ResourceOpenLogMapper extends BaseMapper<ResourceOpenLog> {
}