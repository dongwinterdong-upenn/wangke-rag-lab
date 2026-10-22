package tech.dragonai.wangke.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.dragonai.wangke.config.WangkeProps;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 阿里云短信。手写 RPC 签名而不引 SDK —— 这个签名只有二十行，
 * 而 SDK 会拖进一整套依赖树。（支付宝那边相反：验签写错的代价是钱，所以用官方实现。）
 *
 * <p><b>发送失败必须抛，不能吞。</b>吞掉的后果是前端显示"验证码已发送"，
 * 用户等一分钟、再点一次、再等一分钟，最后放弃注册 —— 而平台侧一条错误日志都没有，
 * 只能看到一个转化率莫名其妙的注册漏斗。
 */
@Component
public class AliyunSms {

    private static final Logger log = LoggerFactory.getLogger(AliyunSms.class);
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final WangkeProps.Sms cfg;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public AliyunSms(WangkeProps props) {
        this.cfg = props.getSms();
    }

    /** @return 阿里云回执号 BizId；用户投诉"没收到"时要拿它去查 */
    public String send(String phone, String templateCode, Map<String, String> params) {
        Map<String, String> p = new TreeMap<>();
        p.put("AccessKeyId", cfg.getAccessKeyId());
        p.put("Action", "SendSms");
        p.put("Format", "JSON");
        p.put("RegionId", cfg.getRegion());
        p.put("SignatureMethod", "HMAC-SHA1");
        p.put("SignatureNonce", UUID.randomUUID().toString());
        p.put("SignatureVersion", "1.0");
        p.put("Timestamp", ISO.format(Instant.now()));
        p.put("Version", "2017-05-25");
        p.put("PhoneNumbers", phone);
        p.put("SignName", cfg.getSignName());
        p.put("TemplateCode", templateCode);
        p.put("TemplateParam", toJson(params));

        StringBuilder canon = new StringBuilder();
        for (var e : p.entrySet()) {
            if (canon.length() > 0) canon.append('&');
            canon.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        String sts = "POST&" + enc("/") + "&" + enc(canon.toString());
        p.put("Signature", hmacSha1(cfg.getAccessKeySecret() + "&", sts));

        StringBuilder body = new StringBuilder();
        for (var e : p.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }

        HttpResponse<String> resp;
        try {
            resp = http.send(HttpRequest.newBuilder(URI.create("https://dysmsapi.aliyuncs.com/"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("短信网关不可达：" + e.getMessage(), e);
        }

        String json = resp.body();
        String code = jsonField(json, "Code");
        if (!"OK".equals(code)) {
            // 手机号在日志里要打码：日志会被转发、被检索、被贴进工单
            log.warn("短信发送失败 phone={} template={} code={} msg={}",
                    maskPhone(phone), templateCode, code, jsonField(json, "Message"));
            throw new SmsException(code, jsonField(json, "Message"));
        }
        return jsonField(json, "BizId");
    }

    public static class SmsException extends RuntimeException {
        private final String code;
        public SmsException(String code, String msg) {
            super("短信发送失败（" + code + "）：" + msg);
            this.code = code;
        }
        public String code() { return code; }
    }

    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return "***";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    // ---- 小工具：只为一个扁平字符串 map，不值得引 Jackson ----

    private static String toJson(Map<String, String> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(esc(e.getKey())).append("\":\"").append(esc(e.getValue())).append('"');
        }
        return sb.append('}').toString();
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 只用于读阿里云回包里那几个扁平字段，不做通用 JSON 解析。 */
    static String jsonField(String json, String field) {
        String needle = "\"" + field + "\"";
        int i = json.indexOf(needle);
        if (i < 0) return null;
        int c = json.indexOf(':', i + needle.length());
        if (c < 0) return null;
        int s = json.indexOf('"', c + 1);
        if (s < 0) return null;
        int e = json.indexOf('"', s + 1);
        return e < 0 ? null : json.substring(s + 1, e);
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8)
                .replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }

    private static String hmacSha1(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("签名失败", e);
        }
    }
}
