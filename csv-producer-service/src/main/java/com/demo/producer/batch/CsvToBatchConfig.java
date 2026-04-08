package com.demo.producer.batch;

import com.demo.producer.kafka.CustomerKafkaProducer;
import com.demo.producer.model.Customer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.*;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.mapping.BeanWrapperFieldSetMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.PlatformTransactionManager;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class CsvToBatchConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final CustomerKafkaProducer kafkaProducer;

    // ─── Reader ──────────────────────────────────────────────────────────────

    @Bean
    @StepScope
    public FlatFileItemReader<Customer> csvReader(
            @Value("#{jobParameters['filePath']}") String filePath) {

        BeanWrapperFieldSetMapper<Customer> mapper = new BeanWrapperFieldSetMapper<>();
        mapper.setTargetType(Customer.class);

        return new FlatFileItemReaderBuilder<Customer>()
                .name("customerCsvReader")
                .resource(new FileSystemResource(filePath))
                .linesToSkip(1)                        // skip header row
                .delimited()
                .names("customerId","firstName","lastName","email","phone",
                        "dateOfBirth","gender","policyNumber","planType",
                        "coverageStartDate","coverageEndDate","premiumAmount",
                        "medicalCondition","assignedHospital")
                .fieldSetMapper(mapper)
                .build();
    }

    // ─── Processor ───────────────────────────────────────────────────────────
    // Light validation / enrichment — skip nulls

    @Bean
    public ItemProcessor<Customer, Customer> customerProcessor() {
        return customer -> {
            if (customer.getCustomerId() == null || customer.getCustomerId().isBlank()) {
                log.warn("Skipping record with blank customerId");
                return null;            // returning null tells Batch to skip this item
            }
            // normalise email to lowercase
            if (customer.getEmail() != null) {
                customer.setEmail(customer.getEmail().toLowerCase());
            }
            return customer;
        };
    }

    // ─── Writer ──────────────────────────────────────────────────────────────

    @Bean
    public ItemWriter<Customer> kafkaItemWriter() {
        return chunk -> chunk.getItems().forEach(kafkaProducer::send);
    }

    // ─── Step ────────────────────────────────────────────────────────────────

    @Bean
    public Step csvToKafkaStep() {
        return new StepBuilder("csvToKafkaStep", jobRepository)
                .<Customer, Customer>chunk(50, transactionManager)
                .reader(csvReader(null))
                .processor(customerProcessor())
                .writer(kafkaItemWriter())
                .listener(new StepExecutionListener() {
                    @Override
                    public void beforeStep(StepExecution se) {
                        log.info("▶ Starting CSV → Kafka step");
                    }
                    @Override
                    public ExitStatus afterStep(StepExecution se) {
                        log.info("✔ Step complete — read={} written={} skipped={}",
                                se.getReadCount(), se.getWriteCount(), se.getSkipCount());
                        return se.getExitStatus();
                    }
                })
                .build();
    }

    // ─── Job ─────────────────────────────────────────────────────────────────

    @Bean
    public Job importCustomerJob() {
        return new JobBuilder("importCustomerJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(csvToKafkaStep())
                .build();
    }
}
