package com.example.mybatis.service;

import com.example.mybatis.domain.Goods;
import com.example.mybatis.mapper.GoodsMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * @Author 22230
 * @create 2019/11/28 15:29
 */

@Service
public class GoodsService {

    @Autowired
    GoodsMapper goodsMapper;

    public Goods findById(Integer id){
        return goodsMapper.findById(id);
    }

    public Goods create(Goods goods){
        System.out.println(goods.toString());
        goodsMapper.create(goods);
        return goods;
    }

}
