# Predictive Dynamic Load Balancer — Starter

Starter scaffold for the final-year project. Java/Spring Boot is the main application; Python/FastAPI is a separate prediction service.

## Architecture in this starter
- `control-service`: Spring Boot REST API and Java-to-Python integration.
- `ml-service`: FastAPI service with a clearly labelled baseline predictor. It is not yet the trained ML model.
- The initial server list is simulated. Gateway routing, persistent metrics, model training and load tests are later milestones.

## Prerequisites
- JDK 21
- Maven 3.9+
- Python 3.11+ (Python 3.13 should work with compatible packages)

## Run Python ML service
From the repository root:

```powershell
cd ml-service
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8001
```

API docs: http://127.0.0.1:8001/docs

## Run Spring Boot control service
In a second terminal, from the repository root:

```powershell
cd control-service
mvn spring-boot:run
```

Spring Boot API: http://127.0.0.1:8080

## Test endpoints
- `GET http://127.0.0.1:8080/api/servers`
- `GET http://127.0.0.1:8080/api/ml-health`
- `POST http://127.0.0.1:8080/api/predictions`

Example PowerShell request:

```powershell
$body = @{
  serverId = 'server-a'
  cpuPercent = 61
  memoryPercent = 48
  requestsPerSecond = 35
  recentCpuPercent = @(45, 49, 54, 58, 61)
} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/predictions -ContentType 'application/json' -Body $body
```

## Important
The Python predictor in this scaffold extrapolates a short CPU trend as a temporary baseline. It is not the final ML model. We will replace it with a trained model using a chronological train/test split and properly constructed future-load targets. Do not present the baseline as machine-learning results.
