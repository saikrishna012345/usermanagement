package com.blackroth.training.orderservice.config;

import com.blackroth.training.orderservice.client.ProductFeignErrorDecoder;
import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeignConfig {
    @Bean
    public ErrorDecoder productErrorDecoder() {
        return new ProductFeignErrorDecoder();
    }
}
