package com.demo.consumer.kafka;

import com.demo.consumer.model.CustomerDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.retry.annotation.Backoff;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

// ─── Kafka Consumer Factory ───────────────────────────────────────────────────

@Configuration
class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // Must be false so Spring Kafka controls offset commits during retry routing
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(3);
        return factory;
    }
}

// ─── Kafka Listener ───────────────────────────────────────────────────────────

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerKafkaListener {

    private final BlockingQueue<CustomerDto> customerQueue;
    private final ObjectMapper objectMapper;
    private final JobLauncher jobLauncher;
    private final Job persistCustomerJob;

    private final AtomicBoolean jobRunning = new AtomicBoolean(false);

    /**
     * Kafka-level retry + DLT:
     *   Attempt 1 → healthcare.customers (original)
     *   Attempt 2 → healthcare.customers-retry-0  (2 s delay)
     *   Attempt 3 → healthcare.customers-retry-1  (4 s delay)
     *   Attempt 4 → healthcare.customers-retry-2  (8 s delay)
     *   All retries exhausted → healthcare.customers.DLT
     *
     * Topics are pre-created by the kafka-init container in docker-compose.
     * Spring also auto-creates them if missing (autoCreateTopics = "true").
     *
     * JsonProcessingException is non-retryable — bad JSON goes straight to DLT.
     */
    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 2000, multiplier = 2.0, maxDelay = 16000),
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            dltTopicSuffix = ".DLT",
            autoCreateTopics = "true",
            exclude = {JsonProcessingException.class}
    )
    @KafkaListener(topics = "${kafka.topic.customers}", groupId = "${spring.kafka.consumer.group-id}")
    public void onMessage(@Payload String payload,
                          @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                          @Header(KafkaHeaders.OFFSET) long offset) throws JsonProcessingException {

        CustomerDto dto = objectMapper.readValue(payload, CustomerDto.class);

        if (!customerQueue.offer(dto)) {
            // Queue is full — let @RetryableTopic retry after backoff delay
            log.warn("Queue full, will retry customer {} (partition={} offset={})",
                    dto.getCustomerId(), partition, offset);
            throw new IllegalStateException(
                    "Customer queue is full — cannot enqueue " + dto.getCustomerId());
        }

        log.debug("Enqueued customer {} from partition={} offset={}",
                dto.getCustomerId(), partition, offset);
    }

    /**
     * Dead Letter Topic handler — invoked after all retries are exhausted.
     * Log the poisoned message; can be extended to persist it to a dead_letter table.
     */
    @DltHandler
    public void handleDlt(@Payload String payload,
                          @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                          @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                          @Header(KafkaHeaders.OFFSET) long offset) {
        log.error("[DLT] Unprocessable message — retries exhausted." +
                        " Topic: {}  Partition: {}  Offset: {}",
                topic, partition, offset);
        log.error("[DLT] Payload: {}", payload);
        // TODO: persist to dead_letter_events table or fire an alert
    }

    /**
     * Every 10 seconds: if there are items in the queue and no job is running,
     * launch a batch job to drain the queue into Postgres.
     */
    @Scheduled(fixedDelay = 10_000)
    public void drainQueueWithBatch() {
        if (customerQueue.isEmpty() || jobRunning.get()) return;

        jobRunning.set(true);
        try {
            log.info("Queue has {} items — launching batch job", customerQueue.size());
            JobParameters params = new JobParametersBuilder()
                    .addLong("runAt", System.currentTimeMillis())
                    .toJobParameters();
            JobExecution execution = jobLauncher.run(persistCustomerJob, params);
            log.info("Batch job finished — status: {} | written: {}",
                    execution.getStatus(),
                    execution.getStepExecutions().stream()
                            .mapToLong(StepExecution::getWriteCount).sum());
        } catch (Exception e) {
            log.error("Batch job error", e);
        } finally {
            jobRunning.set(false);
        }
    }
}
