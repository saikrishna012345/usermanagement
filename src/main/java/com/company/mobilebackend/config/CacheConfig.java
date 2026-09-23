package com.company.mobilebackend.config;

import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    private final CacheErrorHandler cacheErrorHandler;

    public CacheConfig(CacheErrorHandler cacheErrorHandler) {
        this.cacheErrorHandler = cacheErrorHandler;
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return cacheErrorHandler;
    }
}