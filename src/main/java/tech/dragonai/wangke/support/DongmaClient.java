package tech.dragonai.wangke.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 懂妈 —— 课程答疑助手的调用面。
 *
 * <p>她跑在本机回环的 sidecar 上（127.0.0.1:18790），人设与全部课程知识点来自
 * {@code /opt/dongma/workspace} 里的三个 markdown。<b>那是唯一的定义处</b>：
 * 改口径、改边界、补知识都改那几个文件，本仓一行都不用动，也不用重新部署。
 *
 * <h3>为什么这里把流式收成一整段</h3>
 * sidecar 对外是 SSE，第一个字约 2 秒到、整段约 4 秒。但工单这一侧的写入
 * （配额自增 + 建单 + 两条消息）必须是一个事务，而事务里不能挂着一条边流边写的连接。
 * 所以这一层把流收干净再返回：<b>拿到的是「完整答案或什么都没有」，没有半截状态</b>。
 * 半截答案落进 qa_message 是最坏的一种：它看起来是一条正常回答，学员照着做，
 * 而缺掉的恰好是后半段的注意事项。
 *
 * <h3>失败一律返回空，绝不编</h3>
 * 调不通、超时、返回非 200，统统 {@code Optional.empty()} ——
 * 上游会把这一单转人工。<b>不降级成一句「暂时无法回答」塞进 AI 消息里</b>：
 * 那会让工单状态变成 AI_ANSWERED，于是这条问题再也不会进导师队列，
 * 学员的问题就这么无声无息地消失了。
 */
@Component
public class DongmaClient {

    private static final Logger log = LoggerFactory.getLogger(DongmaClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final String token;
    private final int timeoutSeconds;
    private final HttpClient http;

    public DongmaClient(
            @Value("${wangke.qa.dongma-base:http://127.0.0.1:18790}") String baseUrl,
            @Value("${wangke.qa.dongma-token:}") String token,
            @Value("${wangke.qa.dongma-timeout-seconds:60}") int timeoutSeconds) {
        this.baseUrl = baseUrl;
        this.token = token == null ? "" : token.trim();
        this.timeoutSeconds = timeoutSeconds;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    /**
     * 没配令牌 = 这套环境没有懂妈（本地开发、集成测试都是这样）。
     * 此时行为退回到接她之前：问答库没命中就转人工。
     */
    public boolean enabled() { return !token.isEmpty(); }

    /**
     * @param history 之前的来回，形如 {@code [{"role":"user","content":"…"}, …]}，可空
     * @return 完整答案；任何异常都返回空
     */
    public Optional<String> answer(String question, List<Map<String, String>> history) {
        return answerStreaming(question, history, delta -> { });
    }

    /**
     * 边收边喂。
     *
     * <h3>为什么和 {@link #answer} 是同一个方法体</h3>
     * 这里唯一真正难的不是"把字送出去"，是<b>「什么算说完了」那道判据</b> ——
     * 收尾标记、{@code finish_reason=length} 的截断、空正文、非 200、连接断在半路，
     * 五种情况各有各的表现，而判错的代价是<b>半截答案被当成完整答案</b>。
     * 拆成两份实现，两份判据必然在某次改动之后分叉，
     * 而分叉的那一侧不会报错 —— 它只是开始把半截当整段。
     * 所以流式与非流式共用这一份，差别只有一个 {@code onDelta}。
     *
     * <p><b>{@code onDelta} 只是"给用户看"，不是"这段话算数了"。</b>
     * 返回空时，前面已经喂出去的那些字<b>一个都不作数</b>：调用方必须把它们撤掉并转人工。
     * 这一条是这个方法的全部合同，写在这里而不是指望调用方自己想明白。
     *
     * @param onDelta 每来一小段就调一次。它抛异常不会打断收流（多半是客户端断开了），
     *                但会让整次调用按失败收场 —— 人都走了，答案也就没有落库的意义。
     */
    public Optional<String> answerStreaming(String question, List<Map<String, String>> history,
                                            java.util.function.Consumer<String> onDelta) {
        if (!enabled()) return Optional.empty();
        try {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("question", question);
            payload.put("history", history == null ? List.of() : history);

            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/ask"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            JSON.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<java.io.InputStream> resp =
                    http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                log.warn("懂妈返回 {}，本单转人工", resp.statusCode());
                return Optional.empty();
            }

            StringBuilder sb = new StringBuilder();
            boolean done = false;
            try (var r = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) continue;
                    var node = JSON.readTree(data);
                    if (node.hasNonNull("delta")) {
                        String d = node.get("delta").asText();
                        sb.append(d);
                        // 先喂给用户再继续收。喂失败（客户端断了）当整次失败处理：
                        // 没人在看的答案，落不落库都一样，而继续收流只是白等一个超时。
                        onDelta.accept(d);
                    }
                    if (node.path("done").asBoolean(false)) done = true;
                    if (node.hasNonNull("error")) {
                        log.warn("懂妈报错：{}", node.get("error").asText());
                        return Optional.empty();
                    }
                }
            }
            // 没收到收尾标记 = 半截。宁可转人工，也不把半截答案当成答案。
            if (!done || sb.isEmpty()) {
                log.warn("懂妈的回答不完整（done={} 长度={}），本单转人工", done, sb.length());
                return Optional.empty();
            }
            return Optional.of(sb.toString());
        } catch (Exception e) {
            log.warn("懂妈调用失败，本单转人工：{}", e.toString());
            return Optional.empty();
        }
    }
}
