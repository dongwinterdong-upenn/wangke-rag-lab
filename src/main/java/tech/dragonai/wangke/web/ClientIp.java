package tech.dragonai.wangke.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 取客户端真实 IP。
 *
 * <p>nginx 在前，直接拿 {@code getRemoteAddr()} 得到的永远是 127.0.0.1 ——
 * 那会让风控的不可能旅行、播放令牌的网段绑定、短信限流的来源统计<b>全部失效</b>，
 * 而且失效得很安静：所有人看起来都来自同一个 IP，规则照常跑，只是一条都不会触发。
 *
 * <p><b>只信最左边那一跳，且只在可信代理之后。</b>X-Forwarded-For 是客户端可伪造的头，
 * 客户端自己塞一个 "X-Forwarded-For: 1.2.3.4" 进来，nginx 会把它保留并追加真实 IP。
 * 所以要取的是<b>最后一个</b>（nginx 追加的那个），不是最前面那个 ——
 * 取最前面等于让客户端随意声明自己的 IP，风控就成了摆设。
 */
public final class ClientIp {

    private ClientIp() {}

    public static String of(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            String last = parts[parts.length - 1].trim();
            if (!last.isEmpty()) return last;
        }
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) return real.trim();
        String remote = req.getRemoteAddr();
        return remote == null ? "" : remote;
    }
}
