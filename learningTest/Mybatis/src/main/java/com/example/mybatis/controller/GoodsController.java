package com.example.mybatis.controller;

import com.example.mybatis.domain.Goods;
import com.example.mybatis.service.GoodsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Author 22230
 * @create 2019/11/28 15:26
 */

@RestController
@RequestMapping("/")
public class GoodsController {

    @Autowired
    private GoodsService goodsService;

    @RequestMapping("test/{id}")
    public Goods testFunction(@PathVariable(name = "id") Integer id){
        return goodsService.findById(id);
    }

    @RequestMapping("test")
    public Goods createGoods(){
        Goods goods=new Goods(1,"first");
        return goodsService.create(goods);
    }

}
