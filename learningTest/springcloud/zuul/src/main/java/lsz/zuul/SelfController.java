package lsz.zuul;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Author lsz
 * @create 2019/12/4 9:44
 */
@RestController
public class SelfController {

    @RequestMapping("/value")
    public String test(){
        return "zuul";
    }

}
