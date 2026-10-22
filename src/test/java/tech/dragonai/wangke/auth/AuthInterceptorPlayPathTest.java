package tech.dragonai.wangke.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tech.dragonai.wangke.web.ApiException;

import static org.junit.jupiter.api.Assertions.*;

class AuthInterceptorPlayPathTest {

    private final AuthInterceptor interceptor = new AuthInterceptor(null, null);

    @Test
    void hlsPlaylistAndKeyAllowPlayTokenWithoutPageJwt() {
        var playlist = new MockHttpServletRequest(
                "GET", "/api/play/m3u8/1");
        var key = new MockHttpServletRequest(
                "GET", "/api/play/key");

        assertTrue(interceptor.preHandle(
                playlist, new MockHttpServletResponse(), null));
        assertTrue(interceptor.preHandle(
                key, new MockHttpServletResponse(), null));
        assertNull(playlist.getAttribute(AuthInterceptor.ATTR_USER_ID));
        assertNull(key.getAttribute(AuthInterceptor.ATTR_USER_ID));
    }

    /**
     * ★ 分片这条路和播放列表、密钥是同一个理由：它是<b>播放器</b>发起的。
     * iOS 的原生 HLS 没有任何办法给分片请求加 Authorization 头，
     * hls.js 也只在密钥那一路挂了 xhrSetup。身份取自 URL 上那张一片一签的凭据。
     */
    @Test
    void segmentFetchAllowsSignedUrlWithoutPageJwt() {
        var seg = new MockHttpServletRequest("GET", "/api/play/seg/fde/l3/627_a.ts");
        assertTrue(interceptor.preHandle(seg, new MockHttpServletResponse(), null));
        assertNull(seg.getAttribute(AuthInterceptor.ATTR_USER_ID));
    }

    @Test
    void sessionAndRenewStillRequirePageJwt() {
        assertLoginRequired("POST", "/api/play/session");
        assertLoginRequired("POST", "/api/play/renew");
    }

    @Test
    void lookalikePathsAreNotAccidentallyPublic() {
        assertLoginRequired("GET", "/api/play/key-preview");
        assertLoginRequired("GET", "/api/play/m3u8");
        // ★ 前缀必须带边界。写成 startsWith("/api/play/seg") 的话，
        // 将来任何一个以 seg 开头的新接口都会跟着变成免登录的 ——
        // 而它工作得很好、没有任何提示，只是谁都能调。
        assertLoginRequired("GET", "/api/play/segments");
        assertLoginRequired("GET", "/api/play/seg");
    }

    private void assertLoginRequired(String method, String path) {
        var req = new MockHttpServletRequest(method, path);
        var e = assertThrows(ApiException.class, () ->
                interceptor.preHandle(
                        req, new MockHttpServletResponse(), null));
        assertEquals(401, e.status());
    }
}
