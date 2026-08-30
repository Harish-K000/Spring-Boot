package com.example.platform.auth.repository;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** Always exercises the real Flyway scripts and Hibernate schema validation without Docker. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:authmigrations;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.default_schema=auth",
        "spring.flyway.enabled=true",
        "spring.flyway.create-schemas=true",
        "spring.flyway.schemas=auth",
        "spring.flyway.default-schema=auth"
})
class AuthMigrationH2Test {

    @Test
    void migrationsMatchJpaMappings() {
        // Context startup is the assertion: Flyway migrates first, then Hibernate validates it.
    }
}
