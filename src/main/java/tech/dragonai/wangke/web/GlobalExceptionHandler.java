package tech.dragonai.wangke.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tech.dragonai.wangke.learn.ActivationService;

import java.util.Map;

/**
 * 统一错误出口。
 *
 * <p><b>两条纪律：</b>
 * <ol>
 *   <li><b>内部细节绝不出网。</b>SQL 片段、表名、堆栈、别人的手机号，任何一样漏进响应体
 *       都是白送给攻击者的地图。要排查的信息写日志。
 *   <li><b>未预期异常一律 500 且写完整日志。</b>把未知异常映射成 400 会让调用方以为
 *       是自己参数错了，于是不上报 —— 一个真 bug 就这样被静音。
 * </ol>
 *
 * <p>数据库触发器的 SIGNAL 是个特例：它的文案是我们自己写的、面向人的
 * （"激活码已被其他账号激活。一码一人，不可转移"），且业务规则全靠它兜底，
 * 所以这类消息可以原样透出，但要剥掉 JDBC 包在外面的那层技术前缀。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e) {
        if (e.reason() == null) return ResponseEntity.status(e.status()).body(body(e.getMessage()));
        // reason 与 message 并存：message 给人看，reason 给代码分支用。
        // 只有设了 reason 的那几种拒绝才多这个键 —— 恒定带一个 null 的话，
        // non_null 会把它删掉，前端反而分不清"没有原因"和"这版后端还不给原因"。
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("ok", false);
        m.put("message", e.getMessage());
        m.put("reason", e.reason());
        return ResponseEntity.status(e.status()).body(m);
    }

    @ExceptionHandler(ActivationService.RedeemException.class)
    ResponseEntity<Map<String, Object>> redeem(ActivationService.RedeemException e) {
        // reason 一并给出：客服要能一眼分清"码被别人用了"与"你已经有这门课了"
        return ResponseEntity.status(400).body(
                Map.of("ok", false, "message", e.getMessage(), "reason", e.reason().name()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        var f = e.getBindingResult().getFieldError();
        return ResponseEntity.status(400).body(body(
                f == null ? "参数不合法" : f.getField() + " " + f.getDefaultMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> illegalArg(IllegalArgumentException e) {
        return ResponseEntity.status(400).body(body(e.getMessage()));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<Map<String, Object>> dup(DuplicateKeyException e) {
        log.warn("唯一键冲突", e);
        return ResponseEntity.status(409).body(body("该记录已存在，请勿重复提交"));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, Object>> illegalState(IllegalStateException e) {
        log.warn("业务前置条件不满足", e);
        return ResponseEntity.status(409).body(body(e.getMessage()));
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ResponseEntity<Map<String, Object>> data(org.springframework.dao.DataAccessException e) {
        String signal = triggerMessage(e);
        if (signal != null) {
            // 触发器的拒绝是业务规则，不是系统故障
            return ResponseEntity.status(409).body(body(signal));
        }
        log.error("数据库异常", e);
        return ResponseEntity.status(500).body(body("系统繁忙，请稍后重试"));
    }

    /**
     * 调用方把请求写错的四种情况，一律 400，且<b>不写 ERROR 日志</b>。
     *
     * <p>补这几条不是为了好看。少了它们，一个手抖打错的 URL、一个漏填的
     * 查询参数、一个空的请求体，都会落到最后那个 {@code Exception} 分支 →
     * 回 500「系统繁忙」并写一条完整堆栈。后果有两层：
     * <ul>
     *   <li>调用方以为是我们的服务坏了，于是不去查自己的参数；
     *   <li><b>更糟的是日志</b> —— 真正的故障会被这类噪声淹没。
     *       扫描器随手刷一遍不存在的路径，ERROR 日志就涨几千条。
     * </ul>
     *
     * <p>实机启动时 {@code GET /api/health} 就是这么回 500 的：
     * {@code spring.mvc.throw-exception-if-no-handler-found} 开着，
     * 而 {@code NoHandlerFoundException} 没有对应分支。
     */
    @ExceptionHandler({
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, Object>> badRequest(Exception e) {
        log.debug("请求格式不合法", e);
        String msg;
        if (e instanceof org.springframework.web.method.annotation
                .MethodArgumentTypeMismatchException m)
            msg = "参数 " + m.getName() + " 的格式不对";
        else if (e instanceof org.springframework.web.bind
                .MissingServletRequestParameterException m)
            msg = "缺少必填参数 " + m.getParameterName();
        else
            msg = "请求体不是合法的 JSON";
        return ResponseEntity.status(400).body(body(msg));
    }

    @ExceptionHandler(org.springframework.web.servlet.NoHandlerFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(
            org.springframework.web.servlet.NoHandlerFoundException e) {
        log.debug("无此接口 {} {}", e.getHttpMethod(), e.getRequestURL());
        return ResponseEntity.status(404).body(body("没有这个接口"));
    }

    /**
     * 路径存在但动词用错了 → 405，不是 500。
     *
     * <p>少了这一条，{@code GET /api/account/password}（它只有 @PostMapping）返回的是
     * <b>500「系统繁忙，请稍后重试」外加一条 ERROR 堆栈</b>。这有三处坏：
     * <ul>
     *   <li>对调用方，「服务器坏了」和「你动词写错了」是两个完全不同的下一步 ——
     *       前者是等和重试，后者是改代码。500 把后者伪装成了前者。
     *   <li>对值班的人，每一次这种调用都在日志里留一条 ERROR 堆栈，
     *       而它根本不是故障。真故障会被这些噪声淹掉。
     *   <li>这个异常在 handler <b>映射阶段</b>就抛了，早于 AuthInterceptor.preHandle ——
     *       所以未登录的人也能拿到它，等于把「这条路径存在」白送出去，
     *       而 404 分支本来是不区分的。
     * </ul>
     *
     * <p>这条不是假想：本轮把改密路径从 {@code PUT /api/me/password} 挪到
     * {@code POST /api/account/password}，「路径改了、动词没跟着改」正是最容易发生的那种漏改，
     * 而它的表现会是一句「系统繁忙」—— 没有人会从那句话联想到动词。
     * Allow 头按 Spring 给的支持列表原样带上，让调用方直接看见该用什么。
     */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, Object>> methodNotAllowed(
            org.springframework.web.HttpRequestMethodNotSupportedException e) {
        var supported = e.getSupportedHttpMethods();
        String allow = supported == null ? "" : supported.stream()
                .map(Object::toString).sorted().collect(java.util.stream.Collectors.joining(", "));
        log.debug("动词不支持：{}，该接口支持的是 {}", e.getMethod(), allow);
        var b = ResponseEntity.status(405);
        if (!allow.isEmpty()) b.header("Allow", allow);
        return b.body(body("这个接口不支持 " + e.getMethod() + " 方法"
                + (allow.isEmpty() ? "" : "，请用 " + allow)));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unknown(Exception e) {
        log.error("未预期异常", e);
        return ResponseEntity.status(500).body(body("系统繁忙，请稍后重试"));
    }

    /**
     * 从 JDBC 异常里取出触发器 SIGNAL 的原文。
     * MySQL 把它包成 "... Error Code: 1644 ... 我们写的那句话"，
     * 这里只取我们写的那句，技术前缀一概不出网。
     */
    public static String triggerMessage(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException se && se.getErrorCode() == 1644) {
                String m = se.getMessage();
                return m == null ? null : m.trim();
            }
        }
        return null;
    }

    private static Map<String, Object> body(String message) {
        return Map.of("ok", false, "message", message == null ? "请求失败" : message);
    }
}
