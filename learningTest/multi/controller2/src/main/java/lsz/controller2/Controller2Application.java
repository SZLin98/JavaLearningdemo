package lsz.controller2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {"lsz.component","lsz.controller2"})

public class Controller2Application {

    public static void main(String[] args) {
        SpringApplication.run(Controller2Application.class, args);
    }

}
