package com.example.nav;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@MapperScan("com.example.nav.module.**.mapper")
@ConfigurationPropertiesScan
public class NavApplication {

    public static void main(String[] args) {
        SpringApplication.run(NavApplication.class, args);
    }
}
