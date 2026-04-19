# Healthcare Insurance Demo

A multi-service Spring Boot application that reads customer data from a CSV file,
publishes records to Apache Kafka, and persists them to PostgreSQL via Spring Batch.

```
CSV File
   │
   ▼
csv-producer-service  (Spring Batch + Kafka Producer)
   │  POST /api/batch/upload
   │  Reads CSV row by row → publishes to healthcare.customers
   ▼
Kafka  (KRaft mode — no ZooKeeper)
   │  healthcare.customers
   │  healthcare.customers-retry-0/1/2   ← automatic Kafka-level retry
   │  healthcare.customers.DLT           ← Dead Letter Topic (poison messages)
   ▼
consumer-service  (Kafka Consumer + Spring Batch)
   │  Buffers messages → drains every 10s into Postgres via batch job
   ▼
PostgreSQL  →  customers table
```

---

## Stack

| Component | Technology |
|---|---|
| Language | Java 21 (compiled) / Java 25 JRE (runtime) |
| Framework | Spring Boot 3.2.4 |
| Messaging | Apache Kafka — KRaft mode (no ZooKeeper) |
| Batch | Spring Batch 5 |
| Database | PostgreSQL 16 |
| Retry / DLQ | Spring Kafka `@RetryableTopic` (Kafka-level, 3 retries → DLT) |
| Observability | Spring Boot Actuator (health, metrics, env, loggers) |
| Containers | Docker / Docker Compose |
| Kubernetes | Minikube (local), Helm chart for deployment |
| Kafka on K8s | Strimzi operator (KRaft mode, `KafkaNodePool`) |

---

## Spring Profiles

Each service has three environment profiles:

| Profile | When to use | Logging | Config source |
|---|---|---|---|
| `dev` | Local dev (Docker Compose, Minikube) | DEBUG | `application-dev.yml` — localhost defaults |
| `test` | CI / integration tests | DEBUG | `application-test.yml` — env vars with test DB |
| `qa` | QA / staging | INFO | `application-qa.yml` — env vars required (no defaults) |

Active profile is set via `SPRING_PROFILES_ACTIVE` environment variable.
Explicit OS env vars always override profile YAML values.

---

## Quick Start — Docker Compose

The fastest way to run the full stack locally. No Kubernetes needed.

### Prerequisites

- Docker Desktop (with Compose v2)
- curl

### Run

```bash
# First run — builds images and starts all containers (~3–5 min)
docker compose up --build

# Subsequent runs
docker compose up
```

### Test

```bash
# Health checks
curl http://localhost:8081/actuator/health
curl http://localhost:8082/actuator/health

# Upload CSV — triggers the full pipeline
curl -X POST http://localhost:8081/api/batch/upload \
  -F "file=@sample-customers.csv"
# Expected: Job launched. Status: COMPLETED | JobId: 1

# Verify rows in Postgres (~10s after upload)
docker exec postgres psql -U postgres -d healthcare_db \
  -c "SELECT COUNT(*) FROM customers;"
# Expected: 39
```

Open **http://localhost:8080** for the Kafka UI dashboard.

### Stop

```bash
docker compose down        # keeps data
docker compose down -v     # wipes all volumes and data
```

---

## Services and Ports

| Service | Docker Compose URL | Kubernetes port |
|---|---|---|
| csv-producer-service | http://localhost:8081 | NodePort 30081 |
| consumer-service | http://localhost:8082 | NodePort 30082 |
| Kafka UI | http://localhost:8080 | NodePort 30080 |
| PostgreSQL | localhost:5432 | ClusterIP (internal only) |
| Kafka | localhost:29092 | Strimzi bootstrap :9092 (internal) |

---

## Kafka Topics

| Topic | Purpose | Retention |
|---|---|---|
| `healthcare.customers` | Main ingest topic | 24 h |
| `healthcare.customers-retry-0` | 1st retry — ~2 s delay | 1 h |
| `healthcare.customers-retry-1` | 2nd retry — ~4 s delay | 1 h |
| `healthcare.customers-retry-2` | 3rd retry — ~8 s delay | 1 h |
| `healthcare.customers.DLT` | Dead Letter Topic | 7 days |

---

## Project Structure

```
healthcare-insurance-demo/
├── COMMANDS.md                      ← All commands for macOS setup and testing
├── docker-compose.yml
├── pom.xml                          (parent — multi-module, Java 21 target)
├── sample-customers.csv
│
├── csv-producer-service/
│   ├── Dockerfile
│   └── src/main/
│       ├── java/com/demo/producer/
│       │   ├── batch/CsvToBatchConfig.java        (CSV reader → Kafka writer step)
│       │   ├── config/BatchTriggerController.java  (POST /api/batch/upload)
│       │   ├── kafka/CustomerKafkaProducer.java
│       │   └── model/Customer.java
│       └── resources/
│           ├── application.yml                    (base — port, driver, batch config)
│           ├── application-dev.yml                (localhost defaults, DEBUG logging)
│           ├── application-test.yml               (env vars + test DB, DEBUG logging)
│           └── application-qa.yml                 (env vars required, INFO logging)
│
├── consumer-service/
│   ├── Dockerfile
│   └── src/main/
│       ├── java/com/demo/consumer/
│       │   ├── batch/CustomerPersistBatchConfig.java  (queue → Postgres batch step)
│       │   ├── kafka/CustomerKafkaListener.java        (@RetryableTopic + @DltHandler)
│       │   ├── model/CustomerEntity.java
│       │   └── repository/CustomerRepository.java
│       └── resources/
│           ├── application.yml                    (base — JPA, Kafka consumer config)
│           ├── application-dev.yml                (localhost, group-id: healthcare-consumer-group)
│           ├── application-test.yml               (test group-id, DEBUG logging)
│           └── application-qa.yml                 (qa group-id, INFO logging)
│
├── k8s/                             ← Raw Kubernetes manifests (for learning K8s)
│   ├── 01-namespace.yaml            (Namespace "healthcare")
│   ├── 02-postgres.yaml             (PVC + Deployment + Service)
│   ├── 03-kafka.yaml                (KRaft Deployment + Service — manual workarounds)
│   ├── 04-kafka-init.yaml           (Job — creates all 5 Kafka topics via CLI)
│   ├── 05-kafka-ui.yaml             (Deployment + NodePort Service)
│   ├── 06-producer.yaml             (Deployment + NodePort Service)
│   └── 07-consumer.yaml             (Deployment + NodePort Service)
│
└── helm/healthcare/                 ← Helm chart (recommended approach)
    ├── Chart.yaml
    ├── values.yaml                  (dev defaults)
    ├── values-test.yaml             (test overrides)
    ├── values-qa.yaml               (QA overrides — larger resources)
    └── templates/
        ├── _helpers.tpl             (shared label helper)
        ├── namespace.yaml
        ├── postgres.yaml
        ├── kafka.yaml               (Strimzi Kafka CR + KafkaNodePool)
        ├── kafka-topics.yaml        (5 × KafkaTopic CRs — replaces kafka-init Job)
        ├── kafka-ui.yaml
        ├── producer.yaml
        └── consumer.yaml
```

---

## Kubernetes with Minikube

Two deployment approaches are available.
**Helm + Strimzi is recommended** — no manual workarounds, declarative topics.

---

### Approach 1 — Raw k8s/ manifests (learning-focused)

Each file in `k8s/` is heavily commented to explain every Kubernetes concept.
Good for understanding what each resource type does before moving to Helm.

#### Prerequisites

```bash
# macOS
brew install minikube kubectl
```

#### Run

```bash
# 1. Start Minikube
minikube start --memory=4096 --cpus=2

# 2. Build images inside Minikube
eval $(minikube docker-env)
docker build -t csv-producer-service:latest -f csv-producer-service/Dockerfile .
docker build -t consumer-service:latest     -f consumer-service/Dockerfile .

# 3. Deploy all resources
kubectl apply -f k8s/

# 4. Watch pods (wait for all 1/1 Running)
kubectl get pods -n healthcare -w
```

#### Access

```bash
# Port-forward (recommended on macOS — minikube service can hang)
kubectl port-forward svc/csv-producer-service 8081:8081 -n healthcare &
kubectl port-forward svc/kafka-ui             8080:8080 -n healthcare &
```

#### Tear down

```bash
kubectl delete -f k8s/
minikube stop
```

---

### Approach 2 — Helm + Strimzi (recommended)

Uses the [Strimzi operator](https://strimzi.io) to manage Kafka on Kubernetes.
Topics are declared as `KafkaTopic` custom resources — no shell-script Job needed.

**What Strimzi gives you over the raw k8s/ approach:**

| | Raw k8s/ | Helm + Strimzi |
|---|---|---|
| Kafka resource type | `Deployment` (manual) | `StatefulSet` (operator-managed) |
| Topic creation | One-shot Job (shell script) | `KafkaTopic` CRs (declarative, reconciled) |
| KRaft workarounds | 3 manual fixes needed | Operator handles everything |
| Upgrade strategy | Manual pod restart | Operator does controlled rolling restart |
| Topic drift protection | None | Operator recreates deleted topics |

#### Prerequisites

```bash
# macOS
brew install minikube kubectl helm
```

#### One-time operator setup

```bash
# Start Minikube
minikube start --memory=4096 --cpus=2

# Install Strimzi operator
kubectl create namespace strimzi
kubectl create -f 'https://strimzi.io/install/latest?namespace=strimzi' -n strimzi
kubectl rollout status deployment/strimzi-cluster-operator -n strimzi --timeout=120s

# Configure operator to watch healthcare namespace
kubectl set env deployment/strimzi-cluster-operator \
  STRIMZI_NAMESPACE="healthcare" -n strimzi

# Create and prepare healthcare namespace for Helm
kubectl create namespace healthcare
kubectl label namespace healthcare app.kubernetes.io/managed-by=Helm
kubectl annotate namespace healthcare \
  meta.helm.sh/release-name=healthcare \
  meta.helm.sh/release-namespace=healthcare

# Grant Strimzi RBAC permissions in healthcare namespace
kubectl create rolebinding strimzi-cluster-operator \
  --clusterrole=strimzi-cluster-operator-namespaced \
  --serviceaccount=strimzi:strimzi-cluster-operator -n healthcare

kubectl create rolebinding strimzi-cluster-operator-strimzi-entity-operator \
  --clusterrole=strimzi-entity-operator \
  --serviceaccount=strimzi:strimzi-cluster-operator -n healthcare

kubectl create rolebinding strimzi-cluster-operator-strimzi-cluster-operator-watched \
  --clusterrole=strimzi-cluster-operator-watched \
  --serviceaccount=strimzi:strimzi-cluster-operator -n healthcare
```

#### Build images and deploy

```bash
# Build images inside Minikube
eval $(minikube docker-env)
docker build -t csv-producer-service:latest -f csv-producer-service/Dockerfile .
docker build -t consumer-service:latest     -f consumer-service/Dockerfile .

# Lint the chart
helm lint helm/healthcare/

# Deploy
helm install healthcare helm/healthcare/ -n healthcare

# Watch pods (the Strimzi Kafka image is ~600MB — allow 3-5 min on first run)
kubectl get pods -n healthcare -w
```

#### Verify Strimzi

```bash
# Kafka cluster — READY: True
kubectl get kafka -n healthcare

# Topics — all 5 READY: True
kubectl get kafkatopics -n healthcare

# Services Strimzi created
kubectl get svc -n healthcare | grep kafka
# healthcare-kafka-bootstrap  ClusterIP  9092  ← what Spring connects to
```

#### Test

```bash
kubectl port-forward svc/csv-producer-service 8081:8081 -n healthcare &

curl http://localhost:8081/actuator/health
curl -X POST http://localhost:8081/api/batch/upload -F "file=@sample-customers.csv"

kubectl exec -n healthcare deployment/postgres -- \
  psql -U postgres -d healthcare_db -c "SELECT COUNT(*) FROM customers;"
# Expected: 39
```

#### Tear down

```bash
helm uninstall healthcare -n healthcare
kubectl delete namespace healthcare
minikube stop
```

---

## PostgreSQL — Data Inspection

```bash
# Connect (Docker Compose)
docker exec -it postgres psql -U postgres -d healthcare_db

# Connect (Kubernetes)
kubectl exec -it -n healthcare deployment/postgres -- psql -U postgres -d healthcare_db
```

```sql
\dt                          -- list all tables
SELECT COUNT(*) FROM customers;
SELECT customer_id, first_name, last_name, plan_type, premium_amount
FROM customers LIMIT 10;

SELECT plan_type, COUNT(*), ROUND(AVG(premium_amount)::numeric, 2) AS avg_premium
FROM customers GROUP BY plan_type ORDER BY avg_premium DESC;

-- Spring Batch job history
SELECT job_name, start_time, status, exit_code
FROM batch_job_execution ORDER BY start_time DESC;

TRUNCATE TABLE customers;    -- reset data
\q                           -- exit
```

---

## Kafka — CLI Commands

```bash
# Docker Compose
docker exec kafka kafka-topics --bootstrap-server localhost:29092 --list

# Kubernetes (raw k8s/ approach)
kubectl exec -n healthcare deployment/kafka -- \
  kafka-topics --bootstrap-server localhost:29092 --list

# Kubernetes (Helm + Strimzi)
kubectl exec -n healthcare healthcare-dual-role-0 -- \
  bin/kafka-topics.sh --bootstrap-server localhost:9092 --list

# Consumer group lag
kubectl exec -n healthcare deployment/kafka -- \
  kafka-consumer-groups --bootstrap-server localhost:29092 \
  --describe --group healthcare-consumer-group
```

---

## Actuator Endpoints

```bash
# Health (DB + Kafka status)
curl http://localhost:8081/actuator/health
curl http://localhost:8082/actuator/health

# Metrics
curl http://localhost:8081/actuator/metrics
curl "http://localhost:8081/actuator/metrics/kafka.producer.record.send.total"

# Active configuration
curl http://localhost:8081/actuator/env

# Change log level at runtime
curl -X POST http://localhost:8082/actuator/loggers/com.demo.consumer \
  -H "Content-Type: application/json" \
  -d '{"configuredLevel":"DEBUG"}'
```

---

## All Commands in One Place

See [COMMANDS.md](COMMANDS.md) for the complete, step-by-step command reference
covering Docker Compose, raw Kubernetes, and Helm + Strimzi on macOS.

---

## Troubleshooting

| Symptom | Command | Fix |
|---|---|---|
| `ImagePullBackOff` | `kubectl describe pod -n healthcare <name>` | Re-run `eval $(minikube docker-env)` then rebuild |
| Pod stuck `Pending` | `kubectl describe pod -n healthcare <name>` | Not enough memory — restart Minikube with `--memory=6144` |
| App pods restart 2–3× on start | `kubectl logs -n healthcare deployment/<name>` | Normal — waiting for Postgres/Kafka. Kubernetes retries automatically |
| Strimzi operator `CrashLoopBackOff` | `kubectl logs -n strimzi deployment/strimzi-cluster-operator` | Missing RoleBinding — re-run the `kubectl create rolebinding` commands |
| `helm install` namespace error | `kubectl get namespace healthcare` | Namespace exists without Helm labels — run `kubectl label` + `kubectl annotate` commands |
| Port-forward disconnects | Re-run `kubectl port-forward` | Normal — connection drops when pod restarts |
| `minikube service` hangs on macOS | Use `kubectl port-forward` instead | Known macOS issue — port-forward always works |
| `eval $(minikube docker-env)` lost | Re-run in current terminal | Shell env is per-session — must repeat in each new terminal |
