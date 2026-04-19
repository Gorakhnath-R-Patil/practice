# Commands Reference — Healthcare Insurance Demo

This file covers every command you need to run this project on **macOS**,
from a brand-new machine all the way through end-to-end testing.

Three ways to run the stack are documented:

| Approach | Best for | Jump to |
|---|---|---|
| **Docker Compose** | Fast local development, no Kubernetes needed | [Section A](#a-docker-compose-local-development) |
| **Raw Kubernetes (k8s/)** | Learning Kubernetes concepts step by step | [Section B](#b-raw-kubernetes-minikube) |
| **Helm + Strimzi** | Production-like setup, declarative everything | [Section C](#c-helm--strimzi-recommended) |

---

## Prerequisites — Install Required Software

Run these once on a new Mac. Skip any tool you already have.

```bash
# 1. Homebrew — macOS package manager (if not already installed)
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

# Verify Homebrew
brew --version
```

```bash
# 2. Git — version control
brew install git

# Verify
git --version

# Configure your identity (required for git commit)
git config --global user.name  "Your Name"
git config --global user.email "you@example.com"
```

```bash
# 3. Java 21 — required to build the Maven project
#    (The Dockerfile uses Java 21 for compilation, so the host only needs it
#     if you want to run mvn commands locally outside Docker)
brew install --cask temurin@21

# Verify
java -version
# Expected: openjdk version "21.x.x"
```

```bash
# 4. Docker Desktop — container runtime (used by both Docker Compose and Minikube)
brew install --cask docker

# Start Docker Desktop from Applications, then verify:
docker --version
docker compose version
```

```bash
# 5. Minikube — local single-node Kubernetes cluster
brew install minikube

# Verify
minikube version
```

```bash
# 6. kubectl — Kubernetes CLI (control the cluster)
brew install kubectl

# Verify
kubectl version --client
```

```bash
# 7. Helm — Kubernetes package manager (needed for Section C only)
brew install helm

# Verify
helm version
```

```bash
# 8. curl — HTTP client for testing APIs (usually pre-installed on macOS)
curl --version
```

---

## Get the Code

```bash
# Clone the repository
git clone https://github.com/Gorakhnath-R-Patil/practice.git
cd practice

# Confirm project structure
ls -la
# You should see: csv-producer-service/  consumer-service/  k8s/  helm/  docker-compose.yml
```

---

## A. Docker Compose — Local Development

The simplest way to run everything. No Kubernetes required.
Kafka, Postgres, and both Spring Boot services start as Docker containers.

### Start

```bash
# First run — builds Docker images from source and starts all containers
# (takes 3-5 min on first run — Maven downloads dependencies inside the build)
docker compose up --build

# Background mode (detached) — runs silently in the background
docker compose up -d --build

# Subsequent runs — skip --build if you haven't changed any Java code
docker compose up
```

### Stop

```bash
# Stop containers (keeps data in volumes)
docker compose down

# Stop AND delete all data (Postgres tables, Kafka topics)
# Use this to start completely fresh
docker compose down -v
```

### Check status

```bash
# See running containers
docker compose ps

# Follow logs for all services
docker compose logs -f

# Follow logs for a specific service
docker compose logs -f consumer-service
docker compose logs -f csv-producer-service
docker compose logs -f kafka
```

### Test — Docker Compose

```bash
# 1. Health checks — both should return {"status":"UP"}
curl http://localhost:8081/actuator/health
curl http://localhost:8082/actuator/health

# 2. Upload the sample CSV — triggers the full pipeline
curl -X POST http://localhost:8081/api/batch/upload \
  -F "file=@sample-customers.csv"
# Expected response: Job launched. Status: COMPLETED | JobId: 1

# 3. Wait ~10 seconds for the consumer batch job, then check Postgres
docker exec -it postgres psql -U postgres -d healthcare_db \
  -c "SELECT COUNT(*) FROM customers;"
# Expected: 39

# 4. Open Kafka UI in your browser
open http://localhost:8080
```

### Kafka CLI — Docker Compose

```bash
# List all topics
docker exec kafka kafka-topics --bootstrap-server localhost:29092 --list

# Describe the main topic
docker exec kafka kafka-topics --bootstrap-server localhost:29092 \
  --describe --topic healthcare.customers

# Read messages from the beginning (Ctrl+C to stop)
docker exec -it kafka kafka-console-consumer \
  --bootstrap-server localhost:29092 \
  --topic healthcare.customers \
  --from-beginning

# Check consumer group lag
docker exec kafka kafka-consumer-groups \
  --bootstrap-server localhost:29092 \
  --describe --group healthcare-consumer-group

# Inspect DLT (Dead Letter Topic) messages
docker exec -it kafka kafka-console-consumer \
  --bootstrap-server localhost:29092 \
  --topic healthcare.customers.DLT \
  --from-beginning
```

### PostgreSQL — Docker Compose

```bash
# Open an interactive psql session
docker exec -it postgres psql -U postgres -d healthcare_db

# Then inside psql:
\dt                                                      -- list tables
SELECT COUNT(*) FROM customers;                          -- row count
SELECT * FROM customers LIMIT 5;                         -- preview rows
SELECT plan_type, COUNT(*) FROM customers GROUP BY plan_type;  -- summary
TRUNCATE TABLE customers;                                -- reset data
\q                                                       -- exit
```

---

## B. Raw Kubernetes — Minikube

Runs the exact same stack on a local Kubernetes cluster.
Use this to learn Kubernetes concepts step by step (each k8s/ file is heavily commented).

### Step 1 — Start Minikube

```bash
# Start a local K8s cluster with enough resources for this stack
minikube start --memory=4096 --cpus=2

# Verify the cluster is running
minikube status
kubectl cluster-info
```

### Step 2 — Build Docker images inside Minikube

```bash
# Point your Docker CLI at Minikube's internal Docker daemon.
# Images built after this command are visible to Minikube — no registry push needed.
# IMPORTANT: you must run this in EVERY new terminal session before building.
eval $(minikube docker-env)

# Confirm you're talking to Minikube's Docker (not your local Docker Desktop)
docker info | grep "Name"
# Should show "minikube" as the node name

# Build both application images from the project root
docker build -t csv-producer-service:latest -f csv-producer-service/Dockerfile .
docker build -t consumer-service:latest     -f consumer-service/Dockerfile .

# Verify images are visible
docker images | grep -E "csv-producer-service|consumer-service"
```

### Step 3 — Deploy all resources

```bash
# Apply all 7 YAML manifests in numeric order
# (kubectl apply respects the numbered prefix 01- → 07-)
kubectl apply -f k8s/

# Watch pods come up — wait until all show 1/1 Running
# Ctrl+C to stop watching once everything is green
kubectl get pods -n healthcare -w
```

Expected final state (takes 2-3 minutes):

```
NAME                                   READY   STATUS      RESTARTS
postgres-xxxx                          1/1     Running     0
kafka-xxxx                             1/1     Running     0
kafka-init-xxxx                        0/1     Completed   0
kafka-ui-xxxx                          1/1     Running     0
csv-producer-service-xxxx              1/1     Running     0
consumer-service-xxxx                  1/1     Running     0
```

> The app pods (producer/consumer) will restart 2-3 times while Postgres and Kafka
> start up. This is normal — Kubernetes retries automatically.

### Step 4 — Access services

```bash
# Option A: minikube service command (opens browser or prints URL)
minikube service csv-producer-service -n healthcare --url
minikube service kafka-ui             -n healthcare --url
minikube service consumer-service     -n healthcare --url

# Option B: port-forward (works even if minikube service command hangs)
# Run each in a SEPARATE terminal tab
kubectl port-forward svc/csv-producer-service 8081:8081 -n healthcare
kubectl port-forward svc/kafka-ui             8080:8080 -n healthcare
kubectl port-forward svc/consumer-service     8082:8082 -n healthcare
# Then use: http://localhost:8081  http://localhost:8080  http://localhost:8082
```

### Step 5 — Test (raw k8s)

```bash
# Health checks
curl http://localhost:8081/actuator/health
curl http://localhost:8082/actuator/health

# Upload CSV
curl -X POST http://localhost:8081/api/batch/upload \
  -F "file=@sample-customers.csv"
# Expected: Job launched. Status: COMPLETED | JobId: 1

# Verify data in Postgres (wait ~10s for consumer batch job)
kubectl exec -n healthcare deployment/postgres -- \
  psql -U postgres -d healthcare_db -c "SELECT COUNT(*) FROM customers;"
# Expected: 39

# Preview rows
kubectl exec -n healthcare deployment/postgres -- \
  psql -U postgres -d healthcare_db \
  -c "SELECT customer_id, first_name, plan_type, premium_amount FROM customers LIMIT 5;"
```

### Useful kubectl commands

```bash
# Show everything in the namespace
kubectl get all -n healthcare

# Live pod logs
kubectl logs -n healthcare deployment/consumer-service -f
kubectl logs -n healthcare deployment/csv-producer-service -f
kubectl logs -n healthcare deployment/kafka -f

# Describe a pod — shows events, image, env vars, probe status (great for debugging)
kubectl describe pod -n healthcare -l app=kafka
kubectl describe pod -n healthcare -l app=consumer-service

# Check if the kafka-init Job completed
kubectl get jobs -n healthcare
kubectl logs -n healthcare job/kafka-init

# List Kafka topics from inside the Kafka pod
kubectl exec -n healthcare deployment/kafka -- \
  kafka-topics --bootstrap-server localhost:29092 --list

# Describe the main topic
kubectl exec -n healthcare deployment/kafka -- \
  kafka-topics --bootstrap-server localhost:29092 \
  --describe --topic healthcare.customers

# Consumer group lag
kubectl exec -n healthcare deployment/kafka -- \
  kafka-consumer-groups --bootstrap-server localhost:29092 \
  --describe --group healthcare-consumer-group

# Shell into a running pod (for manual debugging)
kubectl exec -it -n healthcare deployment/postgres -- bash
kubectl exec -it -n healthcare deployment/consumer-service -- sh

# Restart a deployment (e.g., after rebuilding the Docker image)
kubectl rollout restart deployment/csv-producer-service -n healthcare
kubectl rollout restart deployment/consumer-service     -n healthcare

# Check rollout status
kubectl rollout status deployment/consumer-service -n healthcare
```

### Tear down (raw k8s)

```bash
# Delete all resources created from k8s/ manifests
kubectl delete -f k8s/

# Or delete the entire namespace at once (faster)
kubectl delete namespace healthcare

# Stop Minikube (saves state — fast to resume later)
minikube stop

# Delete the Minikube cluster entirely (clean slate)
minikube delete
```

---

## C. Helm + Strimzi — Recommended

This is the production-like setup. Kafka is managed by the **Strimzi operator**
(a Kubernetes controller that handles Kafka lifecycle, topics, and upgrades).
Helm packages the entire app as a single deployable chart with environment-specific values.

### Step 1 — Start Minikube

```bash
minikube start --memory=4096 --cpus=2

# Verify
minikube status
```

### Step 2 — Install the Strimzi operator (one-time setup)

The Strimzi operator is installed once into its own namespace.
It watches the healthcare namespace and manages Kafka for you.

```bash
# Create the namespace for the Strimzi operator itself
kubectl create namespace strimzi

# Download and apply the Strimzi install bundle (operator + all CRDs)
# This creates: Deployment, ClusterRoles, ClusterRoleBindings, and 10+ CRDs
kubectl create -f 'https://strimzi.io/install/latest?namespace=strimzi' -n strimzi

# Wait for the operator pod to be ready (takes ~30-60s)
kubectl rollout status deployment/strimzi-cluster-operator -n strimzi --timeout=120s
```

```bash
# Tell the operator to watch the healthcare namespace
kubectl set env deployment/strimzi-cluster-operator \
  STRIMZI_NAMESPACE="healthcare" \
  -n strimzi

# Wait for the operator to restart with the new config
kubectl rollout status deployment/strimzi-cluster-operator -n strimzi --timeout=60s
```

```bash
# Create the healthcare namespace (Helm will manage it)
kubectl create namespace healthcare

# Label it so Helm can adopt it
kubectl label namespace healthcare app.kubernetes.io/managed-by=Helm
kubectl annotate namespace healthcare \
  meta.helm.sh/release-name=healthcare \
  meta.helm.sh/release-namespace=healthcare

# Grant the Strimzi operator RBAC permissions inside the healthcare namespace
# (three RoleBindings needed — one per Strimzi ClusterRole)
kubectl create rolebinding strimzi-cluster-operator \
  --clusterrole=strimzi-cluster-operator-namespaced \
  --serviceaccount=strimzi:strimzi-cluster-operator \
  -n healthcare

kubectl create rolebinding strimzi-cluster-operator-strimzi-entity-operator \
  --clusterrole=strimzi-entity-operator \
  --serviceaccount=strimzi:strimzi-cluster-operator \
  -n healthcare

kubectl create rolebinding strimzi-cluster-operator-strimzi-cluster-operator-watched \
  --clusterrole=strimzi-cluster-operator-watched \
  --serviceaccount=strimzi:strimzi-cluster-operator \
  -n healthcare
```

### Step 3 — Build Docker images inside Minikube

```bash
# Point Docker CLI at Minikube's Docker daemon
eval $(minikube docker-env)

# Build both application images from the project root
docker build -t csv-producer-service:latest -f csv-producer-service/Dockerfile .
docker build -t consumer-service:latest     -f consumer-service/Dockerfile .

# Verify
docker images | grep -E "csv-producer-service|consumer-service"
```

### Step 4 — Deploy with Helm

```bash
# Validate the chart (catches YAML errors before deploying)
helm lint helm/healthcare/

# Dry-run — see everything that would be created without actually creating it
helm template healthcare helm/healthcare/ | grep "^kind:"

# Deploy the chart (dev environment, uses values.yaml defaults)
helm install healthcare helm/healthcare/ -n healthcare

# Watch pods come up — Strimzi will pull its Kafka image (~600MB on first run)
# Wait until all pods show 1/1 Running
kubectl get pods -n healthcare -w
```

Expected final state:

```
NAME                                          READY   STATUS    RESTARTS
healthcare-dual-role-0                        1/1     Running   0         ← Kafka broker (Strimzi)
healthcare-entity-operator-xxxx               1/1     Running   0         ← Topic reconciler
postgres-xxxx                                 1/1     Running   0
kafka-ui-xxxx                                 1/1     Running   0
csv-producer-service-xxxx                     1/1     Running   0
consumer-service-xxxx                         1/1     Running   0
```

### Step 5 — Verify Strimzi resources

```bash
# Check the Kafka cluster status — should show READY: True
kubectl get kafka -n healthcare

# Check KafkaNodePool — shows node roles and IDs
kubectl get kafkanodepool -n healthcare

# Check all 5 topics — should all show READY: True
kubectl get kafkatopics -n healthcare

# Verify Strimzi created the bootstrap Service
kubectl get svc -n healthcare | grep kafka
# Should show: healthcare-kafka-bootstrap  ClusterIP  9092
```

### Step 6 — Access services (Helm)

```bash
# Port-forward to access services on localhost
kubectl port-forward svc/csv-producer-service 8081:8081 -n healthcare &
kubectl port-forward svc/kafka-ui             8080:8080 -n healthcare &
kubectl port-forward svc/consumer-service     8082:8082 -n healthcare &

# OR use minikube service (opens browser directly)
minikube service csv-producer-service -n healthcare
minikube service kafka-ui             -n healthcare
```

### Step 7 — Test (Helm)

```bash
# Health checks
curl http://localhost:8081/actuator/health
curl http://localhost:8082/actuator/health

# Upload CSV — triggers the full Kafka → Postgres pipeline
curl -X POST http://localhost:8081/api/batch/upload \
  -F "file=@sample-customers.csv"
# Expected: Job launched. Status: COMPLETED | JobId: 1

# Wait ~10 seconds, then verify Postgres row count
kubectl exec -n healthcare deployment/postgres -- \
  psql -U postgres -d healthcare_db -c "SELECT COUNT(*) FROM customers;"
# Expected: 39

# Check consumer logs to confirm batch write
kubectl logs -n healthcare deployment/consumer-service --tail=20 | \
  grep -i "persisted\|customers"
# Expected: Persisted 39 customers to Postgres
```

### Helm upgrade (after code or config changes)

```bash
# After changing values.yaml or any template:
helm upgrade healthcare helm/healthcare/ -n healthcare

# Rebuild Docker image first if Java code changed, then upgrade:
eval $(minikube docker-env)
docker build -t csv-producer-service:latest -f csv-producer-service/Dockerfile .
kubectl rollout restart deployment/csv-producer-service -n healthcare
```

### Deploy to different environments

```bash
# Test environment (separate namespace, separate Postgres DB, different ports)
kubectl create namespace healthcare-test
# ... repeat Strimzi RBAC setup for healthcare-test namespace ...
helm install healthcare helm/healthcare/ \
  -f helm/healthcare/values-test.yaml \
  -n healthcare-test --create-namespace

# QA environment
helm install healthcare helm/healthcare/ \
  -f helm/healthcare/values-qa.yaml \
  -n healthcare-qa --create-namespace \
  --set postgres.password=$POSTGRES_PASSWORD
```

### Helm teardown

```bash
# Uninstall the Helm release (removes all K8s resources the chart created)
helm uninstall healthcare -n healthcare

# Delete namespace (also removes PVCs and any leftover resources)
kubectl delete namespace healthcare

# Stop or delete Minikube
minikube stop     # pause — fast to resume
minikube delete   # full wipe — next start is a fresh cluster
```

---

## Troubleshooting

### Pod stuck in ImagePullBackOff

```bash
# Check what went wrong
kubectl describe pod -n healthcare <pod-name>
# Look for: "Failed to pull image" in Events section

# FIX: You forgot eval $(minikube docker-env) before docker build
eval $(minikube docker-env)
docker build -t csv-producer-service:latest -f csv-producer-service/Dockerfile .
kubectl rollout restart deployment/csv-producer-service -n healthcare
```

### Pod stuck in Pending

```bash
kubectl describe pod -n healthcare <pod-name>
# Look for: "Insufficient memory" in Events

# FIX: Restart Minikube with more memory
minikube stop
minikube start --memory=6144 --cpus=2
```

### Strimzi operator CrashLoopBackOff

```bash
kubectl logs deployment/strimzi-cluster-operator -n strimzi --tail=20
# Look for: "forbidden" or "cannot list resource"

# FIX: Missing RoleBinding — re-run the rolebinding commands from Step 2
```

### App pod keeps restarting (but eventually works)

This is normal. The app pods start before Kafka and Postgres are ready.
Kubernetes retries automatically — just wait 2-3 minutes.

```bash
# Watch the restart count decrease
kubectl get pods -n healthcare -w
```

### Port-forward disconnects

```bash
# Port-forward terminates if the pod restarts. Just re-run the command.
kubectl port-forward svc/csv-producer-service 8081:8081 -n healthcare
```

### minikube service command hangs on macOS

```bash
# Use port-forward instead — it works on all platforms
kubectl port-forward svc/csv-producer-service 8081:8081 -n healthcare
```

### `eval $(minikube docker-env)` must be re-run

The minikube docker-env changes are only active in the current terminal session.
Opening a new terminal tab or window means you start fresh — re-run it before any docker build.

```bash
# Add this to your ~/.zshrc to make it permanent (optional):
# eval $(minikube docker-env) 2>/dev/null || true
```

---

## Actuator Endpoints Reference

These work for both Docker Compose (localhost) and Kubernetes (port-forward to localhost).

```bash
# Overall health — {"status":"UP"} when all components are connected
curl http://localhost:8081/actuator/health    # producer
curl http://localhost:8082/actuator/health    # consumer

# Detailed health (shows DB status, Kafka status, disk space)
curl http://localhost:8082/actuator/health | python3 -m json.tool

# All available metrics
curl http://localhost:8081/actuator/metrics

# Kafka producer metrics
curl "http://localhost:8081/actuator/metrics/kafka.producer.record.send.total"

# Resolved configuration (see which env vars are active)
curl http://localhost:8081/actuator/env
curl http://localhost:8082/actuator/env

# Change log level at runtime — no restart needed
curl -X POST http://localhost:8082/actuator/loggers/com.demo.consumer \
  -H "Content-Type: application/json" \
  -d '{"configuredLevel":"DEBUG"}'

# Reset log level back to INFO
curl -X POST http://localhost:8082/actuator/loggers/com.demo.consumer \
  -H "Content-Type: application/json" \
  -d '{"configuredLevel":"INFO"}'
```

---

## PostgreSQL Queries Reference

```bash
# Connect (Docker Compose)
docker exec -it postgres psql -U postgres -d healthcare_db

# Connect (Kubernetes — any approach)
kubectl exec -it -n healthcare deployment/postgres -- psql -U postgres -d healthcare_db
```

```sql
-- Row count
SELECT COUNT(*) AS total FROM customers;

-- All customers
SELECT customer_id, first_name, last_name, plan_type, premium_amount
FROM customers ORDER BY customer_id;

-- Filter by plan type (BASIC | STANDARD | PREMIUM)
SELECT customer_id, first_name, last_name, premium_amount, assigned_hospital
FROM customers WHERE plan_type = 'PREMIUM' ORDER BY premium_amount DESC;

-- Plan summary
SELECT plan_type,
       COUNT(*)                               AS total,
       ROUND(AVG(premium_amount)::numeric, 2) AS avg_premium
FROM customers GROUP BY plan_type ORDER BY avg_premium DESC;

-- Spring Batch job history
SELECT job_instance_id, job_name, start_time, status, exit_code
FROM batch_job_execution ORDER BY start_time DESC;

-- Step-level read/write counts
SELECT step_name, read_count, write_count, skip_count, status
FROM batch_step_execution ORDER BY start_time DESC;

-- Reset customer data (keeps Batch metadata)
TRUNCATE TABLE customers;

-- Exit
\q
```
