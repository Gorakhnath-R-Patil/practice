package com.demo.producer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Customer {
    private String customerId;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private String dateOfBirth;
    private String gender;
    private String policyNumber;
    private String planType;         // BASIC / STANDARD / PREMIUM
    private String coverageStartDate;
    private String coverageEndDate;
    private Double premiumAmount;
    private String medicalCondition;
    private String assignedHospital;
}
