package com.demo.consumer.model;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class CustomerDto {
    private String customerId;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private String dateOfBirth;
    private String gender;
    private String policyNumber;
    private String planType;
    private String coverageStartDate;
    private String coverageEndDate;
    private Double premiumAmount;
    private String medicalCondition;
    private String assignedHospital;
}
