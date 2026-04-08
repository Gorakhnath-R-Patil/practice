# Healthcare Insurance Demo — Kafka + Spring Batch Microservices

## Architecture

```
CSV File
   │
   ▼
[csv-producer-service :8081]
  Spring Batch Job
    FlatFileItemReader  →  ItemProcessor  →  KafkaItemWriter
         ▲                                        │
   REST Upload                                    │
   /api/batch/upload                       Kafka Topic
                                    healthcare.customers
                                                  │
                                                  ▼
                                   [consumer-service :8082]
                                     KafkaListener (enqueue)
                                           │
                                    BlockingQueue<CustomerDto>
                                           │
                                    @Scheduled (10s)
                                     Spring Batch Job
                                   ItemReader (queue poll)
                                     → ItemProcessor
                                     → JPA ItemWriter
                                           │
                                           ▼
                                       PostgreSQL
                                    (customers table)
```

## Prerequisites

- Java 17+
- Maven 3.8+
- Docker + Docker Compose
- PostgreSQL running locally on port 5432

## 1 — Create the Postgres database

```sql
CREATE DATABASE healthcare_db;
-- default user: postgres / password: postgres
-- change in consumer-service/src/main/resources/application.yml if needed
```

## 2 — Start Kafka via Docker

```bash
docker-compose up -d
```

This starts:
- **Zookeeper** on port 2181
- **Kafka broker** on port 9092
- **Kafka UI** on http://localhost:8080 (browse topics & messages)

Wait ~15 seconds for Kafka to be fully ready.

## 3 — Build both services

```bash
mvn clean package -DskipTests
```

## 4 — Run the services (two terminals)

**Terminal 1 — Producer**
```bash
java -jar csv-producer-service/target/csv-producer-service-1.0.0.jar
```

**Terminal 2 — Consumer**
```bash
java -jar consumer-service/target/consumer-service-1.0.0.jar
```

## 5 — Upload the CSV

```bash
curl -X POST http://localhost:8081/api/batch/upload \
  -F "file=@sample-customers.csv"
```

Or use Postman → POST → form-data → key: `file`, value: select `sample-customers.csv`.

## 6 — Verify

**Kafka UI** → http://localhost:8080
→ Topic `healthcare.customers` → Messages tab → you should see JSON events

**Postgres**
```sql
SELECT * FROM customers;
```
Within ~10 seconds of the producer job finishing, the consumer batch job will drain the queue and persist all rows.

## Flow Summary

| Step | Service | Component | Action |
|------|---------|-----------|--------|
| 1 | Producer | REST Controller | Receives CSV upload, saves to temp file |
| 2 | Producer | Spring Batch Job | Reads CSV → maps to `Customer` POJO → sends to Kafka |
| 3 | Kafka | Topic | Stores `healthcare.customers` events |
| 4 | Consumer | KafkaListener | Deserializes JSON → enqueues into `BlockingQueue` |
| 5 | Consumer | @Scheduled trigger | Every 10s, launches Spring Batch job |
| 6 | Consumer | Spring Batch Job | Polls queue → maps DTO → JPA saves to Postgres |

## Kafka Topic Config

Topic `healthcare.customers` is auto-created by Kafka with default settings.
To pre-create with custom partitions (optional):
```bash
docker exec -it kafka kafka-topics \
  --create --topic healthcare.customers \
  --bootstrap-server localhost:9092 \
  --partitions 3 --replication-factor 1
```

## Stop Docker

```bash
docker-compose down
```
