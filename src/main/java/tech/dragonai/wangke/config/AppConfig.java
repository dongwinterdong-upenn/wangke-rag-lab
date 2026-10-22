package tech.dragonai.wangke.config;

import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.exam.DeepSeekClient;
import tech.dragonai.wangke.exam.DeepSeekHttpClient;
import tech.dragonai.wangke.learn.ActivationService;
import tech.dragonai.wangke.protect.*;
import tech.dragonai.wangke.trade.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 装配。
 *
 * <p>这里的每一个 {@code require*} 都是<b>启动期的一次拒绝</b>。
 * 让服务在凭据缺失时照常起来，等于把故障推迟到第一笔真实交易：
 * 用空 appId 发起支付、用假密钥签发令牌、短信发不出去而前端显示"已发送"。
 * 那种故障要靠用户投诉才暴露，而暴露时钱已经收了、码已经发了。
 */
@Configuration
@EnableConfigurationProperties(WangkeProps.class)
public class AppConfig {

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager tm) {
        return new TransactionTemplate(tm);
    }

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    CodeVault codeVault(WangkeProps p) {
        return new CodeVault(
                requireKey32("wangke.secrets.code-vault-key", p.getSecrets().getCodeVaultKey()),
                requireText("wangke.secrets.code-hash-salt", p.getSecrets().getCodeHashSalt())
                        .getBytes(StandardCharsets.UTF_8));
    }

    @Bean
    PlayToken playToken(WangkeProps p) {
        return new PlayToken(requireKey32("wangke.secrets.play-hmac-secret",
                p.getSecrets().getPlayHmacSecret()));
    }

    /**
     * 分片取片凭据。与 {@link PlayToken} 同一把根密钥，但在
     * {@link SegmentToken} 的构造里先派生出一把独立子钥 —— 理由写在那里。
     */
    @Bean
    SegmentToken segmentToken(WangkeProps p) {
        return new SegmentToken(requireKey32("wangke.secrets.play-hmac-secret",
                p.getSecrets().getPlayHmacSecret()));
    }

    @Bean PricingService pricingService(JdbcTemplate j) { return new PricingService(j); }

    @Bean OrderService orderService(JdbcTemplate j, PricingService p) {
        return new OrderService(j, p);
    }

    @Bean FulfillService fulfillService(JdbcTemplate j, PricingService p,
                                        TransactionTemplate t, CodeVault v) {
        return new FulfillService(j, p, t, v);
    }

    @Bean CodeService codeService(JdbcTemplate j, CodeVault v) { return new CodeService(j, v); }

    /** 超管人工给代理发码。紧挨着 fulfillService：两者是同一条出码链上的两个入口。 */
    @Bean ManualIssueService manualIssueService(JdbcTemplate j, OrderService o, PricingService p,
                                                FulfillService f, TransactionTemplate t) {
        return new ManualIssueService(j, o, p, f, t);
    }

    /** 超管批量生成学员激活码。紧挨着 manualIssueService：两条出码入口住在一起。 */
    @Bean StudentCodeBatchService studentCodeBatchService(JdbcTemplate j, OrderService o,
                                                          PricingService p, FulfillService f,
                                                          TransactionTemplate t) {
        return new StudentCodeBatchService(j, o, p, f, t);
    }

    @Bean ActivationService activationService(JdbcTemplate j, TransactionTemplate t, CodeVault v) {
        return new ActivationService(j, t, v);
    }

    @Bean WatermarkService watermarkService(JdbcTemplate j, TransactionTemplate t) {
        return new WatermarkService(j, t);
    }

    @Bean
    AlipayClient alipayClient(WangkeProps p) {
        var a = p.getAlipay();
        requireText("wangke.alipay.app-id", a.getAppId());
        requireText("wangke.alipay.private-key", a.getPrivateKey());
        requireText("wangke.alipay.public-key", a.getPublicKey());
        requireText("wangke.alipay.notify-url", a.getNotifyUrl());
        // 商户 PID。回调三重比对（app_id / seller_id / 金额）里的一环 ——
        // 少了它，任何人用自己的支付宝收 1 分钱就能伪造出一笔"已支付"。
        requireText("wangke.alipay.seller-id", a.getSellerId());
        return new DefaultAlipayClient(a.getGateway(), a.getAppId(), a.getPrivateKey(),
                "json", "UTF-8", a.getPublicKey(), "RSA2");
    }

    /**
     * 播放链路那两项配置的<b>启动期</b>校验。
     *
     * <p>它们的消费者（LessonKeyService / PlayService）是 {@code @Service} 自动装配的，
     * 拿不到这里的 {@code require*}，于是并行建设时把校验放在了"第一次用到的时刻"。
     * 那是有意的降级，也是必须还回来的债：惰性校验意味着服务能带着一把
     * 格式不对的 KEK 正常启动、正常登录、正常下单，直到<b>第一个学员点开第一讲</b>
     * 才炸 —— 而那时报出来的是一句解密失败，看不出根因是配置。
     *
     * <p>这个 Bean 没有任何行为，它存在的唯一意义就是在启动期炸掉。
     */
    @Bean
    PlayConfigCheck playConfigCheck(WangkeProps p) {
        requireKey32("wangke.secrets.content-kek", p.getSecrets().getContentKek());
        requireText("wangke.play.cdn-base", p.getPlay().getCdnBase());
        return new PlayConfigCheck();
    }

    /** 见 {@link #playConfigCheck}。只是个校验发生过的凭证。 */
    public static final class PlayConfigCheck {}

    /**
     * 测评报告叙述层的模型调用面。
     *
     * <p><b>这里唯一不 require 的凭据。</b>key 为空是合法状态 ——
     * 它的含义是"这套环境没有报告功能"，报告会当场置 FAILED 并写明理由。
     * 按其余凭据那样起不来的话，本地开发和全部集成测试都跑不起来，
     * 而报告只是交卷之后的一个附加物：它没有的时候，学员的分数与错题照常显示。
     *
     * <p>但 {@code max-tokens} 必须在启动期拦：这个模型<b>默认开思考</b>，
     * 给小了正文会被草稿吃光（{@code content} 空、{@code finish_reason=length}），
     * 表现是"每一份报告都生成失败"，而失败理由指向的是模型不是配置 ——
     * 那种故障要靠人逐条看日志才能追到这一行。
     */
    @Bean
    DeepSeekClient deepSeekClient(WangkeProps p) {
        var d = p.getDeepseek();
        if (d.getMaxTokens() < 2600)
            throw new IllegalStateException("配置项 wangke.deepseek.max-tokens = " + d.getMaxTokens()
                    + "，至少要 2600：这个模型默认开思考，额度给小了正文会被草稿吃光，"
                    + "回包 content 为空而 finish_reason=length");
        if (d.getApiKey() == null || d.getApiKey().isBlank())
            org.slf4j.LoggerFactory.getLogger(AppConfig.class).warn(
                    "未配置 wangke.deepseek.api-key：本环境不生成 AI 测评报告，"
                  + "报告一律 FAILED 并写明理由（成绩与错题不受影响）");
        return new DeepSeekHttpClient(d.getApiKey(), d.getBaseUrl(), d.getModel(),
                d.getMaxTokens(), d.getTimeoutSeconds());
    }

    // ---------------------------------------------------------------- 校验

    static String requireText(String key, String v) {
        if (v == null || v.isBlank())
            throw new IllegalStateException(
                    "配置项 " + key + " 为空。拒绝以缺失凭据启动 —— "
                  + "起得来就意味着它会在真实流量上以一个空值工作，而那种故障要靠用户投诉才暴露");
        return v;
    }

    /** base64 编码的 32 字节密钥。长度不对必须当场炸：短密钥等于没有加密。 */
    static byte[] requireKey32(String key, String b64) {
        requireText(key, b64);
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(b64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("配置项 " + key + " 不是合法 base64", e);
        }
        if (raw.length != 32)
            throw new IllegalStateException(
                    "配置项 " + key + " 解出 " + raw.length + " 字节，要求 32 字节（AES-256 / HMAC-SHA256）");
        return raw;
    }
}
