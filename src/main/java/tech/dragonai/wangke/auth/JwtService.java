package tech.dragonai.wangke.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Service;
import tech.dragonai.wangke.config.WangkeProps;
import tech.dragonai.wangke.web.ApiException;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.Date;

/**
 * 登录令牌。
 *
 * <p><b>为什么带 tokenVersion：</b>JWT 是无状态的，签出去就收不回来。
 * 但「改密之后旧令牌立刻失效」和「后台把人踢下线」这两件事必须做得到 ——
 * 前者是账号被盗后用户唯一的自救手段，后者是风控的最后一招。
 * 所以签发时把 {@code sys_user.token_version} 写进 claim，校验时比对；
 * 改密/踢人只要把版本 +1，此前签出的每一张都当场作废。
 *
 * <p>代价是每次校验要读一次用户行。这不是"退化成有状态"——
 * 那一行本来就要读（要拿角色、要看是否被冻结），并没有多花一次查询。
 */
@Service
public class JwtService {

    public record Principal(long userId, String role, int tokenVersion) {}

    private final SecretKey key;
    private final long ttlMillis;
    private final String issuer;

    public JwtService(WangkeProps props) {
        byte[] raw = Base64.getDecoder().decode(props.getSecrets().getJwtSecret().trim());
        this.key = new SecretKeySpec(raw, "HmacSHA256");
        this.ttlMillis = props.getJwt().getTtlMinutes() * 60_000L;
        this.issuer = props.getJwt().getIssuer();
    }

    public String issue(long userId, String role, int tokenVersion) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .issuer(issuer)
                .subject(String.valueOf(userId))
                .claim("role", role)
                .claim("tv", tokenVersion)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMillis))
                .signWith(key)
                .compact();
    }

    /** 只做密码学与格式校验；是否被冻结、版本对不对由 {@link AuthInterceptor} 查库判定。 */
    public Principal parse(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).requireIssuer(issuer).build()
                    .parseSignedClaims(token).getPayload();
            return new Principal(Long.parseLong(c.getSubject()),
                    c.get("role", String.class),
                    c.get("tv", Integer.class) == null ? 0 : c.get("tv", Integer.class));
        } catch (JwtException | IllegalArgumentException e) {
            throw ApiException.unauthorized("登录已失效，请重新登录");
        }
    }

    public long ttlSeconds() { return ttlMillis / 1000; }
}
