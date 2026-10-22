package tech.dragonai.wangke.exam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 直连 {@code https://api.deepseek.com/chat/completions}。
 *
 * <h3>三条实测出来的纪律（规格第零节，别再试一遍）</h3>
 * <ol>
 *   <li><b>它默认开思考</b>：回包里 {@code message.content} 与 {@code message.reasoning_content}
 *       是两个字段。{@code max_tokens} 给小了会<b>全被思考吃掉</b> ——
 *       {@code content} 返回空串、{@code finish_reason=length}。所以正文只读 {@code content}，
 *       并且启动期就把 {@code max-tokens < 2600} 拦掉（见 AppConfig）。
 *       读 reasoning_content 当正文是最坏的一种错：那是模型的草稿，
 *       里面有"先假设他第 1 讲没看"这类<b>自问自答的假设</b>，学员会当成结论。
 *   <li><b>{@code finish_reason != "stop"} 一律当失败</b>。半截报告读起来和完整的一样通顺，
 *       只是最后那段行动清单没了，而没有任何人会发现。
 *   <li><b>未配置 key = 这套环境没有报告功能</b>，直接失败并写明理由，
 *       <b>绝不降级成模板文案</b>：一段"根据你的表现，建议加强练习"的套话与真报告长得一样，
 *       学员分不出来，平台自己也再也查不出哪几份是假的。
 * </ol>
 *
 * <h3>为什么解析单独拆成 {@link #parse}</h3>
 * "什么算说完了"这道判据是这个类的全部价值，而它只吃三个输入（HTTP 状态、响应体、配置的模型 id）。
 * 拆成静态方法之后，半截回包 / 空正文 / 只有 reasoning_content 这三种坏回包
 * 能在<b>不打网络</b>的情况下逐条钉住。判据不落在真实调用上，是因为真实调用复现不出它们。
 */
public class DeepSeekHttpClient implements DeepSeekClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekHttpClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 未配置凭据时的那句话。测试按它断言"没降级成模板文案"，所以是常量不是字面量。 */
    static final String NO_KEY =
            "本平台未配置测评报告所需的模型凭据，暂时无法生成 AI 点评（你的成绩与错题不受影响）";

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxTokens;
    private final int timeoutSeconds;
    private final HttpClient http;

    public DeepSeekHttpClient(String apiKey, String baseUrl, String model,
                              int maxTokens, int timeoutSeconds) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = (baseUrl == null || baseUrl.isBlank() ? "https://api.deepseek.com" : baseUrl.trim())
                .replaceAll("/+$", "");
        this.model = model == null || model.isBlank() ? "deepseek-v4-flash" : model.trim();
        this.maxTokens = maxTokens;
        this.timeoutSeconds = timeoutSeconds;
        // 连接超时与整次调用的超时是两件事：真实一次生成 19.3 秒，
        // 而连不上应该几秒内就知道，不该也等 120 秒。
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** 没配 key = 这套环境没有报告功能（本地开发与集成测试都是这样）。 */
    public boolean enabled() { return !apiKey.isEmpty(); }

    @Override
    public Reply complete(String systemPrompt, String userPrompt) {
        if (!enabled()) return Reply.fail(NO_KEY);
        String body;
        try {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("model", model);
            payload.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)));
            // 思考调到最低：报告是"照着事实清单说话"，不是解数学题。
            // 开高了只会把 token 预算烧在草稿上，然后正文被 length 截断。
            payload.put("reasoning_effort", "low");
            payload.put("max_tokens", maxTokens);
            payload.put("stream", false);
            body = JSON.writeValueAsString(payload);
        } catch (Exception e) {
            return Reply.fail("报告请求组装失败，请稍后重新生成");
        }

        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return parse(resp.statusCode(), resp.body(), model);
        } catch (Exception e) {
            // 具体异常写日志，不进 fail_reason：那句话是给学员看的，
            // 一句 java.net.http.HttpTimeoutException 对他没有任何意义。
            log.warn("DeepSeek 调用失败：{}", e.toString());
            return Reply.fail("模型服务暂时调不通（超时或网络不通），请稍后重新生成");
        }
    }

    /**
     * 把一次回包判成"完整正文"或"失败"。
     *
     * @param fallbackModel 回包里没带 model 时落库用的 id（就是我们请求的那个）
     */
    static Reply parse(int status, String rawBody, String fallbackModel) {
        if (status != 200)
            // 不把响应体拼进理由：里面可能带着请求 id 之外的内部信息，而这句话要出网给学员看。
            return Reply.fail("模型服务返回了 " + status + "，请稍后重新生成");
        JsonNode root;
        try {
            root = JSON.readTree(rawBody == null ? "" : rawBody);
        } catch (Exception e) {
            return Reply.fail("模型返回的内容无法解析，已丢弃本次生成");
        }
        JsonNode choice = root.path("choices").path(0);
        if (choice.isMissingNode() || choice.isNull())
            return Reply.fail("模型没有返回任何内容，已丢弃本次生成");

        String finish = choice.path("finish_reason").asText("");
        // ★ 顺序要紧：先判 finish_reason 再判正文空。
        // length 截断时 content 往往<b>也是空的</b>（额度全被思考吃掉），
        // 反过来判的话，学员看到的理由永远是"没写出正文"，
        // 而真正该改的是 max_tokens —— 理由指错了地方，问题就永远修不掉。
        if (!"stop".equals(finish))
            return Reply.fail("模型这次只写了半截（finish_reason=" + (finish.isEmpty() ? "空" : finish)
                    + "），已丢弃：半截报告读起来和完整的一样通顺");

        // 只读 content。reasoning_content 是它的草稿，不是正文。
        String content = choice.path("message").path("content").asText("");
        if (content.isBlank())
            return Reply.fail("模型这次没有写出正文，已丢弃本次生成");

        String model = root.path("model").asText("");
        return Reply.of(content.trim(), model.isBlank() ? fallbackModel : model);
    }
}
