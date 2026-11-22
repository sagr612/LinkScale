package com.greatest.shortUrl.services;

import com.greatest.shortUrl.repository.ShortUrlRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShortUrlCleanupService {
    private final ShortUrlRepo shortUrlRepo;
    private final CachedShortUrlService cachedShortUrlService;

//    @Scheduled(cron = "0 0 2 * * ?")
////    @Scheduled(fixedRate = 5000)
//    @Transactional
//    public void deleteExpiredUrls(){
//        int deleted = shortUrlRepo.deleteExpired(Instant.now());
//        log.info("Deleted {} expired URLs", deleted);

    /// /        shortUrlRepo.deleteByExpiresAtBefore(Instant.now());
//    }
    @Scheduled(cron = "0 */5 * * * *")
//    @Scheduled(fixedRate = 5000)

    @Transactional
    public void expireUrls() {
        Instant now = Instant.now();
        List<String> shortUrlstoExpire = shortUrlRepo.findShortKeysToExpire(now);

        shortUrlstoExpire.forEach(shortUrl -> {
            cachedShortUrlService.evict(shortUrl);
        });
        int updated = shortUrlRepo.markExpiredUrls(Instant.now());

        log.info("Expired {} URLs and evicted {} cache entries", updated, shortUrlstoExpire.size());
    }


    @Scheduled(cron = "0 0 2 * * *")
//    @Scheduled(fixedRate = 5000)

    @Transactional
    public void cleanupOldExpiredUrls() {

        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);

        int deleted = shortUrlRepo.deleteExpiredUrlsOlderThan(cutoff);

        log.info("Deleted {} expired URLs", deleted);
    }
}
