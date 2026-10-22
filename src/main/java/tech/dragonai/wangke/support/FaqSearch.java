package tech.dragonai.wangke.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tech.dragonai.wangke.web.ApiException;

import java.util.ArrayList;
import java.util.List;

/**
 * 公开问答库检索。<b>走 schema 里那条 ngram 全文索引，不走 LIKE '%x%'。</b>
 *
 * <h3>为什么 LIKE 不是「慢一点」而是不能用</h3>
 * 前缀通配的 LIKE 让任何索引都用不上，只能全表扫。实测同一张表两条语句的 EXPLAIN：
 * <pre>
 * -- MATCH ... AGAINST
 *   type: fulltext   key: ft_question   rows: 1   Extra: Using where; Ft_hints: sorted
 * -- LIKE '%激活码%'
 *   type: ALL        key: NULL          rows: 6   Extra: Using where
 * </pre>
 * 关键不在这 6 行，而在于 {@code type: ALL} 的代价随行数线性长，
 * 而这个接口挂在 {@code /api/public/} 下、<b>不需要登录</b>：
 * 问答库涨到十万条时，谁都可以用一串随机字去拖库。全文索引把它变成一次索引查找。
 *
 * <h3>为什么用 NATURAL LANGUAGE MODE 而不是 BOOLEAN MODE</h3>
 * BOOLEAN MODE 会把用户输入里的 {@code + - > < ( ) ~ * " @} 当成操作符：
 * 用户搜「C++ 怎么装」会被解析成一个布尔表达式，轻则语义面目全非，
 * 重则语法错直接抛 SQL 异常 —— 而这是个公开接口，等于把一个报错开关交给了任何人。
 * 要用 BOOLEAN MODE 就必须自己写一遍转义，而转义漏一个字符不会有任何提示。
 * NATURAL LANGUAGE MODE 把整串输入当纯文本切词，参数照常用 {@code ?} 绑定，没有这层风险。
 *
 * <h3>ngram_token_size = 2 是个必须告诉用户的硬边界</h3>
 * ngram 解析器按固定 2 字切词，所以<b>单个汉字检索不到任何东西</b>（实测：'码' 命中 0 条，
 * '激活码' 命中 2 条）。不显式拦住的话，用户搜一个字得到空结果，
 * 看起来像「问答库里没有」，实际是「这个词根本没进倒排表」—— 一个不会报错的假空。
 */
@Component
public class FaqSearch {

    /** 与 MySQL 全局变量 ngram_token_size 对齐（默认 2）。小于它的检索词进不了倒排表。 */
    public static final int MIN_TERM_CHARS = 2;

    /** 一次最多回多少条。公开接口，不设上限等于允许一次把整个问答库拉走。 */
    private static final int MAX_LIMIT = 20;

    private final JdbcTemplate jdbc;

    public FaqSearch(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * @param faqId     命中的问答库条目 id，追加进 citations 时要用
     * @param score     MATCH 的相关度。<b>它不是概率</b>，不同语料下量纲不同，
     *                  只可用于同一次检索内部排序，不可拿去和任何固定阈值比较
     */
    public record Hit(long faqId, long courseId, String question, String answer, double score) {}

    /**
     * 检索。courseId 为 null 表示跨课程检索（问答库本身是公开物料，这不是越权口子）。
     *
     * <p>排序显式用 {@code ORDER BY score DESC}：MySQL 在只有 MATCH 谓词时会顺带按相关度返回，
     * 但那是优化器行为不是承诺，一旦加了别的谓词或换了版本，顺序就变成插入序而不会有任何提示。
     */
    public List<Hit> search(Long courseId, String rawQuery, int limit) {
        String q = normalize(rawQuery);
        int n = Math.max(1, Math.min(limit, MAX_LIMIT));

        StringBuilder sql = new StringBuilder(
                "SELECT id, course_id, question, answer, "
              + "       MATCH(question) AGAINST(? IN NATURAL LANGUAGE MODE) AS score "
              + "  FROM faq_entry "
              + " WHERE MATCH(question) AGAINST(? IN NATURAL LANGUAGE MODE) "
              + "   AND status='ON'");
        List<Object> args = new ArrayList<>();
        args.add(q);
        args.add(q);
        if (courseId != null) { sql.append(" AND course_id=?"); args.add(courseId); }
        sql.append(" ORDER BY score DESC, id ASC LIMIT ").append(n);

        return jdbc.query(sql.toString(), (rs, i) -> new Hit(
                rs.getLong("id"), rs.getLong("course_id"),
                rs.getString("question"), rs.getString("answer"),
                rs.getDouble("score")), args.toArray());
    }

    /**
     * 检索词规整。这里只做「能不能进倒排表」的判断，不做同义词、不做纠错 ——
     * 那些属于检索质量，做错了顶多是结果不好；这里做错了是结果恒空。
     */
    public static String normalize(String raw) {
        if (raw == null) throw ApiException.badRequest("请输入要检索的问题");
        String q = raw.strip();
        if (q.length() < MIN_TERM_CHARS)
            throw ApiException.badRequest(
                    "检索词至少要 " + MIN_TERM_CHARS + " 个字，太短的词检索不到内容");
        // 全文索引的检索串本身有长度上限（innodb_ft_max_token_size 之外还有语句长度），
        // 更实际的是：超长串对召回毫无帮助，只是让服务端白算一遍。
        if (q.length() > 100) q = q.substring(0, 100);
        return q;
    }
}
