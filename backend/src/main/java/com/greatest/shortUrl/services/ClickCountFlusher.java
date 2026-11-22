package com.greatest.shortUrl.services;

import com.greatest.shortUrl.repository.ShortUrlRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@Component
@RequiredArgsConstructor
@Slf4j
public class ClickCountFlusher {
    private final RedissonClient redissonClient;
    private final ShortUrlRepo shortUrlRepo;

    @Scheduled(fixedRate = 30_000)
    @Transactional
    public void flush() {
        Iterable<String> keys = redissonClient.getKeys().getKeysByPattern("clicks:*");

        for (String key : keys) {
            RAtomicLong counter = redissonClient.getAtomicLong(key);
            long count = counter.getAndSet(0);
            if (count > 0) {
                String urlId = key.substring("clicks:".length());
                try {
                    shortUrlRepo.incrementClickCountBy(urlId, count);
                    counter.expire(Duration.ofHours(24));
                    log.debug("Flushed {} clicks for URL {}", count, urlId);
                } catch (Exception e) {
                    counter.addAndGet(count);
                    log.error("Failed to flush {} clicks for URL {}. Restored count back to Redis.", count, urlId, e);
                }
            }
        }
    }
}
