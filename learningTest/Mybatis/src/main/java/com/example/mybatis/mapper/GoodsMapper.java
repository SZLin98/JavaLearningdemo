package com.example.mybatis.mapper;

import com.example.mybatis.domain.Goods;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.web.bind.annotation.Mapping;

/**
 * @Author 22230
 * @create 2019/11/28 15:02
 */

@Mapper
public interface GoodsMapper {

    Goods findById(Integer id);
    int create(Goods goods);

}
