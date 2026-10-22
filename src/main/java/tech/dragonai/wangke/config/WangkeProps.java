package tech.dragonai.wangke.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 全部可配项。
 *
 * <p><b>校验放在 {@link AppConfig} 的构造期，不是运行期。</b>
 * 凭据缺失、密钥长度不对这类问题，如果留到第一次调用才发现，
 * 那时服务已经在接真实流量了 —— 学员点了支付、短信发不出去、令牌用空密钥签发，
 * 每一种都是先造成损失再暴露。宁可起不来。
 */
@ConfigurationProperties(prefix = "wangke")
public class WangkeProps {

    private Secrets secrets = new Secrets();
    private Jwt jwt = new Jwt();
    private Sms sms = new Sms();
    private Alipay alipay = new Alipay();
    private Play play = new Play();
    private Course course = new Course();
    private Deepseek deepseek = new Deepseek();

    public static class Secrets {
        private String codeVaultKey, codeHashSalt, playHmacSecret, jwtSecret, contentKek;
        public String getCodeVaultKey() { return codeVaultKey; }
        public void setCodeVaultKey(String v) { this.codeVaultKey = v; }
        public String getCodeHashSalt() { return codeHashSalt; }
        public void setCodeHashSalt(String v) { this.codeHashSalt = v; }
        public String getPlayHmacSecret() { return playHmacSecret; }
        public void setPlayHmacSecret(String v) { this.playHmacSecret = v; }
        public String getJwtSecret() { return jwtSecret; }
        public void setJwtSecret(String v) { this.jwtSecret = v; }
        public String getContentKek() { return contentKek; }
        public void setContentKek(String v) { this.contentKek = v; }
    }

    public static class Jwt {
        private int ttlMinutes = 720;
        private String issuer = "wangke";
        public int getTtlMinutes() { return ttlMinutes; }
        public void setTtlMinutes(int v) { this.ttlMinutes = v; }
        public String getIssuer() { return issuer; }
        public void setIssuer(String v) { this.issuer = v; }
    }

    public static class Sms {
        private String accessKeyId, accessKeySecret, region, signName;
        private String templateLogin, templateRegister, templateReset, templateBind;
        private int cooldownSeconds = 60, perPhoneDaily = 10, globalDaily = 200;
        private int codeTtlSeconds = 300, maxAttempts = 5;
        public String getAccessKeyId() { return accessKeyId; }
        public void setAccessKeyId(String v) { this.accessKeyId = v; }
        public String getAccessKeySecret() { return accessKeySecret; }
        public void setAccessKeySecret(String v) { this.accessKeySecret = v; }
        public String getRegion() { return region; }
        public void setRegion(String v) { this.region = v; }
        public String getSignName() { return signName; }
        public void setSignName(String v) { this.signName = v; }
        public String getTemplateLogin() { return templateLogin; }
        public void setTemplateLogin(String v) { this.templateLogin = v; }
        public String getTemplateRegister() { return templateRegister; }
        public void setTemplateRegister(String v) { this.templateRegister = v; }
        public String getTemplateReset() { return templateReset; }
        public void setTemplateReset(String v) { this.templateReset = v; }
        public String getTemplateBind() { return templateBind; }
        public void setTemplateBind(String v) { this.templateBind = v; }
        public int getCooldownSeconds() { return cooldownSeconds; }
        public void setCooldownSeconds(int v) { this.cooldownSeconds = v; }
        public int getPerPhoneDaily() { return perPhoneDaily; }
        public void setPerPhoneDaily(int v) { this.perPhoneDaily = v; }
        public int getGlobalDaily() { return globalDaily; }
        public void setGlobalDaily(int v) { this.globalDaily = v; }
        public int getCodeTtlSeconds() { return codeTtlSeconds; }
        public void setCodeTtlSeconds(int v) { this.codeTtlSeconds = v; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int v) { this.maxAttempts = v; }
    }

    public static class Alipay {
        private String appId, privateKey, publicKey, gateway, notifyUrl, returnUrl, sellerId;
        public String getSellerId() { return sellerId; }
        public void setSellerId(String v) { this.sellerId = v; }
        public String getAppId() { return appId; }
        public void setAppId(String v) { this.appId = v; }
        public String getPrivateKey() { return privateKey; }
        public void setPrivateKey(String v) { this.privateKey = v; }
        public String getPublicKey() { return publicKey; }
        public void setPublicKey(String v) { this.publicKey = v; }
        public String getGateway() { return gateway; }
        public void setGateway(String v) { this.gateway = v; }
        public String getNotifyUrl() { return notifyUrl; }
        public void setNotifyUrl(String v) { this.notifyUrl = v; }
        public String getReturnUrl() { return returnUrl; }
        public void setReturnUrl(String v) { this.returnUrl = v; }
    }

    public static class Play {
        private int tokenTtlSeconds = 90;
        private String cdnBase;

        /**
         * 分片取片凭据的有效期。
         *
         * <p>播放列表只生成一次，而凭据烧在里面的每一行上，所以它必须<b>盖得住一次完整的
         * 观看</b>——最长的一讲三个多小时，中途还会暂停。过期之后播放器收到 403，
         * 前端那条 {@code 403/401 → 「播放权限已失效，请重新打开本讲」} 的分支会接住它，
         * 点一次重试就重开会话拿新列表，是自愈的。
         *
         * <p>另一头的代价是：这份列表在有效期内等于一张下载清单。但它是<b>他自己的</b>
         * 清单 —— 每一行都签着他的 user_id、只包含他那一份变体，泄漏出去反而是证据。
         * 6 小时是这两头之间的取值：没人一次看六小时，而一张票当天就死。
         */
        private int segmentTtlSeconds = 6 * 3600;

        /**
         * 用 nginx 的 {@code X-Accel-Redirect} 交付分片。
         *
         * <p>关掉之后走 Java 自己读盘回吐（{@code vod-root}），那只给本地调试用：
         * 一片几百 KB、一讲上千片，全部穿过 JVM 堆是白烧的 CPU 与 GC，
         * 而且 Range 请求要自己实现 —— iOS 原生 HLS 是会发 Range 的。
         */
        private boolean useXAccel = true;

        /** {@code X-Accel-Redirect} 指向的内部 location 前缀，必须与 nginx 里那个 {@code internal} 块一致。 */
        private String vodInternalPrefix = "/vod";

        /** 关掉 x-accel 时的片库根目录。生产留空 —— 留了值就等于给自己留了一条读盘回吐的后路。 */
        private String vodRoot;

        /** 取片速率闸的滑动窗口秒数。 */
        private int paceWindowSeconds = 180;
        /** 允许的倍率上限（相对实时播放速度）。含预取与拖动余量。 */
        private double paceMaxRate = 8.0;
        /** 窗口内不足这么多片一律放行 —— 没有这条下限，每一次开播的头几秒都会被判成下载。 */
        private int paceMinSamples = 60;
        /** 速率闸最多同时跟踪多少个 (用户,讲次)。内存上界，不是业务参数。 */
        private int paceMaxKeys = 20000;

        public int getTokenTtlSeconds() { return tokenTtlSeconds; }
        public void setTokenTtlSeconds(int v) { this.tokenTtlSeconds = v; }
        public String getCdnBase() { return cdnBase; }
        public void setCdnBase(String v) { this.cdnBase = v; }
        public int getSegmentTtlSeconds() { return segmentTtlSeconds; }
        public void setSegmentTtlSeconds(int v) { this.segmentTtlSeconds = v; }
        public boolean isUseXAccel() { return useXAccel; }
        public void setUseXAccel(boolean v) { this.useXAccel = v; }
        public String getVodInternalPrefix() { return vodInternalPrefix; }
        public void setVodInternalPrefix(String v) { this.vodInternalPrefix = v; }
        public String getVodRoot() { return vodRoot; }
        public void setVodRoot(String v) { this.vodRoot = v; }
        public int getPaceWindowSeconds() { return paceWindowSeconds; }
        public void setPaceWindowSeconds(int v) { this.paceWindowSeconds = v; }
        public double getPaceMaxRate() { return paceMaxRate; }
        public void setPaceMaxRate(double v) { this.paceMaxRate = v; }
        public int getPaceMinSamples() { return paceMinSamples; }
        public void setPaceMinSamples(int v) { this.paceMinSamples = v; }
        public int getPaceMaxKeys() { return paceMaxKeys; }
        public void setPaceMaxKeys(int v) { this.paceMaxKeys = v; }
    }

    public static class Course {
        private long defaultCourseId = 1L;
        public long getDefaultCourseId() { return defaultCourseId; }
        public void setDefaultCourseId(long v) { this.defaultCourseId = v; }
    }

    /**
     * 测评报告的叙述层用的模型（DeepSeek）。
     *
     * <p><b>{@code api-key} 是这份配置里唯一允许为空的凭据</b>，而且是有意的：
     * 空 = 这套环境没有报告功能（本地开发、集成测试都是这样），报告会直接置 FAILED
     * 并写明"未配置模型凭据"，<b>绝不降级成模板文案</b> ——
     * 一段"根据你的表现建议加强练习"的套话与真报告长得一模一样，学员分不出来，
     * 平台自己日后也查不出哪几份是假的。
     *
     * <p>其余几项都有默认值，因为它们是<b>实测出来的</b>而不是环境相关的：
     * 模型 id、2600 以上的 max_tokens（低了正文会被思考吃光）、120 秒超时（真实一次 19.3 秒）。
     */
    public static class Deepseek {
        private String apiKey = "";
        private String baseUrl = "https://api.deepseek.com";
        private String model = "deepseek-v4-flash";
        private int maxTokens = 3000;
        private int timeoutSeconds = 120;
        public String getApiKey() { return apiKey; }
        public void setApiKey(String v) { this.apiKey = v; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { this.baseUrl = v; }
        public String getModel() { return model; }
        public void setModel(String v) { this.model = v; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int v) { this.maxTokens = v; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int v) { this.timeoutSeconds = v; }
    }

    public Secrets getSecrets() { return secrets; }
    public void setSecrets(Secrets v) { this.secrets = v; }
    public Jwt getJwt() { return jwt; }
    public void setJwt(Jwt v) { this.jwt = v; }
    public Sms getSms() { return sms; }
    public void setSms(Sms v) { this.sms = v; }
    public Alipay getAlipay() { return alipay; }
    public void setAlipay(Alipay v) { this.alipay = v; }
    public Play getPlay() { return play; }
    public void setPlay(Play v) { this.play = v; }
    public Course getCourse() { return course; }
    public void setCourse(Course v) { this.course = v; }
    public Deepseek getDeepseek() { return deepseek; }
    public void setDeepseek(Deepseek v) { this.deepseek = v; }
}
