package tech.dragonai.wangke;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code db/schema.sql} 必须把 {@code db/migrations/} 里加过的列和索引全都含进去。
 *
 * <h3>为什么要专门有这么一条</h3>
 * 两个文件的分工是：<b>schema.sql = 从零装一套的完整现状</b>，migrations = 打给已有部署的补丁。
 * 补丁写了却没回灌，两边就此分叉，而分叉<b>不会有任何报错</b> —— 老库照常跑，
 * 只有新装的库缺那几列。
 *
 * <p>真发生过一次：{@code 20260811_发票号唯一与驳回原因.sql} 给 invoice_request 加了
 * reject_reason / processed_by / uk_invoice_no，schema.sql 一直没跟上。后果两条，
 * 都不是「少几列」那么轻：
 * <ul>
 *   <li>{@code InvoiceService.mine()} 明文 SELECT reject_reason → 新装的库上直接 500，
 *       学员点开「我的 → 发票」看到的是一句系统繁忙；
 *   <li>{@code uk_invoice_no} 不在 → 同一个发票号能填进两条开票申请，
 *       账面上一张票开了两笔收入，两条记录各自看都合法、不报任何错。
 * </ul>
 *
 * <p>而当时 360 条用例全绿：集成测试全部从 schema.sql 建库，
 * 发票那批用例<b>自己在 setUp 里把缺的列补上了</b> —— 夹具替被测对象补齐环境，
 * 补的那一块就此退出量程。这条用例不碰数据库，只比对两个文件，堵的正是这个盲区。
 *
 * <p>管三类<b>有名字</b>的新增：{@code ADD COLUMN / KEY / UNIQUE KEY / INDEX / CONSTRAINT}、
 * 新建的 {@code CREATE TABLE}、新建的 {@code CREATE TRIGGER}。
 *
 * <p><b>整张新表这一类是后补的（2026-08-21）。</b>原来只比对 ADD 出来的名字，
 * 于是「迁移里 CREATE 了一张新表、schema.sql 忘了同步」这条路是敞开的 ——
 * 而它比缺一列更严重：新装的库上那张表根本不存在，任何查它的接口都是 500，
 * 且同样一个字的报错都不会有，直到有人在一套新环境上点开那个功能。
 * 加这一条的当天正好要新建 {@code lesson_section}，等于给自己先把坑堵上。
 *
 * <p>改类型、改默认值这类没有稳定名字可比的改动仍然不在射程内 ——
 * 说清楚射程，比假装它什么都管住了要好。
 */
class SchemaDriftTest {

    private static final Pattern ADDED =
            Pattern.compile("ADD\\s+(?:COLUMN|UNIQUE\\s+KEY|KEY|INDEX|CONSTRAINT)\\s+`([A-Za-z0-9_]+)`",
                    Pattern.CASE_INSENSITIVE);

    /**
     * 迁移里新建的表与触发器。
     *
     * <p>{@code IF NOT EXISTS} 是可选的：迁移写不写它是各自的风格，
     * 而这条用例关心的只是「schema.sql 里有没有这个名字」。
     */
    private static final Pattern CREATED =
            Pattern.compile("CREATE\\s+(?:TABLE|TRIGGER)\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`([A-Za-z0-9_]+)`",
                    Pattern.CASE_INSENSITIVE);

    /**
     * 迁移自己用完就删的脚手架（探针、临时表），判据是
     * <b>这个名字在文件里的最后一次出现是 DROP</b>。
     *
     * <p>为什么必须看「最后一次」而不是「有没有 DROP」：{@code trg_order_insert} 这种
     * 改体的写法正是 DROP 完再 CREATE ——「出现过 DROP 就放行」会把它一起放走，
     * 而它恰恰是必须回灌进 schema.sql 的东西。看末次则两者自然分开：
     * DROP→CREATE 的末次是 CREATE（要回灌），CREATE→DROP 的末次是 DROP（跑完库里没有它）。
     *
     * <p>这条规则是 2026-08-22 加的。当时 20260822_超管手工发码.sql 在文件头加了一个
     * {@code trg_privilege_probe} —— 它建一个空触发器再删掉，唯一作用是让「用错账号跑迁移」
     * 在拆掉任何东西之前先炸（用应用账号跑，DROP TRIGGER 会成功而 CREATE TRIGGER 报 1419，
     * DDL 不回滚，于是生产上两条正在跑的闸门凭空消失）。
     * 这类脚手架以后还会有，靠人记得往豁免名单里申报是不成立的。
     */
    private static final Pattern DROPPED =
            Pattern.compile("DROP\\s+(?:TABLE|TRIGGER)\\s+(?:IF\\s+EXISTS\\s+)?`([A-Za-z0-9_]+)`",
                    Pattern.CASE_INSENSITIVE);

    /** 这个名字在文件里最后一次出现是不是 DROP。是 → 跑完库里没有它，不该要求 schema.sql 有。 */
    private static boolean isScaffolding(String sql, String name) {
        int lastCreate = -1, lastDrop = -1;
        Matcher c = CREATED.matcher(sql);
        while (c.find()) if (c.group(1).equals(name)) lastCreate = c.start();
        Matcher d = DROPPED.matcher(sql);
        while (d.find()) if (d.group(1).equals(name)) lastDrop = d.start();
        return lastDrop > lastCreate;
    }

    /**
     * 登记在案的豁免：这个名字确实不在 schema.sql 里，但它的<b>判据</b>以别的形式在。
     *
     * <p>必须逐条写清楚「等价物在哪」，而且写的时候要真去验一遍 ——
     * 一张没有理由的豁免名单，用不了几轮就会变成「把红的都塞进去」的地方，
     * 那时这条用例就只剩一个绿灯，什么都不管了。
     */
    private static final java.util.Map<String, String> EXEMPT = java.util.Map.of(
            // 这两条是给【已有部署】补的：老库的水印上架触发器建得比 lesson_content_key 表还早，
            // 所以只验水印布局、不验内容密钥。而新装的库走 schema.sql，
            // 那里的 sp_lesson_publish_gate 里本来就有同一条 v_key_count <> 1 判据，
            // 插入侧与更新侧两个触发器都会调它。
            //
            // 2026-08-21 在一套现建的 schema.sql 库上实测过两条路径，都被同一句话拦下：
            //   ① 正片直接以 status='ON' 新建            → 「本讲尚未封装现行内容密钥…」
            //   ② 先建 DRAFT 再 UPDATE 成 ON（无密钥）    → 「本讲尚未封装现行内容密钥…」
            // 所以名字不在 schema.sql 里，但闸门在。
            "trg_lesson_content_key_gate_ins",
            "判据已内置于 schema.sql 的 sp_lesson_publish_gate（v_key_count <> 1），插入侧触发器会调它",
            "trg_lesson_content_key_gate_upd",
            "判据已内置于 schema.sql 的 sp_lesson_publish_gate（v_key_count <> 1），更新侧触发器会调它");

    @Test
    @DisplayName("★ migrations 里加过的每一列/每一个索引/每一张表，schema.sql 里都得有")
    void everyMigrationIsFoldedIntoSchema() throws IOException {
        String schema = Files.readString(Paths.get("db/schema.sql"), StandardCharsets.UTF_8);

        List<String> drift = new ArrayList<>();
        try (Stream<Path> files = Files.list(Paths.get("db/migrations"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".sql")).sorted().toList()) {
                String sql = Files.readString(f, StandardCharsets.UTF_8);
                Set<String> names = new LinkedHashSet<>();
                for (Pattern p : List.of(ADDED, CREATED)) {
                    Matcher m = p.matcher(sql);
                    while (m.find()) names.add(m.group(1));
                }
                for (String n : names) {
                    // 比对整串带反引号的名字，避免 `status` 这种短名在注释里撞出假绿
                    if (schema.contains("`" + n + "`") || EXEMPT.containsKey(n)) continue;
                    if (isScaffolding(sql, n)) continue;      // 迁移自己用完就删的，见 DROPPED
                    drift.add(f.getFileName() + " 加的 `" + n + "` 不在 schema.sql 里");
                }
            }
        }

        assertTrue(drift.isEmpty(),
                "schema.sql 与 migrations 分叉了。老库有、新装的库没有，"
              + "而两边都不会报错 —— 只有新装的那套会在某个查询上 500：\n  "
              + String.join("\n  ", drift));
    }

    /**
     * 豁免名单也要被看着。
     *
     * <p>某条豁免的名字后来真的进了 schema.sql（或者那条迁移被删了），它就该从名单里退场。
     * 不清的话名单只会越攒越长，而每一条都写着一个已经不成立的理由 ——
     * 下一个人照着那些理由去判断「往名单里加一条是不是安全的」。
     */
    @Test
    @DisplayName("豁免名单里不许留已经用不上的条目")
    void exemptionsStayJustified() throws IOException {
        String schema = Files.readString(Paths.get("db/schema.sql"), StandardCharsets.UTF_8);
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.list(Paths.get("db/migrations"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".sql")).sorted().toList())
                all.append(Files.readString(f, StandardCharsets.UTF_8));
        }
        List<String> stale = new ArrayList<>();
        for (var e : EXEMPT.entrySet()) {
            if (schema.contains("`" + e.getKey() + "`"))
                stale.add(e.getKey() + " 已经在 schema.sql 里了，不需要豁免");
            if (!all.toString().contains("`" + e.getKey() + "`"))
                stale.add(e.getKey() + " 在 migrations 里已经不存在了");
        }
        assertTrue(stale.isEmpty(), "豁免名单该清理了：\n  " + String.join("\n  ", stale));
    }
}
