package tech.dragonai.wangke.home;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.dragonai.wangke.auth.AuthInterceptor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 学习中心导览页。
 *
 * <p>路径落在 {@code /api/home/} 下 —— 不是 {@code /api/public/}，所以 AuthInterceptor
 * 的"默认拒绝"对它生效。这一条不能写错：响应体里带着这个人有哪几门课、
 * 学到哪、考没考过，任何一个前缀手滑都会让它变成一个谁都能拉的用户画像接口。
 */
@RestController
public class HomeController {

    private final HomeService home;

    public HomeController(HomeService home) { this.home = home; }

    /**
     * 导览页要的全部数据，一次请求。
     *
     * <p><b>为什么合成一个接口而不是让前端并发调四个</b>：这是学员打开 App 的第一屏，
     * 而它的四片里有一片（测评）必须先拿到课程列表才知道要问哪几门 ——
     * 拆开就是两个来回。移动网络上那多出来的一个来回，学员看到的是白屏。
     *
     * <p>四片里任何一片取不到会是 {@code null}（不是空对象、更不是零），
     * 前端据此显示"暂时取不到"。理由见 {@link HomeService}。
     */
    @GetMapping("/api/home/overview")
    Map<String, Object> overview(HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.putAll(home.overview(AuthInterceptor.userId(req)));
        return out;
    }
}
