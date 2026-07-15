package com.cortex_clash.mind_games;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Supplies a throwaway MySQL container to the test context. {@code @ServiceConnection} wires the
 * container's JDBC url, username, and password into Spring's datasource, so Flyway runs the real
 * MySQL migrations and Hibernate validates against them — matching production instead of an
 * embedded stand-in.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    MySQLContainer<?> mysqlContainer() {
        return new MySQLContainer<>(DockerImageName.parse("mysql:8.0"));
    }
}
