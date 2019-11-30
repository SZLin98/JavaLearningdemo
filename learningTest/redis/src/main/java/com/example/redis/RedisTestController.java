package com.example.redis;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * @Author lsz
 * @create 2019/11/30 10:23
 */

@RestController
public class RedisTestController {

    @Autowired
    RedisUtil redisUtil;

    @RequestMapping("/test")
    public Object test(){
        redisUtil.addKey("name","张三",10, TimeUnit.MINUTES);
        System.out.println(redisUtil.getValue("name"));
        return redisUtil.getValue("name");
    }

}
