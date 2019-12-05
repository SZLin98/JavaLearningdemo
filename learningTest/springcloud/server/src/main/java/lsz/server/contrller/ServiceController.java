package lsz.server.contrller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Author lsz
 * @create 2019/12/5 9:45
 */
@RestController
public class ServiceController {
    @RequestMapping("/")
    public String sayHello() {
        return "hello service";
    }

    @RequestMapping("/produce")
    public String index(@RequestParam String name){
        return "hello "+name;
    }
}
