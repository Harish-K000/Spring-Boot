package com.example.platform.inventory.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Always exercises the real Flyway scripts and Hibernate schema validation without Docker. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:inventorymigrations;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.default_schema=inventory",
        "spring.flyway.enabled=true",
        "spring.flyway.create-schemas=true",
        "spring.flyway.schemas=inventory",
        "spring.flyway.default-schema=inventory"
})
class InventoryMigrationH2Test {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migrationsMatchJpaMappingsAndEnforceStockInvariants() {
        assertInvalidStock("NEGATIVE-ON-HAND", -1, 0);
        assertInvalidStock("NEGATIVE-RESERVED", 1, -1);
        assertInvalidStock("OVER-RESERVED", 1, 2);
    }

    private void assertInvalidStock(String sku, int onHand, int reserved) {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO inventory.products
                            (id, sku, name, quantity_on_hand, reserved_quantity)
                        VALUES (?, ?, ?, ?, ?)
                        """, UUID.randomUUID(), sku, "Invalid stock", onHand, reserved))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
