package com.example.platform.payments.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:paymentsmigrations;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.default_schema=payments",
        "spring.flyway.enabled=true",
        "spring.flyway.create-schemas=true",
        "spring.flyway.schemas=payments",
        "spring.flyway.default-schema=payments"
})
class PaymentsMigrationH2Test {

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void migrationsMatchMappingsAndRejectNonPositiveAmount() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO payments.payment_transactions
                            (id, order_id, amount, currency, provider, status)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ZERO,
                        "USD", "sandbox", "PENDING"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
