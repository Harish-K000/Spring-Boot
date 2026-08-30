package com.example.platform.inventory.service;

import com.example.platform.inventory.dto.CreateProductRequest;
import com.example.platform.inventory.dto.ProductResponse;
import com.example.platform.inventory.dto.UpdateProductRequest;
import com.example.platform.inventory.exception.InsufficientStockException;
import com.example.platform.inventory.exception.InvalidReleaseException;
import com.example.platform.inventory.exception.ProductNotFoundException;
import com.example.platform.inventory.exception.SkuAlreadyExistsException;
import com.example.platform.inventory.mapper.ProductMapper;
import com.example.platform.inventory.model.Product;
import com.example.platform.inventory.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock private ProductRepository productRepository;

    private ProductService productService;

    @BeforeEach
    void setUp() {
        productService = new ProductService(productRepository, new ProductMapper());
    }

    private Product product(int onHand, int reserved) {
        Product p = new Product();
        p.setSku("SKU-1");
        p.setName("Widget");
        p.setQuantityOnHand(onHand);
        p.setReservedQuantity(reserved);
        return p;
    }

    @Test
    void createRejectsDuplicateSku() {
        given(productRepository.existsBySku("SKU-1")).willReturn(true);

        assertThatThrownBy(() -> productService.create(new CreateProductRequest("SKU-1", "Widget", 5)))
                .isInstanceOf(SkuAlreadyExistsException.class);

        verify(productRepository, never()).save(any());
    }

    @Test
    void createStartsWithNoReservations() {
        given(productRepository.existsBySku("SKU-1")).willReturn(false);
        given(productRepository.saveAndFlush(any(Product.class))).willAnswer(inv -> inv.getArgument(0));

        ProductResponse response = productService.create(new CreateProductRequest("SKU-1", "Widget", 7));

        assertThat(response.quantityOnHand()).isEqualTo(7);
        assertThat(response.reservedQuantity()).isZero();
        assertThat(response.availableQuantity()).isEqualTo(7);
    }

    @Test
    void createNormalizesSkuAndName() {
        given(productRepository.existsBySku("SKU-1")).willReturn(false);
        given(productRepository.saveAndFlush(any(Product.class))).willAnswer(inv -> inv.getArgument(0));

        ProductResponse response = productService.create(
                new CreateProductRequest(" sku-1 ", " Widget ", 7));

        assertThat(response.sku()).isEqualTo("SKU-1");
        assertThat(response.name()).isEqualTo("Widget");
    }

    @Test
    void createTranslatesUniqueConstraintRaceToConflict() {
        given(productRepository.existsBySku("SKU-1")).willReturn(false);
        given(productRepository.saveAndFlush(any(Product.class)))
                .willThrow(new DataIntegrityViolationException("unique sku"));

        assertThatThrownBy(() -> productService.create(
                new CreateProductRequest("sku-1", "Widget", 7)))
                .isInstanceOf(SkuAlreadyExistsException.class)
                .hasMessageContaining("SKU-1");
    }

    @Test
    void reserveReducesAvailableWithoutTouchingOnHand() {
        UUID id = UUID.randomUUID();
        given(productRepository.findByIdForUpdate(id)).willReturn(Optional.of(product(10, 2)));
        given(productRepository.save(any(Product.class))).willAnswer(inv -> inv.getArgument(0));

        ProductResponse response = productService.reserve(id, 3);

        assertThat(response.quantityOnHand()).isEqualTo(10);
        assertThat(response.reservedQuantity()).isEqualTo(5);
        assertThat(response.availableQuantity()).isEqualTo(5);
    }

    @Test
    void reserveRejectsMoreThanAvailable() {
        UUID id = UUID.randomUUID();
        // 10 on hand, 8 reserved -> only 2 available.
        given(productRepository.findByIdForUpdate(id)).willReturn(Optional.of(product(10, 8)));

        assertThatThrownBy(() -> productService.reserve(id, 3))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("only 2 available");

        verify(productRepository, never()).save(any());
    }

    @Test
    void reserveExactlyAvailableIsAllowed() {
        UUID id = UUID.randomUUID();
        given(productRepository.findByIdForUpdate(id)).willReturn(Optional.of(product(10, 8)));
        given(productRepository.save(any(Product.class))).willAnswer(inv -> inv.getArgument(0));

        assertThat(productService.reserve(id, 2).availableQuantity()).isZero();
    }

    @Test
    void releaseReturnsStockToAvailablePool() {
        UUID id = UUID.randomUUID();
        given(productRepository.findByIdForUpdate(id)).willReturn(Optional.of(product(10, 4)));
        given(productRepository.save(any(Product.class))).willAnswer(inv -> inv.getArgument(0));

        ProductResponse response = productService.release(id, 3);

        assertThat(response.reservedQuantity()).isEqualTo(1);
        assertThat(response.availableQuantity()).isEqualTo(9);
    }

    @Test
    void releaseRejectsMoreThanReserved() {
        UUID id = UUID.randomUUID();
        given(productRepository.findByIdForUpdate(id)).willReturn(Optional.of(product(10, 1)));

        assertThatThrownBy(() -> productService.release(id, 2))
                .isInstanceOf(InvalidReleaseException.class);
    }

    @Test
    void updateCannotDropStockBelowReserved() {
        UUID id = UUID.randomUUID();
        given(productRepository.findByIdForUpdate(id)).willReturn(Optional.of(product(10, 6)));

        assertThatThrownBy(() -> productService.update(id, new UpdateProductRequest("Widget", 4)))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("already reserved");
    }

    @Test
    void updateAllowsStockEqualToReserved() {
        UUID id = UUID.randomUUID();
        given(productRepository.findByIdForUpdate(id)).willReturn(Optional.of(product(10, 6)));
        given(productRepository.save(any(Product.class))).willAnswer(inv -> inv.getArgument(0));

        ProductResponse response = productService.update(id, new UpdateProductRequest("Renamed", 6));

        assertThat(response.name()).isEqualTo("Renamed");
        assertThat(response.availableQuantity()).isZero();
    }

    @Test
    void fulfillConsumesPhysicalAndReservedStock() {
        UUID id = UUID.randomUUID();
        given(productRepository.findByIdForUpdate(id)).willReturn(Optional.of(product(10, 4)));
        given(productRepository.save(any(Product.class))).willAnswer(inv -> inv.getArgument(0));

        ProductResponse response = productService.fulfill(id, 3);

        assertThat(response.quantityOnHand()).isEqualTo(7);
        assertThat(response.reservedQuantity()).isEqualTo(1);
        assertThat(response.availableQuantity()).isEqualTo(6);
    }

    @Test
    void fulfillRejectsMoreThanReserved() {
        UUID id = UUID.randomUUID();
        given(productRepository.findByIdForUpdate(id)).willReturn(Optional.of(product(10, 2)));

        assertThatThrownBy(() -> productService.fulfill(id, 3))
                .isInstanceOf(com.example.platform.inventory.exception.InvalidFulfillmentException.class)
                .hasMessageContaining("only 2 reserved");

        verify(productRepository, never()).save(any());
    }

    @Test
    void stockOperationsRejectNonPositiveQuantityBeforeLoadingProduct() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> productService.reserve(id, 0))
                .isInstanceOf(com.example.platform.inventory.exception.InvalidStockQuantityException.class);
        assertThatThrownBy(() -> productService.release(id, -1))
                .isInstanceOf(com.example.platform.inventory.exception.InvalidStockQuantityException.class);
        assertThatThrownBy(() -> productService.fulfill(id, 0))
                .isInstanceOf(com.example.platform.inventory.exception.InvalidStockQuantityException.class);

        verifyNoInteractions(productRepository);
    }

    @Test
    void deleteRejectsProductWithReservations() {
        UUID id = UUID.randomUUID();
        given(productRepository.findByIdForUpdate(id)).willReturn(Optional.of(product(10, 2)));

        assertThatThrownBy(() -> productService.delete(id))
                .isInstanceOf(com.example.platform.inventory.exception.ReservedStockException.class)
                .hasMessageContaining("2 units are reserved");

        verify(productRepository, never()).delete(any());
    }

    @Test
    void deleteUsesLockedProductWhenNoReservationsExist() {
        UUID id = UUID.randomUUID();
        Product product = product(10, 0);
        given(productRepository.findByIdForUpdate(id)).willReturn(Optional.of(product));

        productService.delete(id);

        verify(productRepository).delete(product);
    }

    @Test
    void reserveOnMissingProductThrows() {
        UUID id = UUID.randomUUID();
        given(productRepository.findByIdForUpdate(id)).willReturn(Optional.empty());

        assertThatThrownBy(() -> productService.reserve(id, 1))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void findBySkuThrowsWhenMissing() {
        given(productRepository.findBySku("NOPE")).willReturn(Optional.empty());

        assertThatThrownBy(() -> productService.findBySku("NOPE"))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessageContaining("sku NOPE");
    }
}
