package cn.ziji;

import cn.ziji.data.DataService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ZijiApplication {
    public static void main(String[] args) { SpringApplication.run(ZijiApplication.class, args); }
    @Bean
    ApplicationRunner repairMissingCosts(DataService data) {
        return args -> data.repairMissingCosts();
    }
}
