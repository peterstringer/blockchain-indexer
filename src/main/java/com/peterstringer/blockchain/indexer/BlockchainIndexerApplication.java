package com.peterstringer.blockchain.indexer;

import com.peterstringer.blockchain.indexer.config.IndexerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(IndexerProperties.class)
public class BlockchainIndexerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BlockchainIndexerApplication.class, args);
    }
}
