package tech.dragonai.wangke.auth;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import tech.dragonai.wangke.ItDb;
import tech.dragonai.wangke.config.WangkeProps;
import tech.dragonai.wangke.web.GlobalExceptionHandler;

import javax.sql.DataSource;
import java.util.Base64;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录/注册/验证码五个接口的<b>入参校验必须真的在跑</b>。
 *
 * <p>2026-09-02 现场：五个 record 上都写着 {@code @NotBlank}，但五个方法的参数前
 * <b>一个 {@code @Valid} 都没有</b> —— 注解在、校验没跑。畸形请求（scene 缺席）
 * 直接走到 {@code r.scene().toUpperCase()} 上 NPE，出网 500「未预期异常」。
 * 客户端读到的是"服务器坏了"，日志里是一条 ERROR 堆栈，两边都指错方向；
 * 而全局的 {@code MethodArgumentNotValidException} 处理器早就接好了，只差一个词。
 *
 * <p><b>为什么必须走 MockMvc 而不是直接 new 控制器调方法：</b>{@code @Valid} 是
 * Spring MVC 参数解析器在分发时执行的，直接调方法它根本不存在 —— 那种写法下
 * 「加没加 @Valid」测出来完全一样，判据等于没有。
 */
class AuthValidationIT {

    static DataSource ds;
    static MockMvc mvc;

    @BeforeAll
    static void boot() throws Exception {
        ds = ItDb.freshDatabase("wangke_authval_it");
        var jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var props = new WangkeProps();
        props.getSecrets().setJwtSecret(Base64.getEncoder().encodeToString(ItDb.testKey(21)));
        props.getSecrets().setCodeHashSalt("it-salt");
        // 不许真发短信：AliyunSms 是类不是接口，覆写 send
        var noSms = new AliyunSms(props) {
            @Override public String send(String phone, String tpl, Map<String, String> p) {
                return "IT-NO-SEND";
            }
        };
        var sms = new SmsCodeService(jdbc, tx, noSms, props);
        var auth = new AuthService(jdbc, tx, sms, new JwtService(props), new BCryptPasswordEncoder(4));
        // 真实分发 + 真实错误映射，缺一个测的都不是用户看到的东西
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(auth, sms))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterAll
    static void down() { ItDb.close(ds); }

    @Test
    @DisplayName("★ 空体打 /api/auth/sms：400 且说清缺什么，绝不是 500")
    void emptyBodyIsA400NotA500() throws Exception {
        mvc.perform(post("/api/auth/sms").contentType("application/json").content("{}"))
                // 先钉"不是 500"再钉"是 400"：@Valid 缺席时这里是 NPE → 500，
                // 那正是要抓的病，报错要直指它
                .andExpect(r -> {
                    if (r.getResponse().getStatus() == 500)
                        throw new AssertionError("空体打出了 500 —— @Valid 没在跑，NPE 冒到了全局处理器。"
                                + "响应：" + r.getResponse().getContentAsString());
                })
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    @DisplayName("五个接口空体全部 400：注解在而校验不跑，五处是一起漏的")
    void allFiveEndpointsValidate() throws Exception {
        for (String path : new String[]{"/api/auth/sms", "/api/auth/register",
                "/api/auth/login/code", "/api/auth/login/password", "/api/auth/password/reset"}) {
            var res = mvc.perform(post(path).contentType("application/json").content("{}"))
                    .andReturn().getResponse();
            if (res.getStatus() != 400)
                throw new AssertionError(path + " 空体回了 " + res.getStatus() + "，应当 400。响应："
                        + res.getContentAsString());
        }
    }

    @Test
    @DisplayName("阳性对照：合法请求穿得过校验并落到业务（否则上面两条只是把接口整个关了）")
    void wellFormedRequestPassesValidation() throws Exception {
        mvc.perform(post("/api/auth/sms").contentType("application/json")
                        .content("{\"phone\":\"13900000001\",\"scene\":\"LOGIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }
}
