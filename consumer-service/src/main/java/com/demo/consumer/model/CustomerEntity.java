package com.demo.consumer.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "customers")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerEntity {

    @Id
    @Column(name = "customer_id", nullable = false, unique = true)
    private String customerId;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column
    private String email;

    private String phone;

    @Column(name = "date_of_birth")
    private String dateOfBirth;

    private String gender;

    @Column(name = "policy_number", unique = true)
    private String policyNumber;

    @Column(name = "plan_type")
    private String planType;

    @Column(name = "coverage_start_date")
    private String coverageStartDate;

    @Column(name = "coverage_end_date")
    private String coverageEndDate;

    @Column(name = "premium_amount")
    private Double premiumAmount;

    @Column(name = "medical_condition")
    private String medicalCondition;

    @Column(name = "assigned_hospital")
    private String assignedHospital;

    @Column(name = "ingested_at")
    private LocalDateTime ingestedAt;

    @PrePersist
    public void prePersist() {
        this.ingestedAt = LocalDateTime.now();
    }
}
