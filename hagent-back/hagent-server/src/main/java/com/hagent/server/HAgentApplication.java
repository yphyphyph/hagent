package com.hagent.server;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * HAgent 后端启动类
 *
 * @author H
 */
@Slf4j
@SpringBootApplication(scanBasePackages = "com.hagent")
@EnableAsync
@EnableScheduling
@MapperScan("com.hagent.**.mapper")
public class HAgentApplication {

    /**
     * 应用启动入口
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(HAgentApplication.class, args);
    }
}