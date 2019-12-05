package lsz.client.inter;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * @Author lsz
 * @create 2019/12/5 9:49
 */
@FeignClient("user-service")
public interface HelloFeigen {
    @RequestMapping("/produce")
    public String HelloFeigne(@RequestParam("name") String name);
}
