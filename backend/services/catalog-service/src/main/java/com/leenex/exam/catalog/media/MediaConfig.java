package com.leenex.exam.catalog.media;

import java.nio.file.Path;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MediaProperties.class)
public class MediaConfig {

    /** Registers image plugins packaged in the application jar (the WebP reader), which ImageIO may not find alone. */
    public MediaConfig() {
        javax.imageio.ImageIO.scanForPlugins();
    }

    @Bean
    MediaStorage mediaStorage(MediaProperties properties) {
        return new FileSystemMediaStorage(Path.of(properties.dir()));
    }

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }
}
