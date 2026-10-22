package tech.dragonai.wangke;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 集成测试的建库夹具。三个 IT 各自抄一份 bootstrap 的话，
 * 迟早会有一份漏掉 connectionCollation 或漏掉授权，然后以一个看起来像代码问题的报错收场。
 */
public final class ItDb {

    private ItDb() {}

    /** 从 db/schema.sql + db/seed.sql 现建一个干净的库，并返回连上去的连接池。 */
    public static DataSource freshDatabase(String dbName) throws Exception {
        Path root = Paths.get("db");
        runScript("DROP DATABASE IF EXISTS `" + dbName + "`;");
        runScript(rewrite(Files.readString(root.resolve("schema.sql"), StandardCharsets.UTF_8), dbName));
        runScript(rewrite(Files.readString(root.resolve("seed.sql"), StandardCharsets.UTF_8), dbName));
        // The seed intentionally leaves the real course in DRAFT for production safety.
        // Integration tests exercise purchasing by default, so their catalog must opt in
        // to being saleable. Tests for the DRAFT guard explicitly switch it back.
        runScript("USE `" + dbName + "`; UPDATE course SET status='ON' WHERE id=1;");
        // 授权放在这里而不是靠人工执行一次：换台机器跑就会以 Access denied 收场
        runScript("GRANT ALL ON `" + dbName + "`.* TO 'wangke'@'localhost'; FLUSH PRIVILEGES;");

        HikariConfig cfg = new HikariConfig();
        // characterEncoding 要的是【Java 的字符集名】UTF-8，不是 MySQL 的 utf8mb4。
        // 写 utf8mb4 当场报错；更危险的是写成 utf8 —— 那是三字节别名，
        // 连接器不报错，只会静默吞掉 emoji 与生僻字。
        cfg.setJdbcUrl("jdbc:mysql://127.0.0.1:3306/" + dbName
                + "?useUnicode=true&characterEncoding=UTF-8&connectionCollation=utf8mb4_0900_ai_ci"
                + "&serverTimezone=Asia/Shanghai");
        cfg.setUsername("wangke");
        cfg.setPassword("wangke_dev_only");
        cfg.setMaximumPoolSize(20);
        return new HikariDataSource(cfg);
    }

    public static void close(DataSource ds) {
        if (ds instanceof HikariDataSource h) h.close();
    }

    /** 测试专用密钥。固定种子，便于失败时复现。 */
    public static byte[] testKey(int seed) {
        byte[] k = new byte[32];
        new java.security.SecureRandom(new byte[]{(byte) seed}).nextBytes(k);
        return k;
    }

    private static String rewrite(String sql, String db) {
        return sql.replace("`wangke`", "`" + db + "`").replace("USE `wangke`", "USE `" + db + "`");
    }

    public static void runScript(String sql) throws Exception {
        Process p = new ProcessBuilder("sudo", "mysql", "--default-character-set=utf8mb4")
                .redirectErrorStream(true).start();
        p.getOutputStream().write(sql.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IllegalStateException("建库失败：" + out);
    }
}
