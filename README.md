# Healthcare Insurance Demo

A multi-service Spring Boot application that reads customer data from a CSV file, publishes records to Apache Kafka, and persists them to PostgreSQL via Spring Batch.

```
CSV File
   │
   ▼
csv-producer-service  (Spring Batch + Kafka Producer)
   │  POST /api/batch/upload
   │  Reads CSV → publishes to healthcare.customers
   ▼
Kafka (KRaft mode — no ZooKeeper)
   │  healthcare.customers
   │  healthcare.customers-retry-0/1/2   ← Kafka-level retry
   │  healthcare.customers.DLT           ← Dead Letter Topic
   ▼
consumer-service  (Kafka Consumer + Spring Batch)
   │  Buffers messages → drains every 10s via batch job
   ▼
PostgreSQL  →  customers table
```

---

## Stack

| Component | Technology |
|---|---|
| Language | Java 21 (compiled) / Java 25 JRE (runtime) |
| Framework | Spring Boot 3.2.4 |
| Messaging | Apache Kafka 7.7.1 — KRaft mode (no ZooKeeper) |
| Batch | Spring Batch 5 |
| Database | PostgreSQL 16 |
| Retry / DLQ | Spring Kafka `@RetryableTopic` (Kafka-level) |
| Observability | Spring Boot Actuator |

---

## Prerequisites

- Docker Desktop (with Compose v2)
- curl

---

## Start the full stack

```bash
# First run — builds images and starts all containers
docker compose up --build

# Subsequent runs (no code changes)
docker compose up

# Detached / background mode
docker compose up -d --build
```

> First build takes ~3–5 minutes — Maven downloads all dependencies inside the builder container.

### Stop

```bash
docker compose down
```

### Stop and wipe all data (volumes)

```bash
# Use this when switching Kafka cluster IDs or resetting Postgres data
docker compose down -v
```

---

## Services and ports

| Service | URL | Purpose |
|---|---|---|
| csv-producer-service | http://localhost:8081 | REST API — upload CSV |
| consumer-service | http://localhost:8082 | Kafka consumer + batch writer |
| Kafka UI | http://localhost:8080 | Browse topics, messages, consumer groups |
| PostgreSQL | localhost:5432 | `healthcare_db` |

---

## Test — curl commands

### 1. Health checks

```bash
# Producer
curl http://localhost:8081/actuator/health

# Consumer
curl http://localhost:8082/actuator/health
```

### 2. Upload CSV and trigger the full pipeline

```bash
curl -X POST http://localhost:8081/api/batch/upload \
  -F "file=@sample-customers.csv"
```

Expected response:
```
Job launched. Status: COMPLETED | JobId: 1
```

The consumer polls every **10 seconds** — after that window, all records appear in Postgres.

### 3. Re-upload (idempotent — duplicate customer IDs are skipped by the processor)

```bash
curl -X POST http://localhost:8081/api/batch/upload \
  -F "file=@sample-customers.csv"
```

### 4. Actuator endpoints

```bash
# Live metrics (JVM, HTTP, Kafka, batch counters)
curl http://localhost:8081/actuator/metrics
curl http://localhost:8082/actuator/metrics

# Specific metric — Kafka records sent
curl "http://localhost:8081/actuator/metrics/kafka.producer.record.send.total"

# Resolved configuration
curl http://localhost:8081/actuator/env
curl http://localhost:8082/actuator/env

# Change log level at runtime (no restart)
curl -X POST http://localhost:8082/actuator/loggers/com.demo.consumer \
  -H "Content-Type: application/json" \
  -d '{"configuredLevel":"DEBUG"}'

# App info
curl http://localhost:8081/actuator/info
curl http://localhost:8082/actuator/info
```

---

## PostgreSQL — data inspection

### Connect

```bash
docker exec -it postgres psql -U postgres -d healthcare_db
```

### List all tables

```sql
\dt
```

```
 batch_job_execution           -- Spring Batch job run history
 batch_job_execution_context
 batch_job_execution_params
 batch_job_instance
 batch_step_execution          -- read / write / skip counts per step
 batch_step_execution_context
 customers                     -- application data
```

### Customer queries

```sql
-- Row count
SELECT COUNT(*) AS total FROM customers;

-- All customers
SELECT customer_id, first_name, last_name, plan_type, premium_amount
FROM customers
ORDER BY customer_id;

-- Filter by plan type  (BASIC | STANDARD | PREMIUM)
SELECT customer_id, first_name, last_name, premium_amount, assigned_hospital
FROM customers
WHERE plan_type = 'PREMIUM'
ORDER BY premium_amount DESC;

-- Plan summary — count and average premium per plan
SELECT plan_type,
       COUNT(*)                                    AS total,
       ROUND(AVG(premium_amount)::numeric, 2)      AS avg_premium,
       MIN(premium_amount)                         AS min_premium,
       MAX(premium_amount)                         AS max_premium
FROM customers
GROUP BY plan_type
ORDER BY avg_premium DESC;

-- Customers with a medical condition
SELECT customer_id, first_name, last_name, medical_condition, assigned_hospital
FROM customers
WHERE medical_condition <> 'None'
ORDER BY medical_condition;

-- Records ingested in the last hour
SELECT customer_id, first_name, last_name, ingested_at
FROM customers
WHERE ingested_at >= NOW() - INTERVAL '1 hour'
ORDER BY ingested_at DESC;

-- Full schema
\d customers
```

### Spring Batch metadata queries

```sql
-- All job runs with status
SELECT job_instance_id, job_name, start_time, end_time, status, exit_code
FROM batch_job_execution
ORDER BY start_time DESC;

-- Step-level read / write / skip counts
SELECT step_name, start_time, status,
       read_count, write_count, skip_count, commit_count
FROM batch_step_execution
ORDER BY start_time DESC;

-- Duration of last completed job
SELECT job_name, start_time, end_time,
       EXTRACT(EPOCH FROM (end_time - start_time)) AS duration_seconds
FROM batch_job_execution
WHERE status = 'COMPLETED'
ORDER BY end_time DESC
LIMIT 1;
```

### Reset customer data (keeps Batch metadata intact)

```sql
TRUNCATE TABLE customers;
```

### Exit psql

```sql
\q
```

---

## Kafka — CLI commands

```bash
# List all topics
docker exec kafka kafka-topics --bootstrap-server localhost:29092 --list

# Describe the main topic (partitions, replication)
docker exec kafka kafka-topics --bootstrap-server localhost:29092 \
  --describe --topic healthcare.customers

# Consume from beginning — main topic
docker exec kafka kafka-console-consumer \
  --bootstrap-server localhost:29092 \
  --topic healthcare.customers \
  --from-beginning

# Inspect dead-letter messages
docker exec kafka kafka-console-consumer \
  --bootstrap-server localhost:29092 \
  --topic healthcare.customers.DLT \
  --from-beginning

# Consumer group lag (how far behind the consumer is)
docker exec kafka kafka-consumer-groups \
  --bootstrap-server localhost:29092 \
  --describe --group healthcare-consumer-group
```

Browse all topics and messages visually at **http://localhost:8080** (Kafka UI).

---

## Kafka topics

| Topic | Purpose | Retention |
|---|---|---|
| `healthcare.customers` | Main ingest topic | 24 h |
| `healthcare.customers-retry-0` | 1st retry — 2 s delay | 1 h |
| `healthcare.customers-retry-1` | 2nd retry — 4 s delay | 1 h |
| `healthcare.customers-retry-2` | 3rd retry — 8 s delay | 1 h |
| `healthcare.customers.DLT` | Dead Letter Topic | 7 days |

---

## Logs

```bash
# Tail all services
docker compose logs -f

# Single service
docker compose logs -f consumer-service
docker compose logs -f csv-producer-service
docker compose logs -f kafka
```

---

## Project structure

```
healthcare-insurance-demo/
├── docker-compose.yml
├── pom.xml                          (parent — Java 21 target, multi-module)
├── sample-customers.csv
│
├── csv-producer-service/
│   ├── Dockerfile                   (build: Java 21 JDK / run: Java 25 JRE)
│   └── src/main/
│       ├── java/com/demo/producer/
│       │   ├── batch/CsvToBatchConfig.java        (CSV reader → Kafka writer step)
│       │   ├── config/AppConfig.java
│       │   ├── config/BatchTriggerController.java  (POST /api/batch/upload)
│       │   ├── kafka/CustomerKafkaProducer.java
│       │   └── model/Customer.java
│       └── resources/application.yml
│
└── consumer-service/
    ├── Dockerfile                   (build: Java 21 JDK / run: Java 25 JRE)
    └── src/main/
        ├── java/com/demo/consumer/
        │   ├── batch/CustomerPersistBatchConfig.java  (queue → Postgres batch step)
        │   ├── config/AppConfig.java                  (@EnableScheduling, async launcher)
        │   ├── kafka/CustomerKafkaListener.java        (@RetryableTopic + @DltHandler)
        │   ├── model/CustomerDto.java
        │   ├── model/CustomerEntity.java
        │   └── repository/CustomerRepository.java
        └── resources/application.yml
```

---

## Kubernetes with Minikube

Run the full stack on a local Kubernetes cluster using Minikube.

### Prerequisites

<!-- tabs for OS -->

**Windows** (PowerShell as admin)
```powershell
winget install Kubernetes.minikube
winget install Kubernetes.kubectl

# Verify
minikube version
kubectl version --client
```

**macOS**
```bash
brew install minikube kubectl

# Verify
minikube version
kubectl version --client
```

### k8s/ folder structure

```
k8s/
├── 01-namespace.yaml      # Namespace "healthcare"
├── 02-postgres.yaml       # PVC + Deployment + Service
├── 03-kafka.yaml          # KRaft Deployment + Service
├── 04-kafka-init.yaml     # Job — creates all Kafka topics
├── 05-kafka-ui.yaml       # Deployment + NodePort Service
├── 06-producer.yaml       # Deployment + NodePort Service
└── 07-consumer.yaml       # Deployment + NodePort Service
```

---

### Step 1 — Start Minikube

```bash
# Same command on both Windows and macOS
# Start with enough memory for Kafka + Postgres + 2 Spring Boot apps
minikube start --memory=4096 --cpus=2
```

---

### Step 2 — Build images inside Minikube's Docker daemon

Minikube runs its own Docker daemon. Point your local Docker CLI at it,
then build — images land directly inside Minikube, no push to a registry needed.

**macOS / Linux**
```bash
# Point Docker CLI at Minikube's daemon (run in every new terminal session)
eval $(minikube docker-env)

# Build both app images
docker build -t csv-producer-service:latest -f csv-producer-service/Dockerfile .
docker build -t consumer-service:latest     -f consumer-service/Dockerfile .

# Confirm images are visible inside Minikube
docker images | grep -E "csv-producer|consumer-service"
```

**Windows** (PowerShell)
```powershell
# Point Docker CLI at Minikube's daemon
& minikube -p minikube docker-env --shell powershell | Invoke-Expression

# Build both app images
docker build -t csv-producer-service:latest -f csv-producer-service/Dockerfile .
docker build -t consumer-service:latest     -f consumer-service/Dockerfile .

# Confirm images are visible inside Minikube
docker images | Select-String "csv-producer|consumer-service"
```

---

### Step 3 — Deploy everything

```bash
# Apply all 7 manifests in one shot (numbered order is respected)
kubectl apply -f k8s/

# Watch pods come up (Ctrl+C to exit)
kubectl get pods -n healthcare -w
```

Expected final state:

```
NAME                                   READY   STATUS      RESTARTS
postgres-xxxx                          1/1     Running     0
kafka-xxxx                             1/1     Running     0
kafka-init-xxxx                        0/1     Completed   0
kafka-ui-xxxx                          1/1     Running     0
csv-producer-service-xxxx              1/1     Running     0
consumer-service-xxxx                  1/1     Running     0
```

---

### Step 4 — Get service URLs

```bash
# Minikube prints the URL for each NodePort service
minikube service csv-producer-service -n healthcare --url
minikube service consumer-service     -n healthcare --url
minikube service kafka-ui             -n healthcare --url
```

Or open all at once in the browser:

```bash
minikube service csv-producer-service -n healthcare
minikube service kafka-ui             -n healthcare
```

---

### Step 5 — Test with curl

Replace `<PRODUCER_URL>` with the URL from Step 4.

```bash
# Health check
curl <PRODUCER_URL>/actuator/health
curl <CONSUMER_URL>/actuator/health

# Upload CSV → triggers full pipeline
curl -X POST <PRODUCER_URL>/api/batch/upload \
  -F "file=@sample-customers.csv"
```

Wait ~10 seconds for the consumer batch job to drain, then verify in Postgres:

```bash
kubectl exec -n healthcare deployment/postgres -- \
  psql -U postgres -d healthcare_db -c "SELECT COUNT(*) FROM customers;"

kubectl exec -n healthcare deployment/postgres -- \
  psql -U postgres -d healthcare_db \
  -c "SELECT customer_id, first_name, plan_type, premium_amount FROM customers LIMIT 5;"
```

---

### Useful kubectl commands

```bash
# List everything in the namespace
kubectl get all -n healthcare

# Pod logs (live)
kubectl logs -n healthcare deployment/consumer-service -f
kubectl logs -n healthcare deployment/csv-producer-service -f
kubectl logs -n healthcare deployment/kafka -f

# Describe a pod (events, env vars, probes — useful for debugging)
kubectl describe pod -n healthcare -l app=consumer-service

# Shell into a running pod
kubectl exec -it -n healthcare deployment/postgres -- bash
kubectl exec -it -n healthcare deployment/kafka   -- bash

# Check Job completion
kubectl get jobs -n healthcare
kubectl logs   -n healthcare job/kafka-init

# List Kafka topics from inside the cluster
kubectl exec -n healthcare deployment/kafka -- \
  kafka-topics --bootstrap-server localhost:29092 --list

# Consumer group lag
kubectl exec -n healthcare deployment/kafka -- \
  kafka-consumer-groups --bootstrap-server localhost:29092 \
  --describe --group healthcare-consumer-group
```

---

### Tear down

```bash
# Delete all resources in the namespace
kubectl delete -f k8s/

# Or delete the entire namespace (removes everything inside it)
kubectl delete namespace healthcare

# Stop Minikube (keeps the cluster state)
minikube stop

# Delete the cluster entirely (fresh start next time)
minikube delete
```

---

### Troubleshooting

| Symptom | Command | Common cause |
|---|---|---|
| Pod stuck in `Pending` | `kubectl describe pod -n healthcare <name>` | Not enough memory — increase `minikube start --memory` |
| Pod in `ImagePullBackOff` | `kubectl describe pod -n healthcare <name>` | `imagePullPolicy: Never` missing or image not built inside Minikube daemon |
| App crashes on start | `kubectl logs -n healthcare deployment/<name>` | Postgres or Kafka not ready yet — pod will auto-restart |
| Job stuck | `kubectl logs -n healthcare job/kafka-init` | Kafka readiness probe not yet passed |
| `eval $(minikube docker-env)` lost (macOS/Linux) | Re-run in current terminal session | Shell env reset |
| `Invoke-Expression` error (Windows) | Run `& minikube -p minikube docker-env --shell powershell \| Invoke-Expression` in PowerShell | Must use PowerShell, not CMD |
| `minikube` not found (Windows) | Add `C:\Program Files\Kubernetes\Minikube` to PATH | winget installs but doesn't always update PATH in current session |
| `brew: command not found` (macOS) | Install Homebrew: `/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"` | Homebrew not installed |
