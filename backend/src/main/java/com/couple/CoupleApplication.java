package com.couple;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 双人情侣互动网站后端启动类
 */
@SpringBootApplication
@MapperScan("com.couple.**.mapper")
public class CoupleApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoupleApplication.class, args);
    }
}
