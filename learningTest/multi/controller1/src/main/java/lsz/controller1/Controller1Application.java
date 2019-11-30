package lsz.controller1;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {"lsz.component","lsz.controller1"})
public class Controller1Application {

    public static void main(String[] args) {
        SpringApplication.run(Controller1Application.class, args);
    }

}
