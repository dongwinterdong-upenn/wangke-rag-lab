package tech.dragonai.wangke.web;

/**
 * 带 HTTP 状态的业务异常。
 *
 * <p>message 是<b>要给用户看的</b>，所以永远不许把内部细节（SQL、堆栈、表名、
 * 别人的手机号）拼进去。需要排查的信息走日志，不走响应体。
 */
public class ApiException extends RuntimeException {

    private final int status;
    private String reason;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() { return status; }

    /**
     * 机器可读的拒绝原因。<b>只在调用方需要按原因分支时才设</b>，默认 null。
     *
     * <p>message 是给人看的，随时可能被改文案；靠 message 做分支的前端会在
     * 某次纯文案改动之后<b>静默失效</b>。所以「前端要据此走另一条路」的拒绝，
     * 必须另给一个不会随文案变的标识。
     *
     * <p>反过来也成立：不需要分支的拒绝<b>不要</b>设它 —— 每多一个 reason
     * 就多一个前端可能去 if 的东西，而绝大多数拒绝前端只需要原样显示那句话。
     */
    public String reason() { return reason; }

    public ApiException withReason(String r) { this.reason = r; return this; }

    public static ApiException badRequest(String m) { return new ApiException(400, m); }
    public static ApiException unauthorized(String m) { return new ApiException(401, m); }
    public static ApiException forbidden(String m) { return new ApiException(403, m); }
    public static ApiException notFound(String m) { return new ApiException(404, m); }
    public static ApiException conflict(String m) { return new ApiException(409, m); }
}
