package tech.dragonai.wangke.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tech.dragonai.wangke.auth.AuthInterceptor;
import tech.dragonai.wangke.web.ApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多套试卷的后台：列卷、改规则、上下架、审题。
 *
 * <h3>路径必须落在 /api/admin/ 下</h3>
 * {@link AdminGuard} 是按路径前缀（{@code /api/admin/**}）挂的拦截器。挂到别的前缀下的话
 * 那道闸整个不生效，而接口<b>工作得好好的</b>。每个方法体第一行仍写 requireRole：
 * 两道闸互补不重复，一道是路径级的、一道是方法级的。
 *
 * <h3>★ 改规则是插 exam_config 新行，绝不 UPDATE 旧行</h3>
 * {@code exam_config} 的版本化机制是这一整块的地基：已开考的场次按
 * {@code exam_attempt.config_id} 引用<b>它开考时那一行</b>。UPDATE 旧行在库层是一次
 * 完全合法的写入（列都在、CHECK 满意、行数正确），后果是<b>正在考试的人交不上卷</b> ——
 * ExamService 按 config_id 那一行算 passed，触发器也按那一行校验，两边一起变了，
 * 而学员看到的是一句数据库触发器文案；更远一点，已经判过分的老场次再去查
 * 「当时及格线是多少」查到的是新值，历史被静默改写。
 * 所以这里只有 INSERT，没有一条 UPDATE 落在 exam_config 上。
 *
 * <h3>★ 列卷必须带题库统计，而且要把「不够」说成人话</h3>
 * 抽题是 {@code WHERE paper_id=? AND review_status='APPROVED'}，凑不满卷面题数时
 * ExamService <b>拒绝开考</b>（不能"有几道出几道"，否则 100 题的及格线配 40 道的卷子，
 * 所有人必然不及格）。也就是说「题不够」这件事的唯一表现是<b>学员点开考被拒</b> ——
 * 在他点之前，后台任何一页都不会提它一个字。这一页就是那个信号：
 * {@code enoughApproved} / {@code shortBy} / {@code blocker} 三个字段，
 * 前两个给界面标红用，第三个是原样能贴给运营看的一句话。
 *
 * <h3>题目列表是超管专用，可以带答案与解析</h3>
 * 学员那一侧的判据是「下发考卷的路径连 answer 列都不查」（ExamService.PaperQuestion
 * 里没有 answer 字段，ExamIT 会遍历整棵响应树断言不存在 answer/correct/explan）。
 * 这一页反过来 —— 审题的人看不到答案就没法审。两件事不冲突的前提是<b>这条路进不来非超管</b>：
 * 路径闸 + 方法闸两道都在，且这个控制器<b>不提供任何按 attemptId / userId 取题的入口</b>，
 * 免得哪天有人把它当成"通用取题接口"从学员侧调过来。
 */
@RestController
@RequestMapping("/api/admin/exam")
public class AdminExamController {

    /**
     * 卷面题数的上限。
     *
     * <p>不是业务规则，是<b>多打一个 0 的护栏</b>：{@code question_count} 是抽题的
     * {@code LIMIT}，写成 1000 在库层完全合法，表现是这张卷永远开不了考
     * （题库根本没那么多题），而错因要翻到抽题那一行才看得出来。
     * 真实卷面是 50 / 100 题，留三倍余量足够。
     */
    static final int MAX_QUESTION_COUNT = 300;

    /** 考试时长上限（分钟）。8 小时，离真实的 35 / 60 分钟很远，挡的同样是多打一位。 */
    static final int MAX_DURATION_MIN = 480;

    /** 冷却上限（小时）= 一年。填 87600 的话这张卷等于只能考一次，而界面显示一切正常。 */
    static final int MAX_COOLDOWN_HOURS = 8760;

    /** 审核状态白名单。{@code DRAFT} 是入库初值，不在这里出现 —— 见 {@link #review}。 */
    static final String[] REVIEW_STATES = {"APPROVED", "REJECTED", "PENDING"};

    private final JdbcTemplate jdbc;
    private final AdminExamService exams;
    private final AdminAudit audit;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public AdminExamController(JdbcTemplate jdbc, AdminExamService exams, AdminAudit audit,
                               TransactionTemplate tx, ObjectMapper json) {
        this.jdbc = jdbc; this.exams = exams; this.audit = audit; this.tx = tx; this.json = json;
    }

    /**
     * 规则改动。<b>每个字段都可以省略，省略 = 沿用现行规则的那个值</b>。
     *
     * <p>不能把缺席当成"用默认值"：运营只想把及格线从 90 改到 80 时，
     * 缺席补默认会把时长、次数、冷却、进度门槛一起打回建表默认值 ——
     * 而接口返回成功、页面刷新之后那几个数看起来也"像个正常的数"，
     * 没有任何一处会说刚才被改过。
     */
    public record RulesReq(Integer questionCount, Integer passScore, Integer durationMin,
                           Boolean shuffleOptions, Integer maxAttempts,
                           Integer retryCooldownHours, Integer requireProgressPct) {}

    /**
     * ★ 规则七项<b>嵌套写与平铺写都收</b>。
     *
     * <p>不是为了好看。{@code spring.jackson} 没开 fail-on-unknown-properties（Spring Boot 默认关），
     * 所以只收其中一种写法时，另一种写法的请求会<b>安静地反序列化成"什么都没给"</b>：
     * 接口返回 200、响应里写着「没有任何改动」、页面刷新之后规则还是老的 ——
     * 运营改了三次都以为自己点错了。管理端那一页现在发的是平铺写法，
     * 而嵌套写法读起来清楚；两种都收，谁也不会掉进那个洞。
     * 两边都给了同一项时以嵌套的为准，其余用平铺的补。
     *
     * @param subtitle 传 null = 不改；传空串 = 清空。record 分不出"JSON 里没这个键"和
     *                 "值是 null"，所以清空必须有另一个写法，否则副标题一旦填错就再也删不掉
     * @param status   {@code DRAFT} / {@code ON} / {@code OFF}；null = 不改
     * @param rules    嵌套写法。它与下面七个平铺字段全为 null 时才算"不改规则"
     */
    public record PaperReq(String title, String subtitle, Integer sortNo, String status,
                           RulesReq rules, String reason,
                           Integer questionCount, Integer passScore, Integer durationMin,
                           Boolean shuffleOptions, Integer maxAttempts,
                           Integer retryCooldownHours, Integer requireProgressPct) {

        /** 嵌套 + 平铺归一。七项全空返回 null —— 那才是"这次不改规则"。 */
        RulesReq mergedRules() {
            boolean flat = questionCount != null || passScore != null || durationMin != null
                    || shuffleOptions != null || maxAttempts != null
                    || retryCooldownHours != null || requireProgressPct != null;
            if (rules == null) return flat ? new RulesReq(questionCount, passScore, durationMin,
                    shuffleOptions, maxAttempts, retryCooldownHours, requireProgressPct) : null;
            if (!flat) return rules;
            return new RulesReq(
                    rules.questionCount() != null ? rules.questionCount() : questionCount,
                    rules.passScore() != null ? rules.passScore() : passScore,
                    rules.durationMin() != null ? rules.durationMin() : durationMin,
                    rules.shuffleOptions() != null ? rules.shuffleOptions() : shuffleOptions,
                    rules.maxAttempts() != null ? rules.maxAttempts() : maxAttempts,
                    rules.retryCooldownHours() != null ? rules.retryCooldownHours()
                            : retryCooldownHours,
                    rules.requireProgressPct() != null ? rules.requireProgressPct()
                            : requireProgressPct);
        }
    }

    public record ReviewReq(String status, String reason) {}

    // ================================================================ 列卷

    /**
     * 全部试卷 + 现行规则 + 题库统计 + 「现在能不能开考」。
     *
     * <p>{@code course} 用 <b>LEFT JOIN</b>：开放卷（AIGC_OPEN）的 {@code course_id} 是 NULL，
     * 写成 INNER 的话它会从这一页上<b>整张消失</b> —— 不报错、不空白，就是少一行，
     * 而那恰好是本轮唯一一张对所有注册学员开放的卷。
     */
    @GetMapping("/papers")
    Map<String, Object> papers(HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");

        var rows = jdbc.queryForList(
                "SELECT p.id, p.code, p.title, p.subtitle, p.course_id, p.access,"
              + " p.grants_certificate, p.status, p.sort_no, p.created_at, c.title AS course_title"
              + "  FROM exam_paper p LEFT JOIN course c ON c.id = p.course_id"
              + " ORDER BY p.sort_no, p.id");

        List<Long> ids = new ArrayList<>();
        for (var r : rows) ids.add(AdminQuery.id(r.get("id")));
        var banks = exams.banks(ids);
        var versions = exams.ruleVersions(ids);

        List<Map<String, Object>> out = new ArrayList<>();
        int broken = 0;
        for (var r : rows) {
            long id = AdminQuery.longOf(r.get("id"), 0);
            var bank = banks.getOrDefault(id, new AdminExamService.Bank(0, 0, 0, 0, 0));
            var rules = exams.effectiveRules(id);
            String blocker = exams.readyBlocker(rules, bank);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("paperId", id);
            m.put("code", r.get("code"));
            m.put("title", r.get("title"));
            m.put("subtitle", r.get("subtitle"));
            m.put("courseId", AdminQuery.id(r.get("course_id")));
            m.put("courseTitle", r.get("course_title"));
            m.put("access", r.get("access"));
            m.put("grantsCertificate", AdminQuery.flag(r.get("grants_certificate")));
            m.put("status", r.get("status"));
            m.put("sortNo", AdminQuery.intOf(r.get("sort_no"), 0));
            m.put("createdAt", r.get("created_at"));
            m.put("rules", rulesView(rules));
            m.put("ruleVersions", versions.getOrDefault(id, 0));
            m.put("bank", bankView(bank));
            // ★ 这四个字段就是「题不够就开不了考」在学员点下去之前的唯一信号。
            // enoughApproved / shortBy 给界面标红用，blocker 是原样能贴给运营的一句话。
            m.put("approvedNeeded", rules == null ? null : rules.questionCount());
            m.put("enoughApproved", rules != null && bank.approved() >= rules.questionCount());
            m.put("shortBy", rules == null ? null
                    : Math.max(0, rules.questionCount() - bank.approved()));
            m.put("startable", blocker == null);
            m.put("blocker", blocker);
            // status 刻意不进 blocker：blocker 是「上架之后能不能开考」，
            // 上架闸要复用它。一张 DRAFT 但一切就绪的卷 blocker 必须是 null，
            // 否则上架闸会把自己拒掉（"你还没上架所以不许上架"）。
            m.put("visibleToStudents", "ON".equals(r.get("status")));
            if (blocker != null && "ON".equals(r.get("status"))) broken++;
            out.add(m);
        }

        return AdminJson.ok("items", out, "brokenOnCount", broken,
                "message", broken == 0 ? null
                        : "有 " + broken + " 张已上架的卷现在开不了考：学员在列表里看得到它，"
                        + "点下去会被拒。逐行看 blocker");
    }

    // ================================================================ 改规则 / 上下架

    /**
     * 改这张卷的规则（<b>插 exam_config 新行</b>）、改身份字段、上下架。
     *
     * <h3>为什么不接受 access / course_id / grants_certificate</h3>
     * 这三个是<b>准入与合规</b>，不是运营参数：
     * 把一张 ENROLLED 卷改成 OPEN，等于把一门收费课的结业测评对全网注册用户开放；
     * 把 grants_certificate 从 0 改成 1，等于让考过 50 题章节卷的人换出结业证明。
     * 库层的 {@code trg_exam_paper_upd} 拦得住其中自相矛盾的组合（OPEN 且发证、
     * ENROLLED 却没有课程），但拦不住"合法但不该改"的那些。这一层干脆不给入口 ——
     * 要改就走一次带审计的数据订正，而不是后台一个下拉框。
     *
     * <h3>上架闸</h3>
     * {@code status=ON} 意味着这张卷立刻出现在学员的测评列表里。所以上架（以及在
     * 已上架状态下改规则）必须当场满足 {@link AdminExamService#readyBlocker}：
     * 否则学员看得到卡片、点下去吃一句 409，而后台这边显示"已上架"。
     * 下架永远不拦 —— 出事时要能立刻把它关掉。
     *
     * <h3>状态写入带 compare-and-set</h3>
     * 闸门读的题库数与现行规则都取自<b>事务之外</b>，两人同时操作时它们可能已经过期。
     * 所以状态那条 UPDATE 的 WHERE 里带上刚才读到的旧状态，改到 0 行就回 409 ——
     * 与 {@link #review} 那一处同一个写法。无条件 UPDATE 的后果不报错：
     * 上架方按过期的题库数过了闸、审计里留下一次并没有发生过的状态流转，
     * 而 {@code exam_paper} 除了 {@code audit_log} 没有任何历史。
     */
    @PutMapping("/papers/{id}")
    Map<String, Object> updatePaper(@PathVariable long id,
                                    @RequestBody(required = false) PaperReq r,
                                    HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        if (r == null) throw ApiException.badRequest("请求体为空");
        var paper = requirePaper(id);

        String curStatus = String.valueOf(paper.get("status"));
        String newStatus = AdminQuery.enumOf(r.status(), "status", "DRAFT", "ON", "OFF");
        String targetStatus = newStatus == null ? curStatus : newStatus;
        Long courseId = AdminQuery.id(paper.get("course_id"));

        // 身份字段：title 空串是"改成空"，那是 NOT NULL 列，当场拒而不是等 MySQL 报错
        String title = r.title() == null ? null : r.title().trim();
        if (title != null && title.isEmpty()) throw ApiException.badRequest("试卷标题不能为空");
        if (title != null && title.length() > 128)
            throw ApiException.badRequest("试卷标题不要超过 128 个字");
        String subtitle = r.subtitle() == null ? null : r.subtitle().trim();
        if (subtitle != null && subtitle.length() > 255)
            throw ApiException.badRequest("试卷副标题不要超过 255 个字");
        // 空串与 NULL 在库里是两个不同的值，但在界面上长得一模一样。归一之后再比，
        // 否则「把空副标题再清一次」会被当成一次真改动，往审计里塞一条什么也没改的记录。
        String subNorm = subtitle == null || subtitle.isEmpty() ? null : subtitle;

        var cur = exams.effectiveRules(id);
        var bank = exams.bank(id);
        RulesReq want = r.mergedRules();
        Merged merged = want == null ? null : merge(cur, want);
        boolean rulesChanged = merged != null && (cur == null || merged.differsFrom(cur));

        // ★ 只在「上架」或「已上架还改了规则」时过闸。
        // 不加这个条件的话，一张已经上架但题被驳回到不够的卷，连改个标题都会被拒 ——
        // 而那次拒绝与标题毫无关系，运营只会觉得后台坏了。
        if ("ON".equals(targetStatus) && (rulesChanged || !"ON".equals(curStatus))) {
            var wouldBe = merged == null ? cur : merged.asRules(cur, courseId);
            String blocker = exams.readyBlocker(wouldBe, bank);
            if (blocker != null)
                throw ApiException.conflict(blocker + "。"
                        + ("ON".equals(curStatus)
                           ? "这张卷现在是上架状态，改成这套规则会让它当场开不了考。"
                             + "先把题审够，或者把卷面题数改到已审核的题数以内；真要先改规则就把它下架"
                           : "上架之后学员在列表里就看得到它，点下去却会被拒。"
                             + "先把题审够，或者把卷面题数改到已审核的题数以内，再上架"));
        }

        boolean identityChanged = (title != null && !title.equals(paper.get("title")))
                || (subtitle != null && !java.util.Objects.equals(subNorm, str(paper.get("subtitle"))))
                || (r.sortNo() != null && r.sortNo() != AdminQuery.intOf(paper.get("sort_no"), 0));
        boolean statusChanged = newStatus != null && !newStatus.equals(curStatus);

        if (!identityChanged && !statusChanged && !rulesChanged)
            return AdminJson.ok("changed", false, "paperId", id, "status", curStatus,
                    "rules", rulesView(cur), "bank", bankView(bank),
                    "message", "没有任何改动");

        String reason = AdminQuery.blank(r.reason()) ? null : r.reason().trim();
        // ★ 这个局部变量不是为了好看，是为了不拆箱。
        // 写成 `merged != null ? merged.questionCount() : (cur == null ? null : cur.questionCount())`
        // 时，两支一个是 int 一个是 Integer —— 按 JLS 15.25 整个三元表达式的类型是 int，
        // 于是 null 那一支会被【自动拆箱】，一张没配过规则的卷（cur == null）改状态时
        // 当场 NPE→500，UPDATE 连同审计一起回滚。上架闸挡不住它：闸只在 targetStatus=ON
        // 时才跑，下架 / 改回草稿是直接撞进来的 —— 而那恰恰是出事时唯一的止损按钮。
        // 两支都显式包成 Integer，条件表达式才是引用类型、才允许为 null。
        Integer auditQuestionCount = merged != null ? Integer.valueOf(merged.questionCount())
                : (cur == null ? null : Integer.valueOf(cur.questionCount()));
        Long newConfigId;
        try {
            newConfigId = tx.execute(st -> {
                Long cfgId = null;
                if (identityChanged) {
                    var before = AdminJson.map("title", paper.get("title"),
                            "subtitle", str(paper.get("subtitle")),
                            "sortNo", AdminQuery.intOf(paper.get("sort_no"), 0));
                    jdbc.update("UPDATE exam_paper SET title=COALESCE(?,title),"
                              + " subtitle=IF(? IS NULL, subtitle, NULLIF(?, '')),"
                              + " sort_no=COALESCE(?,sort_no) WHERE id=?",
                            title, subtitle, subtitle, r.sortNo(), id);
                    audit.write(req, "EXAM_PAPER_EDIT", "exam_paper", id, before,
                            AdminJson.map("title", title, "subtitle", subNorm,
                                    "sortNo", r.sortNo(), "reason", reason));
                }
                if (rulesChanged) {
                    // ★ course_id 取自 exam_paper，不是抄旧 config 那一行：
                    // 两边一旦不一致（比如老行是迁移前按课程写的），抄旧行会让这张卷
                    // 的规则永远挂在错的课程上，而抽题按 paper_id 走 —— 谁也不会报错。
                    cfgId = AdminQuery.insert(jdbc,
                            "INSERT INTO exam_config (paper_id,course_id,question_count,pass_score,"
                          + "duration_min,shuffle_options,max_attempts,retry_cooldown_hours,"
                          + "require_progress_pct,effective_from) VALUES (?,?,?,?,?,?,?,?,?,NOW(3))",
                            id, courseId, merged.questionCount(), merged.passScore(),
                            merged.durationMin(), merged.shuffleOptions() ? 1 : 0,
                            merged.maxAttempts(), merged.retryCooldownHours(),
                            merged.requireProgressPct());
                    audit.write(req, "EXAM_PAPER_RULES", "exam_paper", id,
                            cur == null ? null : rulesSnapshot(cur),
                            AdminJson.map("configId", cfgId,
                                    "questionCount", merged.questionCount(),
                                    "passScore", merged.passScore(),
                                    "durationMin", merged.durationMin(),
                                    "shuffleOptions", merged.shuffleOptions(),
                                    "maxAttempts", merged.maxAttempts(),
                                    "retryCooldownHours", merged.retryCooldownHours(),
                                    "requireProgressPct", merged.requireProgressPct(),
                                    "reason", reason));
                }
                if (statusChanged) {
                    // ★ 并发裁决点，与 review() 那一处同一个写法：WHERE 带上读到的旧状态。
                    // requirePaper / effectiveRules / bank 全是在事务外读的，读与写之间
                    // 另一个人也在改这张卷时，无条件 UPDATE 有两个后果，而且都不报错：
                    // ① 上架方按【过期的】题库数过了闸，卷上架了而它已经开不了考；
                    // ② 审计的前态记的是 curStatus —— 一次并没有发生过的状态流转，
                    //    而 exam_paper 除了审计没有任何历史。
                    int n = jdbc.update("UPDATE exam_paper SET status=? WHERE id=? AND status=?",
                            newStatus, id, curStatus);
                    if (n == 0) throw ApiException.conflict(
                            "这张卷刚被另一个人改过状态（现在已经不是「" + curStatus + "」了），"
                          + "你这次没有生效。刷新看一下它现在是什么状态、题够不够，再决定");
                    audit.write(req, "EXAM_PAPER_STATUS", "exam_paper", id,
                            AdminJson.map("status", curStatus),
                            AdminJson.map("status", newStatus, "reason", reason,
                                    "approvedAtThatMoment", bank.approved(),
                                    "questionCount", auditQuestionCount));
                }
                return cfgId;
            });
        } catch (DataAccessException e) {
            // ★ catch 的必须是 DataAccessException 不是 DataIntegrityViolationException：
            // 触发器的 SIGNAL 走自定义 SQLSTATE 45000，Spring 冒上来的是
            // UncategorizedSQLException，catch 错了就是一段永远不执行的守卫。
            throw DbSignal.explain(e, "这张卷没有改动，规则历史里也没有多出一行");
        }

        // 规则回读一遍再返回：返回"我打算写什么"而不是"库里现在是什么"的话，
        // 插入被触发器改写或者被别人的新行盖过时，页面上显示的是一份并不生效的规则。
        var after = exams.effectiveRules(id);
        var bankAfter = exams.bank(id);
        String blockerAfter = exams.readyBlocker(after, bankAfter);
        return AdminJson.ok("changed", true, "paperId", id,
                "status", statusChanged ? newStatus : curStatus,
                "configId", newConfigId,
                "rules", rulesView(after), "bank", bankView(bankAfter),
                "enoughApproved", after != null && bankAfter.approved() >= after.questionCount(),
                "startable", blockerAfter == null, "blocker", blockerAfter,
                "message", changeMessage(rulesChanged, statusChanged, newStatus, newConfigId,
                        blockerAfter));
    }

    private static String changeMessage(boolean rulesChanged, boolean statusChanged,
                                        String newStatus, Long configId, String blocker) {
        StringBuilder sb = new StringBuilder();
        if (rulesChanged)
            sb.append("规则已存为新的一版（configId ").append(configId)
              .append("）。已经开考的场次仍按它们各自那一版判分，不受影响。");
        if (statusChanged)
            sb.append("ON".equals(newStatus) ? "这张卷已上架，学员现在能看到它。"
                    : "这张卷已" + ("OFF".equals(newStatus) ? "下架" : "改回草稿")
                      + "，学员的测评列表里不再出现它；已经开考没交的场次不受影响。");
        if (blocker != null) sb.append("注意：").append(blocker);
        return sb.length() == 0 ? "已保存" : sb.toString();
    }

    // ================================================================ 题目列表

    /**
     * 分页列题，<b>带答案与解析</b>（审题的人看不到答案没法审）。
     *
     * @param paperId 空 = 全部；数字 = 这张卷；{@code none} = 还没挂到任何卷上的题。
     *                最后那个入口不能省：迁移之后老题的 paper_id 全是 NULL，
     *                没有它那批题在后台<b>整片消失</b>，看起来像被迁移删掉了
     * @param status  DRAFT / PENDING / APPROVED / REJECTED
     * @param q       只搜题干。{@code %} 与 {@code _} 会被 {@link AdminQuery#like} 转义 ——
     *                不转义的话一个 {@code q=%} 就是"返回全库题目"，而它是合法值不是注入
     */
    @GetMapping("/questions")
    Map<String, Object> questions(@RequestParam(required = false) String paperId,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) String type,
                                  @RequestParam(required = false) String q,
                                  @RequestParam(required = false) Integer page,
                                  @RequestParam(required = false) Integer size,
                                  HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        int p = AdminQuery.page(page), sz = AdminQuery.size(size);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (!AdminQuery.blank(paperId)) {
            String v = paperId.trim();
            if ("none".equalsIgnoreCase(v) || "null".equalsIgnoreCase(v))
                where.append(" AND q.paper_id IS NULL");
            else {
                long pid;
                try { pid = Long.parseLong(v); }
                catch (NumberFormatException e) {
                    throw ApiException.badRequest("paperId 只接受试卷 ID 或 none（未挂卷的题）");
                }
                where.append(" AND q.paper_id=?");
                args.add(pid);
            }
        }
        String st = AdminQuery.enumOf(status, "status", "DRAFT", "PENDING", "APPROVED", "REJECTED");
        if (st != null) { where.append(" AND q.review_status=?"); args.add(st); }
        String tp = AdminQuery.enumOf(type, "type", "SINGLE", "MULTI", "JUDGE");
        if (tp != null) { where.append(" AND q.type=?"); args.add(tp); }
        if (!AdminQuery.blank(q)) { where.append(" AND q.stem LIKE ?"); args.add(AdminQuery.like(q)); }

        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM question q" + where,
                Integer.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(sz); pageArgs.add(AdminQuery.offset(p, sz));
        var rows = jdbc.queryForList(
                "SELECT q.id, q.paper_id, q.course_id, q.lesson_id, q.source_ts_sec, q.type,"
              + " q.stem, q.options, q.answer, q.explanation, q.origin, q.review_status,"
              + " q.reviewed_by, q.reviewed_at, q.created_at,"
              + " p.code AS paper_code, p.title AS paper_title, l.title AS lesson_title,"
              + " COALESCE(u.real_name, u.nickname, u.phone) AS reviewer_name"
              + "  FROM question q"
              // 三个 LEFT JOIN 都不能写 INNER：paper_id 为空（未挂卷）、lesson_id 为空
              // （没标出处）、reviewed_by 为空（还没人审过）都是这一页最该显示的几类，
              // 写 INNER 的话它们会静默消失 —— 而"还没人审过的题"恰恰是审题页存在的理由
              + "  LEFT JOIN exam_paper p ON p.id = q.paper_id"
              + "  LEFT JOIN lesson l ON l.id = q.lesson_id"
              + "  LEFT JOIN sys_user u ON u.id = q.reviewed_by"
              + where
              // 排序必须是全序。只按 review_status 排（没有 id 兜底）的话，同状态行之间
              // 的相对顺序由存储引擎决定，翻页时同一道题会重复出现、另一道从头到尾看不见
              + " ORDER BY q.id LIMIT ? OFFSET ?", pageArgs.toArray());

        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) out.add(questionView(r));
        return AdminJson.ok("total", total == null ? 0 : total, "page", p, "size", sz,
                "items", out);
    }

    // ================================================================ 审题

    /**
     * 改一道题的审核状态。只认 {@code APPROVED} / {@code REJECTED} / {@code PENDING}。
     *
     * <p><b>为什么不许改回 DRAFT</b>：DRAFT 是入库初值（AI 生成 / 批量导入落地时的状态），
     * 语义是"还没进入审核流程"。允许人工设回去，等于给审核轨迹开了一个能抹掉的口子 ——
     * 一道被驳回的题改成 DRAFT 之后，从任何一页看都和"刚导进来的新题"一模一样。
     * 要退回重改用 PENDING，那是"待审"，与"没审过"是两件事。
     *
     * <h3>★ 把题拿出 APPROVED 永远不拦，但必须当场喊出后果</h3>
     * 驳回一道错题是<b>止损动作</b>，任何时候都得让它成功 —— 拦下来的结果是错题继续在卷上。
     * 但如果这一下把已上架的卷打到题量不足，这张卷从此开不了考，而学员那边看得到卡片。
     * 所以：照改，然后在响应里把这句话原样返回，并且写进审计的 after 快照 ——
     * 事后复盘"这张卷什么时候坏的"，那条审计就是答案。
     */
    @PostMapping("/questions/{id}/review")
    Map<String, Object> review(@PathVariable long id,
                               @RequestBody(required = false) ReviewReq r,
                               HttpServletRequest req) {
        AuthInterceptor.requireRole(req, "PLATFORM_ADMIN");
        if (r == null) throw ApiException.badRequest("请求体为空");
        if (AdminQuery.blank(r.status()))
            throw ApiException.badRequest("请给出审核结果：" + String.join(" / ", REVIEW_STATES));
        String want = AdminQuery.enumOf(r.status(), "审核结果", REVIEW_STATES);

        var rows = jdbc.queryForList(
                "SELECT id, paper_id, review_status, reviewed_by, reviewed_at FROM question WHERE id=?", id);
        if (rows.isEmpty()) throw ApiException.notFound("这道题不存在");
        var qrow = rows.get(0);
        String cur = String.valueOf(qrow.get("review_status"));
        Long paperId = AdminQuery.id(qrow.get("paper_id"));

        // 双击保护：同一个结果再点一次回 200 changed:false，不回 409。
        // 后台按钮会被双击，把第二次点击变成红色错误框的话，运营的第一反应是
        //「刚才那次没生效」，于是去别的地方找补。
        if (cur.equals(want))
            return AdminJson.ok("changed", false, "questionId", id, "paperId", paperId,
                    "status", cur, "bank", paperId == null ? null : bankView(exams.bank(paperId)),
                    "message", "这道题现在就是「" + cur + "」，没有改动");

        long operator = AuthInterceptor.userId(req);
        String reason = AdminQuery.blank(r.reason()) ? null : r.reason().trim();
        try {
            tx.executeWithoutResult(st -> {
                int n = jdbc.update(
                        "UPDATE question SET review_status=?, reviewed_by=?, reviewed_at=NOW(3)"
                      + " WHERE id=? AND review_status=?", want, operator, id, cur);
                // 并发裁决点。先查后写之间别人也改了这道题时，n=0 —— 这里必须炸，
                // 不能当成功：两个人一个批准一个驳回，后写的那个静默丢失，
                // 而两边界面都显示成功。
                if (n == 0) throw ApiException.conflict(
                        "这道题刚被另一个人改过（现在已经不是「" + cur + "」了），你这次没有生效。"
                      + "刷新看一下它现在是什么状态再决定");
                audit.write(req, "EXAM_QUESTION_REVIEW", "question", id,
                        AdminJson.map("reviewStatus", cur,
                                "reviewedBy", AdminQuery.id(qrow.get("reviewed_by")),
                                "reviewedAt", qrow.get("reviewed_at")),
                        AdminJson.map("reviewStatus", want, "paperId", paperId,
                                "reviewedBy", operator, "reason", reason));
            });
        } catch (DataAccessException e) {
            throw DbSignal.explain(e, "这道题的审核状态没有改动");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("changed", true);
        body.put("questionId", id);
        body.put("paperId", paperId);
        body.put("status", want);
        body.put("reviewedBy", operator);

        if (paperId == null) {
            // 未挂卷的题永远抽不到。审了也没用这件事必须说出来，否则运营会以为
            // 自己刚刚把一道题放上了某张卷。
            body.put("bank", null);
            body.put("warning", "这道题还没有挂到任何一张试卷上（paper_id 为空），"
                    + "抽题按 paper_id 走，所以它审了也不会出现在任何卷面上");
            body.put("message", "已改为「" + want + "」");
            return body;
        }

        var bank = exams.bank(paperId);
        var rules = exams.effectiveRules(paperId);
        String blocker = exams.readyBlocker(rules, bank);
        // queryForObject 在 0 行时抛 EmptyResultDataAccessException。这道题指向的卷
        // 被并发删掉时，那个异常会把一次【已经成功落库】的审核显示成失败。
        var prows = jdbc.queryForList("SELECT status FROM exam_paper WHERE id=?", paperId);
        String paperStatus = prows.isEmpty() ? null : str(prows.get(0).get("status"));
        body.put("bank", bankView(bank));
        body.put("enoughApproved", rules != null && bank.approved() >= rules.questionCount());
        body.put("startable", blocker == null);
        // ★ 只有「已上架 + 开不了考」才是要喊出来的那一种：草稿卷题不够是正常的中间态，
        // 每次审题都弹一句警告的话，真正该看的那句会被淹掉。
        String warning = (blocker != null && "ON".equals(paperStatus))
                ? "这张卷现在是上架状态，而它已经开不了考：" + blocker
                  + "。学员在测评列表里仍看得到这张卡片，点下去会被拒" : null;
        body.put("warning", warning);
        body.put("message", "已改为「" + want + "」" + (warning == null ? "" : "。" + warning));
        return body;
    }

    // ================================================================ 校验与工具

    /** 合并后的规则。record 里全是基本类型 —— 到这一步不许再有 null。 */
    private record Merged(int questionCount, int passScore, int durationMin, boolean shuffleOptions,
                          int maxAttempts, int retryCooldownHours, int requireProgressPct) {

        boolean differsFrom(AdminExamService.Rules c) {
            return questionCount != c.questionCount() || passScore != c.passScore()
                || durationMin != c.durationMin() || shuffleOptions != c.shuffleOptions()
                || maxAttempts != c.maxAttempts() || retryCooldownHours != c.retryCooldownHours()
                || requireProgressPct != c.requireProgressPct();
        }

        /** 拿来喂 readyBlocker 的"假如存下去会是什么样"。configId 用 0 —— 它还不存在。 */
        AdminExamService.Rules asRules(AdminExamService.Rules cur, Long courseId) {
            return new AdminExamService.Rules(cur == null ? 0 : cur.configId(),
                    cur == null ? null : cur.paperId(), courseId, questionCount, passScore,
                    durationMin, shuffleOptions, maxAttempts, retryCooldownHours,
                    requireProgressPct, null);
        }
    }

    /**
     * 现行规则 + 本次请求 = 要存的那一版。缺席的字段沿用现行值。
     *
     * <p>这张卷<b>一次规则都没配过</b>时没有可沿用的东西，所以七个字段一个都不能省：
     * 悄悄用建表默认值（100 题 / 90 分 / 45 分钟）填上去的话，
     * 存下去的是一套没有人拍板过的规则，而它看起来完全正常。
     */
    private Merged merge(AdminExamService.Rules cur, RulesReq r) {
        int questionCount = pick(r.questionCount(), cur == null ? null : cur.questionCount(), "卷面题数");
        int passScore = pick(r.passScore(), cur == null ? null : cur.passScore(), "及格分");
        int durationMin = pick(r.durationMin(), cur == null ? null : cur.durationMin(), "考试时长");
        boolean shuffle;
        if (r.shuffleOptions() != null) shuffle = r.shuffleOptions();
        else if (cur != null) shuffle = cur.shuffleOptions();
        else throw missing("选项是否乱序");
        int maxAttempts = pick(r.maxAttempts(), cur == null ? null : cur.maxAttempts(), "可考次数");
        int cooldown = pick(r.retryCooldownHours(), cur == null ? null : cur.retryCooldownHours(),
                "补考冷却小时数");
        int progress = pick(r.requireProgressPct(), cur == null ? null : cur.requireProgressPct(),
                "进度门槛百分比");

        if (questionCount < 1 || questionCount > MAX_QUESTION_COUNT)
            throw ApiException.badRequest("卷面题数要在 1 到 " + MAX_QUESTION_COUNT
                    + " 之间。它是抽题的 LIMIT，填大了在库里完全合法，"
                    + "表现是这张卷永远凑不满题、永远开不了考");
        if (passScore < 0)
            throw ApiException.badRequest("及格分不能是负数");
        if (passScore > questionCount)
            throw ApiException.badRequest("及格线 " + passScore + " 分高于卷面 " + questionCount
                    + " 题：满分也不及格。所有人都会考完、判分、不过，"
                    + "而且每人烧掉一次补考次数，没有任何一处会报错");
        if (durationMin < 1 || durationMin > MAX_DURATION_MIN)
            throw ApiException.badRequest("考试时长要在 1 到 " + MAX_DURATION_MIN
                    + " 分钟之间。0 或负数会让开考时的 deadline 不晚于开始时间，"
                    + "学员点开考直接被库层拒掉");
        if (maxAttempts < 1)
            throw ApiException.badRequest("可考次数至少是 1。填 0 的话第一次开考就会被"
                    + "「已超出补考次数上限」拒掉，而这张卷看起来是正常上架的");
        if (cooldown < 0 || cooldown > MAX_COOLDOWN_HOURS)
            throw ApiException.badRequest("补考冷却要在 0 到 " + MAX_COOLDOWN_HOURS + " 小时之间");
        if (progress < 0 || progress > 100)
            throw ApiException.badRequest("进度门槛是百分比，只能在 0 到 100 之间");
        return new Merged(questionCount, passScore, durationMin, shuffle, maxAttempts,
                cooldown, progress);
    }

    private static int pick(Integer given, Integer current, String field) {
        if (given != null) return given;
        if (current != null) return current;
        throw missing(field);
    }

    private static ApiException missing(String field) {
        return ApiException.badRequest("这张卷还没有配过规则，第一次配必须把七项填全，缺的是：" + field);
    }

    private Map<String, Object> requirePaper(long id) {
        var rows = jdbc.queryForList(
                "SELECT id, code, title, subtitle, course_id, access, grants_certificate,"
              + " status, sort_no FROM exam_paper WHERE id=?", id);
        if (rows.isEmpty()) throw ApiException.notFound("这张试卷不存在");
        return rows.get(0);
    }

    // ---------------------------------------------------------------- 出网形状

    private static Map<String, Object> rulesView(AdminExamService.Rules r) {
        if (r == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configId", r.configId());
        m.put("questionCount", r.questionCount());
        m.put("passScore", r.passScore());
        // 及格线的口径是百分比（100 题 80 分 / 50 题 40 分）。让前端自己按题数除一遍
        // 就是第二套口径，改了卷面题数之后两边显示的百分比会不一样。
        m.put("passPct", r.questionCount() <= 0 ? null
                : Math.round(r.passScore() * 100.0 / r.questionCount()));
        m.put("durationMin", r.durationMin());
        m.put("shuffleOptions", r.shuffleOptions());
        m.put("maxAttempts", r.maxAttempts());
        m.put("retryCooldownHours", r.retryCooldownHours());
        m.put("requireProgressPct", r.requireProgressPct());
        m.put("effectiveFrom", r.effectiveFrom());
        return m;
    }

    private static Map<String, Object> rulesSnapshot(AdminExamService.Rules r) {
        return AdminJson.map("configId", r.configId(),
                "questionCount", r.questionCount(), "passScore", r.passScore(),
                "durationMin", r.durationMin(), "shuffleOptions", r.shuffleOptions(),
                "maxAttempts", r.maxAttempts(), "retryCooldownHours", r.retryCooldownHours(),
                "requireProgressPct", r.requireProgressPct(),
                "effectiveFrom", String.valueOf(r.effectiveFrom()));
    }

    private static Map<String, Object> bankView(AdminExamService.Bank b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("approved", b.approved());
        m.put("pending", b.pending());
        m.put("rejected", b.rejected());
        m.put("draft", b.draft());
        m.put("total", b.total());
        return m;
    }

    private Map<String, Object> questionView(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("questionId", AdminQuery.id(r.get("id")));
        m.put("paperId", AdminQuery.id(r.get("paper_id")));
        m.put("paperCode", r.get("paper_code"));
        m.put("paperTitle", r.get("paper_title"));
        m.put("courseId", AdminQuery.id(r.get("course_id")));
        // 出处（哪一讲、第几秒）：审题的人要能跳回原片核对，一道题对不对往往取决于
        // 讲师当时那句话。带不出来的话，审题只能凭题面本身猜。
        m.put("lessonId", AdminQuery.id(r.get("lesson_id")));
        m.put("lessonTitle", r.get("lesson_title"));
        m.put("sourceTsSec", r.get("source_ts_sec"));
        m.put("type", r.get("type"));
        m.put("stem", r.get("stem"));
        m.put("options", options(r.get("options")));
        // 答案与解析只在这条路上出网。前端要审题就必须看得到，见类注释。
        m.put("answer", r.get("answer"));
        m.put("explanation", r.get("explanation"));
        m.put("origin", r.get("origin"));
        m.put("reviewStatus", r.get("review_status"));
        m.put("reviewedBy", AdminQuery.id(r.get("reviewed_by")));
        m.put("reviewerName", r.get("reviewer_name"));
        m.put("reviewedAt", r.get("reviewed_at"));
        m.put("createdAt", r.get("created_at"));
        return m;
    }

    /**
     * {@code question.options} 是 JSON 列，JDBC 取回来是<b>字符串</b>。
     *
     * <p>原样透出的话前端要自己 {@code JSON.parse} 一次 —— 一层没必要的坑，
     * 而且那一层出错时错在前端、看起来像后端返回了脏数据。所以这里解开。
     *
     * <p>解不开时<b>不抛异常</b>：整页 500 会让运营连"哪道题坏了"都看不到，
     * 而这一页恰恰是用来发现坏题的。把原文放在 optionsRaw 里带出去，让那一行自己显形。
     */
    private Object options(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v);
        if (s.isBlank()) return null;
        try {
            return json.readValue(s, Object.class);
        } catch (Exception e) {
            return List.of(Map.of("optionsRaw", s,
                    "error", "这道题的选项不是合法 JSON，考卷上会显示成空选项"));
        }
    }

    private static String str(Object v) { return v == null ? null : String.valueOf(v); }
}
