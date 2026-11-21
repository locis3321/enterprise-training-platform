package com.insurance.training.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置。
 *
 * <p>分页插件是必须的：所有列表接口统一走 {@code Page} 对象，禁止手写 limit/offset 拼接。
 * {@code maxLimit} 与 {@code app.page.max-size} 保持一致，防止前端传入超大 pageSize 拖垮数据库。
 *
 * <p>注意：MyBatis-Plus 不能绕过数据权限条件（见 AGENTS.md），
 * 数据范围过滤将在 Task 02 通过 Service 层显式条件 + 拦截器实现，不得依赖前端传参。
 */
@Configuration
@MapperScan("com.insurance.training.**.mapper")
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(200L);
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
