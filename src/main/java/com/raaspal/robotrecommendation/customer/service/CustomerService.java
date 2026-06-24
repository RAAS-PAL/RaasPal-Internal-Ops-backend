package com.raaspal.robotrecommendation.customer.service;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.customer.dto.CustomerRequest;
import com.raaspal.robotrecommendation.customer.dto.CustomerResponse;
import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import com.raaspal.robotrecommendation.customer.repository.CustomerProfileRepository;
import com.raaspal.robotrecommendation.robotunit.repository.DeploymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD for customer records (report recipients). Customers have no login
 * account in this MVP, so these are plain records. Deleting a customer is
 * blocked while robots are still deployed to it (to protect the FK link).
 */
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerProfileRepository customerProfileRepository;
    private final DeploymentRepository deploymentRepository;

    @Transactional(readOnly = true)
    public List<CustomerResponse> listAll() {
        return customerProfileRepository.findAll().stream()
                .sorted(Comparator.comparing(CustomerProfile::getCompanyName, String.CASE_INSENSITIVE_ORDER))
                .map(c -> CustomerResponse.of(c, robotCount(c.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public CustomerResponse getById(UUID id) {
        CustomerProfile c = require(id);
        return CustomerResponse.of(c, robotCount(id));
    }

    @Transactional
    public CustomerResponse create(CustomerRequest request) {
        CustomerProfile c = customerProfileRepository.save(CustomerProfile.builder()
                .companyName(request.companyName().trim())
                .industry(request.industry())
                .contactEmail(normalizeEmail(request.contactEmail()))
                .contactPhone(request.contactPhone())
                .address(request.address())
                .notes(request.notes())
                .build());
        return CustomerResponse.of(c, 0);
    }

    @Transactional
    public CustomerResponse update(UUID id, CustomerRequest request) {
        CustomerProfile c = require(id);
        c.setCompanyName(request.companyName().trim());
        c.setIndustry(request.industry());
        c.setContactEmail(normalizeEmail(request.contactEmail()));
        c.setContactPhone(request.contactPhone());
        c.setAddress(request.address());
        c.setNotes(request.notes());
        customerProfileRepository.save(c);
        return CustomerResponse.of(c, robotCount(id));
    }

    @Transactional
    public void delete(UUID id) {
        CustomerProfile c = require(id);
        if (deploymentRepository.existsByCustomerProfileId(id)) {
            throw new BadRequestException(
                    "Cannot delete '" + c.getCompanyName() + "': it still has deployed robots. "
                            + "Remove the robot deployments first.");
        }
        customerProfileRepository.delete(c);
    }

    private CustomerProfile require(UUID id) {
        return customerProfileRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CustomerProfile", "id", id));
    }

    private long robotCount(UUID customerProfileId) {
        return deploymentRepository.countByCustomerProfileIdAndIsActiveTrue(customerProfileId);
    }

    private String normalizeEmail(String email) {
        if (email == null) return null;
        String trimmed = email.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase();
    }
}
