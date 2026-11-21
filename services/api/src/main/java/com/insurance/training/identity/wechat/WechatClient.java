package com.insurance.training.identity.wechat;

/**
 * 微信服务端能力抽象。
 *
 * <p>只有服务端持有 AppSecret；小程序端只传一次性 code。
 * 本地/测试环境使用 {@link MockWechatClient}，生产环境未配置真实实现时直接失败，
 * 绝不回退到「看起来成功」的假数据。
 */
public interface WechatClient {

    /** 用 wx.login 的 code 换取 openid（对应 jscode2session）。 */
    Session code2Session(String code);

    /** 用手机号授权 code 换取手机号（对应 getuserphonenumber）。 */
    String getPhoneNumber(String phoneCode);

    record Session(String openid, String unionId) {
    }
}
