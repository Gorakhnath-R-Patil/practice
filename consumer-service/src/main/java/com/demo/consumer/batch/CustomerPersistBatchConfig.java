package com.demo.consumer.batch;

import com.demo.consumer.model.CustomerDto;
import com.demo.consumer.model.CustomerEntity;
import com.demo.consumer.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.*;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class CustomerPersistBatchConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final CustomerRepository customerRepository;

    /**
     * Shared queue between the Kafka listener and the Batch ItemReader.
     * Kafka listener offers items; Batch reader polls them.
     */
    @Bean
    public BlockingQueue<CustomerDto> customerQueue() {
        return new LinkedBlockingQueue<>(5000);
    }

    // ─── Reader ──────────────────────────────────────────────────────────────
    // Polls the queue; returns null when empty → Batch step ends naturally

    @Bean
    public ItemReader<CustomerDto> queueItemReader(BlockingQueue<CustomerDto> queue) {
        return () -> queue.poll(200, TimeUnit.MILLISECONDS);
    }

    // ─── Processor ───────────────────────────────────────────────────────────

    @Bean
    public ItemProcessor<CustomerDto, CustomerEntity> dtoToEntityProcessor() {
        return dto -> {
            if (customerRepository.existsByCustomerId(dto.getCustomerId())) {
                log.warn("Duplicate customerId {} — skipping", dto.getCustomerId());
                return null;
            }
            return CustomerEntity.builder()
                    .customerId(dto.getCustomerId())
                    .firstName(dto.getFirstName())
                    .lastName(dto.getLastName())
                    .email(dto.getEmail())
                    .phone(dto.getPhone())
                    .dateOfBirth(dto.getDateOfBirth())
                    .gender(dto.getGender())
                    .policyNumber(dto.getPolicyNumber())
                    .planType(dto.getPlanType())
                    .coverageStartDate(dto.getCoverageStartDate())
                    .coverageEndDate(dto.getCoverageEndDate())
                    .premiumAmount(dto.getPremiumAmount())
                    .medicalCondition(dto.getMedicalCondition())
                    .assignedHospital(dto.getAssignedHospital())
                    .build();
        };
    }

    // ─── Writer ──────────────────────────────────────────────────────────────

    @Bean
    public ItemWriter<CustomerEntity> postgresItemWriter() {
        return chunk -> {
            customerRepository.saveAll(chunk.getItems());
            log.info("Persisted {} customers to Postgres", chunk.getItems().size());
        };
    }

    // ─── Step ────────────────────────────────────────────────────────────────

    @Bean
    public Step persistCustomerStep(BlockingQueue<CustomerDto> queue) {
        return new StepBuilder("persistCustomerStep", jobRepository)
                .<CustomerDto, CustomerEntity>chunk(50, transactionManager)
                .reader(queueItemReader(queue))
                .processor(dtoToEntityProcessor())
                .writer(postgresItemWriter())
                .build();
    }

    // ─── Job ─────────────────────────────────────────────────────────────────

    @Bean
    public Job persistCustomerJob(BlockingQueue<CustomerDto> queue) {
        return new JobBuilder("persistCustomerJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(persistCustomerStep(queue))
                .build();
    }
}
