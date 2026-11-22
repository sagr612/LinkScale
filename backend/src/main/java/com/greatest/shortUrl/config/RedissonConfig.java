package com.greatest.shortUrl.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host}")
    private String host;

    @Value("${spring.data.redis.port}")
    private String port;

    @Value("${spring.data.redis.password}")
    private String password;

    @Value("${spring.data.redis.ssl.enabled:false}")
    private boolean sslEnabled;

    @Bean
    public RedissonClient redissonClient() {

        Config config = new Config();
        String protocol = sslEnabled ? "rediss://" : "redis://";
        var server = config.useSingleServer()
                .setAddress(
                        protocol + host + ":" + port

                );

        if (password != null && !password.isBlank()) {
            server.setPassword(password);
        }

        return Redisson.create(config);
    }
}