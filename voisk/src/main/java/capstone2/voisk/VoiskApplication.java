package capstone2.voisk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VoiskApplication {

    public static void main(String[] args) {
        SpringApplication.run(VoiskApplication.class, args);
    }
}
