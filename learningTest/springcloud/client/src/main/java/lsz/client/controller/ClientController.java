package lsz.client.controller;

import lsz.client.inter.HelloFeigen;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Author lsz
 * @create 2019/12/5 9:52
 */
@RestController
public class ClientController {

    @Autowired
    HelloFeigen helloFeigen;

    @RequestMapping("/hello")
    public String hello(){
        return "hello client";
    }

    @RequestMapping("/feigen/{name}")
    public String consumer(@PathVariable("name") String name){
        return helloFeigen.HelloFeigne(name);
    }

}
