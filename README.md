# Petriflow Generation

A single Spring Boot backend for testing all LLM providers and context modes to generate Petriflow applications. 

## Quick Start

### Option 1: Docker (Recommended)

```bash
# 1. Copy environment template
cp .env.example .env

# 2. Edit .env and add your API keys
# Required: Set at least one of ANTHROPIC_API_KEY, OPENAI_API_KEY, or GEMINI_API_KEY

# 3. Run with Docker Compose
docker-compose up
```

The application will be available at `http://localhost:8080`

### Option 2: Local Maven

```bash
# 1. Copy configuration template
cp src/main/resources/application.properties.example src/main/resources/application.properties

# 2. Edit application.properties and add your API keys

# 3. Run with Maven
mvn spring-boot:run
```

## Endpoints

### `POST /api/chat/stream`
SSE streaming chat. Body:
```json
{
  "messages": [
    { "role": "user", "content": "Create a simple approval workflow" }
  ]
}
```
Events:
- `chunk` → `{"text": "..."}` (streaming token)
- `done`  → `{"full": "complete response text"}` (use this to save to history)
- `error` → error message string

### `GET /api/config`
Returns the currently active configuration (provider, model, mode, topK, etc.).

### `GET /api/health`
Health check.

### `POST /api/reload`
Recomputes RAG embeddings after updating `petriflow_reference.md`.

### `GET /api/runs?limit=20`
Returns the last N run log entries as a JSON array (newest first).
Each entry contains: llm, model, contextMode, embedProvider, tokens, duration, estimated cost.

---

## Run Log (`run-log.jsonl`)

One JSON line is appended after every generation:
```json
{
  "timestamp": "2025-03-22T10:30:00Z",
  "llm": "gemini",
  "model": "gemini-2.5-flash",
  "contextMode": "rag",
  "embedProvider": "gemini",
  "promptTokens": 14200,
  "outputTokens": 3100,
  "cacheReadTokens": 0,
  "cacheWriteTokens": 0,
  "durationMs": 8420,
  "estimatedCostUsd": 0.0020,
  "truncated": false,
  "userPrompt": "Create an approval workflow for leave requests...",
  "stopReason": "STOP"
}
```

Analyze in Python:
```python
import pandas as pd
df = pd.read_json('run-log.jsonl', lines=True)
df.groupby(['llm', 'contextMode'])[['estimatedCostUsd', 'durationMs']].mean()
```

---

## RAG Cache

Cache is stored separately per embedding provider:
```
rag-cache/
  openai/
    chunks.json
    embeddings.json
  gemini/
    chunks.json
    embeddings.json
```

The first run with a new provider computes embeddings (~2 min for Gemini, ~10s for OpenAI batch).
Subsequent runs load from cache instantly.

After updating `petriflow_reference.md`:
```bash
curl -X POST http://localhost:8080/api/reload
```

---

## Windows / Java 11 TLS Fix

If you get an SSL handshake error, run with:
```bash
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dhttps.protocols=TLSv1.2,TLSv1.3 -Djdk.tls.client.protocols=TLSv1.2,TLSv1.3"
```
(This is already configured in `pom.xml`, so plain `mvn spring-boot:run` should work.)
