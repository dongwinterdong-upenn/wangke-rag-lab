package tech.dragonai.wangke.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tech.dragonai.wangke.auth.AuthInterceptor;

/**
 * 拦截器挂载。
 *
 * <p><b>拦 {@code /api/**} 全部，白名单写在 {@link AuthInterceptor} 里。</b>
 * 反过来（只拦需要登录的路径）的话，每加一个接口都要记得去补规则，
 * 而漏补的那个接口是裸的、工作正常、没有任何提示 —— 谁都能调。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor auth;

    public WebConfig(AuthInterceptor auth) { this.auth = auth; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auth).addPathPatterns("/api/**");
    }
}
