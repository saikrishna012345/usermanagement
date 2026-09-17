package com.company.mobilebackend.service;

import com.company.mobilebackend.repository.RefreshTokenRepository;
import com.company.mobilebackend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ScheduledJobsService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobsService.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final CacheManager cacheManager;

    public ScheduledJobsService(RefreshTokenRepository refreshTokenRepository,
                                UserRepository userRepository,
                                CacheManager cacheManager) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.cacheManager = cacheManager;
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void cleanupExpiredRefreshTokens() {
        log.info("Running scheduled job: cleanup expired refresh tokens");
        List<com.company.mobilebackend.model.RefreshToken> allTokens = refreshTokenRepository.findAll();
        long expiredCount = allTokens.stream()
                .filter(t -> t.getExpiryDate().isBefore(LocalDateTime.now()))
                .count();
        List<com.company.mobilebackend.model.RefreshToken> expired = allTokens.stream()
                .filter(t -> t.getExpiryDate().isBefore(LocalDateTime.now()))
                .toList();
        refreshTokenRepository.deleteAll(expired);
        log.info("Expired refresh token cleanup complete - removed {} tokens", expiredCount);
    }

    @Scheduled(cron = "0 0 23 * * *")
    public void generateDailyOrderSummary() {
        log.info("Running scheduled job: daily order summary");
        log.info("Daily order summary job completed");
    }

    @Scheduled(cron = "0 0 4 * * MON")
    public void detectInactiveUsers() {
        log.info("Running scheduled job: inactive user detection");
        LocalDateTime cutoff = LocalDateTime.now().minusDays(90);
        long inactiveCount = userRepository.findAll().stream()
                .filter(u -> u.getUpdatedAt() != null && u.getUpdatedAt().isBefore(cutoff))
                .count();
        log.info("Inactive user detection complete - {} users inactive for 90+ days", inactiveCount);
    }

    @Scheduled(fixedRate = 1800000)
    public void cacheMaintenance() {
        log.info("Running scheduled job: cache maintenance check");
        cacheManager.getCacheNames().forEach(name ->
                log.info("Cache present: {}", name));
    }
}