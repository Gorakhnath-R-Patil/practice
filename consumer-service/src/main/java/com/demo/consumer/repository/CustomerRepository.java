package com.demo.consumer.repository;

import com.demo.consumer.model.CustomerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CustomerRepository extends JpaRepository<CustomerEntity, String> {
    boolean existsByCustomerId(String customerId);
}
