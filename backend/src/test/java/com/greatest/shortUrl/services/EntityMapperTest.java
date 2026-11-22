package com.greatest.shortUrl.services;

import com.greatest.shortUrl.entity.ShortUrl;
import com.greatest.shortUrl.model.CachedShortUrlDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

public class EntityMapperTest {

    @Test
    void toCachedShortUrlDto_anonymousUrlLeavesCreatedByIdNull() {
        ShortUrl shortUrl = new ShortUrl();
        shortUrl.setId("url-1");
        shortUrl.setShortKey("abc123");
        shortUrl.setOriginalUrl("https://example.com");
        shortUrl.setCreatedBy(null);
        CachedShortUrlDto result = new EntityMapper().toCachedShortUrlDto(shortUrl);
        assertThat(result.getCreatedById()).isNull();
    }
}
