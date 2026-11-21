package com.insurance.training.identity.wechat;

import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 生产环境的真实微信客户端。
 *
 * <p>当前项目尚未取得小程序主体资质与 AppSecret，因此这里**不实现伪造调用**：
 * 任何调用都会显式失败并说明缺少的配置。取得资质后在此实现真实 HTTP 调用，
 * 业务层（WechatAuthService）无需改动。
 */
@Component
@Profile("!local & !test")
public class RealWechatClient implements WechatClient {

    private static final Logger log = LoggerFactory.getLogger(RealWechatClient.class);

    public RealWechatClient() {
        log.warn("RealWechatClient 尚未实现微信开放接口调用（缺少小程序资质/AppSecret），"
                + "生产环境调用登录接口将失败，这是预期行为");
    }

    @Override
    public Session code2Session(String code) {
        throw notConfigured();
    }

    @Override
    public String getPhoneNumber(String phoneCode) {
        throw notConfigured();
    }

    private BizException notConfigured() {
        return new BizException(ErrorCode.CONFLICT,
                "微信小程序尚未完成资质与密钥配置，无法完成登录，请联系平台管理员");
    }
}
