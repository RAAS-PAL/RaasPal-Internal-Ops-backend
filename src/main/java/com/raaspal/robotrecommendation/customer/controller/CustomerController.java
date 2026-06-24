package com.raaspal.robotrecommendation.customer.controller;

import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.customer.dto.CustomerRequest;
import com.raaspal.robotrecommendation.customer.dto.CustomerResponse;
import com.raaspal.robotrecommendation.customer.service.CustomerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD for customer records. Every endpoint is authenticated via
 * SecurityConfig. Customers are report recipients, not login accounts.
 */
@RestController
@RequestMapping("/api/v1/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    @GetMapping
    public ApiResponse<List<CustomerResponse>> list() {
        return ApiResponse.success(customerService.listAll());
    }

    @GetMapping("/{id}")
    public ApiResponse<CustomerResponse> getById(@PathVariable UUID id) {
        return ApiResponse.success(customerService.getById(id));
    }

    @PostMapping
    public ApiResponse<CustomerResponse> create(@Valid @RequestBody CustomerRequest request) {
        return ApiResponse.success("Customer created", customerService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<CustomerResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody CustomerRequest request) {
        return ApiResponse.success("Customer updated", customerService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        customerService.delete(id);
        return ApiResponse.success("Customer deleted");
    }
}
