package tech.dragonai.wangke;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling} 是给 {@link tech.dragonai.wangke.agent.CommissionSettleService}
 * 开的：佣金冻结期满转可提现全靠那一轮扫描。不开的话它一次都不会跑，
 * 而表现是「代理的佣金记录都在，余额恒为 0」—— 没有任何报错。
 */
@EnableScheduling
@SpringBootApplication
public class WangkeApplication {
    public static void main(String[] args) {
        SpringApplication.run(WangkeApplication.class, args);
    }
}
