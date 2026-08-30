package com.example.platform.inventory.controller;

import com.example.platform.inventory.dto.CreateProductRequest;
import com.example.platform.inventory.dto.PageResponse;
import com.example.platform.inventory.dto.ProductResponse;
import com.example.platform.inventory.dto.StockChangeRequest;
import com.example.platform.inventory.dto.UpdateProductRequest;
import com.example.platform.inventory.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest req,
                                                  UriComponentsBuilder uriBuilder) {
        ProductResponse created = productService.create(req);
        URI location = uriBuilder.path("/api/v1/products/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(productService.findById(id));
    }

    @GetMapping("/by-sku/{sku}")
    public ResponseEntity<ProductResponse> findBySku(@PathVariable String sku) {
        return ResponseEntity.ok(productService.findBySku(sku));
    }

    @GetMapping
    public ResponseEntity<PageResponse<ProductResponse>> findAll(
            @RequestParam(required = false) String name,
            @PageableDefault(size = 20, sort = "sku", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(productService.findAll(name, pageable));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProductResponse> update(@PathVariable UUID id,
                                                  @Valid @RequestBody UpdateProductRequest req) {
        return ResponseEntity.ok(productService.update(id, req));
    }

    @PostMapping("/{id}/reserve")
    public ResponseEntity<ProductResponse> reserve(@PathVariable UUID id,
                                                   @Valid @RequestBody StockChangeRequest req) {
        return ResponseEntity.ok(productService.reserve(id, req.quantity()));
    }

    @PostMapping("/{id}/release")
    public ResponseEntity<ProductResponse> release(@PathVariable UUID id,
                                                   @Valid @RequestBody StockChangeRequest req) {
        return ResponseEntity.ok(productService.release(id, req.quantity()));
    }

    @PostMapping("/{id}/fulfill")
    public ResponseEntity<ProductResponse> fulfill(@PathVariable UUID id,
                                                   @Valid @RequestBody StockChangeRequest req) {
        return ResponseEntity.ok(productService.fulfill(id, req.quantity()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        productService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
