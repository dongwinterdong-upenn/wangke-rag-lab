package tech.dragonai.wangke.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tech.dragonai.wangke.auth.AuthInterceptor;

/**
 * {@code /api/admin/**} 的结构性角色闸。
 *
 * <p>规范要求每个后台方法自己调一次 {@code requireRole(req,"PLATFORM_ADMIN")}，这层照做了。
 * 但「每个方法都记得写一行」是一条<b>靠人记性的保证</b>：漏写的那个接口不会报错、不会告警、
 * 功能完全正常，只是任何登录用户都能调 —— 这类缺口只有在被人用过之后才会被发现。
 * 所以这里再加一道按路径生效的闸：新加的后台接口哪怕忘了写那行，也进不来。
 *
 * <p>两道闸不是重复。方法内那行保护的是「这个方法被非 /api/admin 路径挂上去」的情况
 * （比如被人顺手加到某个已有控制器里），路径闸保护的是「新方法忘了写那行」的情况，
 * 两者各自覆盖对方的盲区。
 *
 * <p><b>不改 WebConfig</b>：Spring 会把所有 {@link WebMvcConfigurer} Bean 的注册合并，
 * 这里自带一个配置类即可，不必去动别人的文件。{@code order(100)} 保证它排在
 * {@link AuthInterceptor}（默认 0）之后 —— 反过来的话，请求还没解析出角色就来判角色，
 * 结果是所有后台请求恒定 403，而且看起来像是权限配置错了。
 */
@Component
public class AdminGuard implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        // 预检请求不带 Authorization，拦下来只会让浏览器侧的跨域调用整体失败
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) return true;
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        return true;
    }

    @Configuration
    static class Mount implements WebMvcConfigurer {
        private final AdminGuard guard;
        Mount(AdminGuard guard) { this.guard = guard; }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(guard).addPathPatterns("/api/admin/**").order(100);
        }
    }
}
