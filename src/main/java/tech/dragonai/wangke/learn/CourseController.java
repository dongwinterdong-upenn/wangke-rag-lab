package tech.dragonai.wangke.learn;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tech.dragonai.wangke.auth.AuthInterceptor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 课程目录：一个公开的、两个要权益的。
 *
 * <p><b>路径前缀决定鉴权，这不是巧合而是唯一的机制。</b>
 * AuthInterceptor 是"默认拒绝 + 白名单前缀放行"，白名单里有 {@code /api/public/}。
 * 所以公开介绍页必须落在那个前缀下，而任何需要权益的东西一旦不小心写成
 * {@code /api/public/...}，它就<b>裸了</b> —— 接口工作正常，只是谁都能调。
 * 这个类里三条路径的前缀，是三条接口安全等级的全部来源。
 */
@RestController
public class CourseController {

    private final CourseService courses;

    public CourseController(CourseService courses) { this.courses = courses; }

    /** 课程介绍。公开页，含章节讲次目录，<b>不含 hls_key、不含切片数、不含水印布局</b>。 */
    @GetMapping("/api/public/course/{id}")
    Map<String, Object> publicCourse(@PathVariable("id") long id) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("course", courses.publicCourse(id));
        return out;
    }

    /** 我的课程。含有效期与进度百分比；进度按 watched_sec 算，不按最远播放位置算。 */
    @GetMapping("/api/learn/my/courses")
    Map<String, Object> myCourses(HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("courses", courses.myCourses(AuthInterceptor.userId(req)));
        return out;
    }

    /** 讲次列表。要权益才给；无权益与课程不存在返回同一句 403，不泄露课程是否存在。 */
    @GetMapping("/api/learn/course/{id}/lessons")
    Map<String, Object> lessons(@PathVariable("id") long id, HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.putAll(courses.lessonsOf(AuthInterceptor.userId(req), id));
        return out;
    }
}
