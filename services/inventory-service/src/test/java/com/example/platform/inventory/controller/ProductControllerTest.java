package com.example.platform.inventory.controller;

import com.example.platform.inventory.dto.CreateProductRequest;
import com.example.platform.inventory.dto.PageResponse;
import com.example.platform.inventory.dto.ProductResponse;
import com.example.platform.inventory.exception.InsufficientStockException;
import com.example.platform.inventory.exception.InvalidFulfillmentException;
import com.example.platform.inventory.exception.ProductNotFoundException;
import com.example.platform.inventory.exception.ReservedStockException;
import com.example.platform.inventory.exception.SkuAlreadyExistsException;
import com.example.platform.inventory.service.ProductService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProductController.class)
@AutoConfigureMockMvc(addFilters = false)
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    private ProductResponse sample(UUID id, int onHand, int reserved) {
        return new ProductResponse(id, "SKU-1", "Widget", onHand, reserved,
                onHand - reserved, Instant.now(), Instant.now());
    }

    @Test
    void createReturns201WithLocation() throws Exception {
        UUID id = UUID.randomUUID();
        given(productService.create(any(CreateProductRequest.class))).willReturn(sample(id, 10, 0));

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-1\",\"name\":\"Widget\",\"quantityOnHand\":10,"
                                + "\"unitPrice\":12.50,\"currency\":\"USD\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", Matchers.endsWith("/api/v1/products/" + id)))
                .andExpect(jsonPath("$.availableQuantity").value(10));
    }

    @Test
    void createWithBlankSkuAndNegativeQuantityReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"  \",\"name\":\"Widget\",\"quantityOnHand\":-1,"
                                + "\"unitPrice\":12.50,\"currency\":\"USD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'sku')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'quantityOnHand')]").exists());
    }

    @Test
    void createWithDuplicateSkuReturns409() throws Exception {
        given(productService.create(any(CreateProductRequest.class)))
                .willThrow(new SkuAlreadyExistsException("SKU-1"));

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-1\",\"name\":\"Widget\",\"quantityOnHand\":1,"
                                + "\"unitPrice\":12.50,\"currency\":\"USD\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void findByIdReturns404WhenMissing() throws Exception {
        UUID id = UUID.randomUUID();
        given(productService.findById(id)).willThrow(new ProductNotFoundException(id));

        mockMvc.perform(get("/api/v1/products/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void findBySkuReturns200() throws Exception {
        given(productService.findBySku("SKU-1")).willReturn(sample(UUID.randomUUID(), 4, 1));

        mockMvc.perform(get("/api/v1/products/by-sku/{sku}", "SKU-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("SKU-1"))
                .andExpect(jsonPath("$.availableQuantity").value(3));
    }

    @Test
    void findAllReturnsPageEnvelope() throws Exception {
        given(productService.findAll(eq("wid"), any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(sample(UUID.randomUUID(), 4, 0)), 0, 20, 1, 1, true));

        mockMvc.perform(get("/api/v1/products").param("name", "wid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void reserveReturns200WithUpdatedCounts() throws Exception {
        UUID id = UUID.randomUUID();
        given(productService.reserve(eq(id), eq(3))).willReturn(sample(id, 10, 3));

        mockMvc.perform(post("/api/v1/products/{id}/reserve", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservedQuantity").value(3))
                .andExpect(jsonPath("$.availableQuantity").value(7));
    }

    @Test
    void reserveBeyondStockReturns409() throws Exception {
        UUID id = UUID.randomUUID();
        given(productService.reserve(eq(id), anyInt()))
                .willThrow(InsufficientStockException.cannotReserve("SKU-1", 5, 2));

        mockMvc.perform(post("/api/v1/products/{id}/reserve", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Cannot reserve 5 of SKU-1: only 2 available"));
    }

    @Test
    void reserveWithZeroQuantityReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/products/{id}/reserve", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("quantity"));
    }

    @Test
    void releaseReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        given(productService.release(eq(id), eq(2))).willReturn(sample(id, 10, 0));

        mockMvc.perform(post("/api/v1/products/{id}/release", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservedQuantity").value(0));
    }

    @Test
    void fulfillReturns200WithConsumedCounts() throws Exception {
        UUID id = UUID.randomUUID();
        given(productService.fulfill(eq(id), eq(2))).willReturn(sample(id, 8, 1));

        mockMvc.perform(post("/api/v1/products/{id}/fulfill", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityOnHand").value(8))
                .andExpect(jsonPath("$.reservedQuantity").value(1))
                .andExpect(jsonPath("$.availableQuantity").value(7));
    }

    @Test
    void fulfillBeyondReservedReturns409() throws Exception {
        UUID id = UUID.randomUUID();
        given(productService.fulfill(eq(id), eq(3)))
                .willThrow(new InvalidFulfillmentException("SKU-1", 3, 1));

        mockMvc.perform(post("/api/v1/products/{id}/fulfill", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Cannot fulfill 3 of SKU-1: only 1 reserved"));
    }

    @Test
    void deleteWithReservationsReturns409() throws Exception {
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new ReservedStockException("SKU-1", 2))
                .when(productService).delete(id);

        mockMvc.perform(delete("/api/v1/products/{id}", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Cannot delete SKU-1: 2 units are reserved"));
    }

    @Test
    void deleteReturns204() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/products/{id}", id))
                .andExpect(status().isNoContent());

        verify(productService).delete(id);
    }
}
