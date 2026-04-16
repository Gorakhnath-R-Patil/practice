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
# Start with enough memory for Kafka + Postgres + 2 Spring Boot apps
minikube start --memory=4096 --cpus=2
```

---

### Step 2 — Build images inside Minikube's Docker daemon

Minikube runs its own Docker daemon. Point your local Docker CLI at it,
then build — images land directly inside Minikube, no push to a registry needed.

> **Important:** You must run the `docker-env` command in **every new terminal session**
> before building. If you skip it, the images won't be visible to Minikube.

**macOS / Linux**
```bash
# Point Docker CLI at Minikube's daemon
eval $(minikube docker-env)

# Build both app images (run from the project root)
docker build -t csv-producer-service:latest -f csv-producer-service/Dockerfile .
docker build -t consumer-service:latest     -f consumer-service/Dockerfile .

# Confirm images are visible inside Minikube
docker images | grep -E "csv-producer|consumer-service"
```

**Windows** (PowerShell)
```powershell
# Point Docker CLI at Minikube's daemon
& minikube -p minikube docker-env --shell powershell | Invoke-Expression

# Build both app images (run from the project root)
docker build -t csv-producer-service:latest -f csv-producer-service/Dockerfile .
docker build -t consumer-service:latest     -f consumer-service/Dockerfile .

# Confirm images are visible inside Minikube
docker images | Select-String "csv-producer|consumer-service"
```

> First build takes ~3–5 minutes — Maven downloads all dependencies inside the builder container.

---

### Step 3 — Deploy everything

```bash
# Apply all 7 manifests in one shot (numbered order is respected)
kubectl apply -f k8s/

# Watch pods come up — wait until all are 1/1 Running (Ctrl+C to exit)
kubectl get pods -n healthcare -w
```

Expected final state (takes ~2–3 minutes):

```
NAME                                   READY   STATUS      RESTARTS
postgres-xxxx                          1/1     Running     0
kafka-xxxx                             1/1     Running     0
kafka-init-xxxx                        0/1     Completed   0
kafka-ui-xxxx                          1/1     Running     0
csv-producer-service-xxxx              1/1     Running     0
consumer-service-xxxx                  1/1     Running     0
```

> The app pods (producer/consumer) may restart 2–3 times while waiting for Kafka and
> Postgres to be ready. This is normal — Kubernetes will keep retrying automatically.

---

### Step 4 — Get service URLs

**macOS / Linux**
```bash
minikube service csv-producer-service -n healthcare --url
minikube service consumer-service     -n healthcare --url
minikube service kafka-ui             -n healthcare --url
```

**Windows** (PowerShell)
```powershell
minikube service csv-producer-service -n healthcare --url
minikube service consumer-service     -n healthcare --url
minikube service kafka-ui             -n healthcare --url
```

Or open directly in the browser:
```bash
minikube service csv-producer-service -n healthcare
minikube service kafka-ui             -n healthcare
```

Alternatively, use `kubectl port-forward` (works on all OS without needing the Minikube IP):

```bash
# Terminal 1 — producer
kubectl port-forward -n healthcare svc/csv-producer-service 8081:8081

# Terminal 2 — consumer
kubectl port-forward -n healthcare svc/consumer-service 8082:8082

# Terminal 3 — Kafka UI
kubectl port-forward -n healthcare svc/kafka-ui 8080:8080
```

Then access at `http://localhost:8081`, `http://localhost:8082`, `http://localhost:8080`.

---

### Step 5 — Test with curl

Replace `<PRODUCER_URL>` with the URL from Step 4 (or use `http://localhost:8081` if port-forwarding).

```bash
# Health checks — both should return {"status":"UP"}
curl <PRODUCER_URL>/actuator/health
curl <CONSUMER_URL>/actuator/health

# Upload CSV → triggers the full pipeline
curl -X POST <PRODUCER_URL>/api/batch/upload \
  -F "file=@sample-customers.csv"

# Expected response:
# Job launched. Status: COMPLETED | JobId: 1
```

Wait ~10 seconds for the consumer batch job to drain, then verify data in Postgres:

```bash
# Row count — should be 39
kubectl exec -n healthcare deployment/postgres -- \
  psql -U postgres -d healthcare_db -c "SELECT COUNT(*) FROM customers;"

# Preview first 5 rows
kubectl exec -n healthcare deployment/postgres -- \
  psql -U postgres -d healthcare_db \
  -c "SELECT customer_id, first_name, plan_type, premium_amount FROM customers LIMIT 5;"
```

---

### Useful kubectl commands

```bash
# List all pods, services, deployments in the namespace
kubectl get all -n healthcare

# Live pod logs
kubectl logs -n healthcare deployment/consumer-service -f
kubectl logs -n healthcare deployment/csv-producer-service -f
kubectl logs -n healthcare deployment/kafka -f

# Describe a pod — shows events, env vars, probe status (great for debugging)
kubectl describe pod -n healthcare -l app=kafka
kubectl describe pod -n healthcare -l app=consumer-service

# Shell into a running pod
kubectl exec -it -n healthcare deployment/postgres -- bash
kubectl exec -it -n healthcare deployment/kafka   -- bash

# Check Job completion
kubectl get jobs -n healthcare
kubectl logs   -n healthcare job/kafka-init

# List Kafka topics (exec inside the Kafka pod)
kubectl exec -n healthcare deployment/kafka -- \
  kafka-topics --bootstrap-server localhost:29092 --list

# Describe the main topic (partitions, replication factor)
kubectl exec -n healthcare deployment/kafka -- \
  kafka-topics --bootstrap-server localhost:29092 \
  --describe --topic healthcare.customers

# Consumer group lag
kubectl exec -n healthcare deployment/kafka -- \
  kafka-consumer-groups --bootstrap-server localhost:29092 \
  --describe --group healthcare-consumer-group

# Restart a deployment (e.g. after a config change)
kubectl rollout restart deployment/consumer-service -n healthcare

# Check rollout status
kubectl rollout status deployment/kafka -n healthcare
```

---

### Tear down

```bash
# Delete all resources in the namespace (keeps Minikube running)
kubectl delete -f k8s/

# Or delete the entire namespace at once
kubectl delete namespace healthcare

# Stop Minikube (preserves cluster state — fast to resume)
minikube stop

# Delete the cluster entirely (clean slate for next run)
minikube delete
```

---

### Design notes — Kafka on Kubernetes

Three non-obvious things fixed in `k8s/03-kafka.yaml` that are worth understanding:

**1. `enableServiceLinks: false`**
Kubernetes auto-injects env vars like `KAFKA_PORT=tcp://10.x.x.x:29092` for every
Service in the namespace. Because our Service is named `kafka`, K8s injects `KAFKA_PORT`
into the Kafka pod. The Confluent image treats every `KAFKA_*` env var as a broker config
and crashes. Disabling service-link injection removes the collision entirely.

**2. `KAFKA_CONTROLLER_QUORUM_VOTERS: 1@localhost:9093` (not `kafka:9093`)**
Using the Service DNS name (`kafka:9093`) for the KRaft quorum creates a circular
dependency: the Service has no ready endpoints until the pod is healthy, but the pod
needs to reach the controller port to start. Since the broker and controller run in the
same pod, `localhost` always resolves correctly.

**3. `tcpSocket` readiness/liveness probes (not `kafka-topics --list`)**
The `kafka-topics` CLI bootstraps via `localhost:29092` but then follows
`KAFKA_ADVERTISED_LISTENERS` (`kafka:29092` = Service ClusterIP) for the actual request.
The Service has no endpoints until the probe passes — another circular dependency.
A raw TCP socket check just verifies the port is open and avoids the redirect entirely.

---

### Troubleshooting

| Symptom | Command | Common cause |
|---|---|---|
| Pod stuck in `Pending` | `kubectl describe pod -n healthcare <name>` | Not enough memory — increase `minikube start --memory` |
| Pod in `ImagePullBackOff` | `kubectl describe pod -n healthcare <name>` | Image not built inside Minikube daemon — re-run `docker-env` + `docker build` |
| App pods restart 2–3 times on start | `kubectl logs -n healthcare deployment/<name>` | Normal — Postgres/Kafka not ready yet, K8s retries automatically |
| `kafka-init` Job stuck | `kubectl logs -n healthcare job/kafka-init` | Kafka readiness probe not yet passed — wait a bit longer |
| `eval $(minikube docker-env)` lost | Re-run in the current terminal | Shell env is per-session — must repeat after opening a new terminal |
| `Invoke-Expression` error (Windows) | Use PowerShell, not CMD | `minikube docker-env` output only works in PowerShell |
| `minikube` not found (Windows) | Add `C:\Program Files\Kubernetes\Minikube` to PATH | `winget` installs but may not update PATH in the current session |
| `brew: command not found` (macOS) | Install Homebrew first | See https://brew.sh |
| Port-forward disconnects | Re-run the `kubectl port-forward` command | port-forward connections drop if the pod restarts |
