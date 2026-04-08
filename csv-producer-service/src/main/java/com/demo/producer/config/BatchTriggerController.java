package com.demo.producer.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@RestController
@RequestMapping("/api/batch")
@RequiredArgsConstructor
public class BatchTriggerController {

    private final JobLauncher jobLauncher;
    private final Job importCustomerJob;

    /**
     * POST /api/batch/upload
     * Upload a CSV file → triggers Spring Batch job → events land in Kafka
     */
    @PostMapping("/upload")
    public ResponseEntity<String> upload(@RequestParam("file") MultipartFile file) {
        try {
            // Save uploaded file to temp dir
            Path tempFile = Files.createTempFile("customers_", ".csv");
            file.transferTo(tempFile.toFile());

            JobParameters params = new JobParametersBuilder()
                    .addString("filePath", tempFile.toAbsolutePath().toString())
                    .addLong("startAt", System.currentTimeMillis())
                    .toJobParameters();

            JobExecution execution = jobLauncher.run(importCustomerJob, params);
            log.info("Job started — status: {}", execution.getStatus());

            return ResponseEntity.ok("Job launched. Status: " + execution.getStatus()
                    + " | JobId: " + execution.getJobId());
        } catch (Exception e) {
            log.error("Batch job failed", e);
            return ResponseEntity.internalServerError().body("Job failed: " + e.getMessage());
        }
    }
}
