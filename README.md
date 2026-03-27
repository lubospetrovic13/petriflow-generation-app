# Petriflow Generation

> **Can LLMs reliably generate valid PetriFlow process applications from natural language?**  
> This repository is my ongoing attempt to find out.

---

## What's in here

The core of this project is **two instructional Markdown documents** built specifically to teach large language models how to generate [PetriFlow](https://petriflow.com) XML:

| File | Purpose |
|------|---------|
| `petriflow_guide.md` | Full schema walkthrough, patterns, Groovy action examples, process templates (~250KB) |
| `petriflow_reference.md` | Critical rules, common error patterns, generation workflow, quick reference snippets |

Both files are in `src/main/resources/guides/`.

**You can use these guides without the app** — paste them into any LLM chat and ask it to generate a PetriFlow process. That's the point.

The repository also includes a **Spring Boot backend** for systematic experimentation: compare providers, context strategies, token costs, and output quality across runs.

---

## What is PetriFlow?

[PetriFlow](https://petriflow.com) is an open-source XML + Groovy language developed by [Netgrif](https://netgrif.com) for building enterprise process applications. A single `.xml` file defines:

- **Process flow** — Petri net topology with places, transitions, and arcs
- **Data model** — typed fields with validation
- **UI** — forms for logged-in and anonymous users
- **Role-based access control**
- **Automation logic** — Groovy actions triggered on task/case lifecycle events
- **Notifications and integrations**

The language is precise and the runtime is strict — which makes it a meaningful challenge for LLMs.

---

## Quick Start

### Option 1: Docker (Recommended)

```bash
# 1. Copy environment template
cp .env.example .env

# 2. Add your API keys (at least one of the three)
# ANTHROPIC_API_KEY, OPENAI_API_KEY, or GEMINI_API_KEY

# 3. Start
docker-compose up
```

Open `http://localhost:8080`

### Option 2: Local Maven

```bash
cp src/main/resources/application.properties.example src/main/resources/application.properties
# Edit application.properties and add your API keys
mvn spring-boot:run
```

---

## Supported providers and models

| Provider | Default model | Alternatives |
|----------|--------------|-------------|
| **Claude** (Anthropic) | `claude-sonnet-4-5` | `claude-opus-4-5`, `claude-haiku-4-5` |
| **OpenAI** | `gpt-4o` | `gpt-4o-mini`, `gpt-4-turbo` |
| **Gemini** (Google) | `gemini-2.5-flash` | `gemini-2.0-flash`, `gemini-1.5-pro` |

Configure in `application.properties` (or `.env` for Docker).

---

## Context modes

| Mode | How it works | Best for |
|------|-------------|---------|
| `full` | Entire guide injected into system prompt. Claude uses prompt caching to reduce cost on repeated runs. | Highest accuracy, complex processes |
| `rag` | Top-K most relevant chunks retrieved per query using embeddings. | Lower cost, faster iteration |

RAG embeddings are computed on first run and cached in `rag-cache/`. After editing the guide, reload with:

```bash
curl -X POST http://localhost:8080/api/reload
```

---

## API

### `POST /api/chat/stream`
SSE streaming chat endpoint.

```json
{
  "messages": [
    { "role": "user", "content": "Create a leave approval workflow with manager and HR steps" }
  ]
}
```

Events:
- `chunk` → `{"text": "..."}` — streaming token
- `done` → `{"full": "complete response"}` — save this to history
- `error` → error message string

### `GET /api/config`
Returns active configuration (provider, model, context mode, RAG settings).

### `GET /api/runs?limit=20`
Returns the last N run log entries (newest first) with token counts, cost estimates, and duration.

### `POST /api/reload`
Recomputes RAG embeddings after updating the guide.

### `GET /api/health`
Health check.

---

## Run log

Every generation appends a JSON line to `run-log.jsonl`:

```json
{
  "timestamp": "2025-03-22T10:30:00Z",
  "llm": "claude",
  "model": "claude-sonnet-4-5",
  "contextMode": "full",
  "promptTokens": 42000,
  "outputTokens": 3100,
  "cacheReadTokens": 40500,
  "durationMs": 6200,
  "estimatedCostUsd": 0.0031,
  "userPrompt": "Create a leave request workflow..."
}
```

Analyze across runs:

```python
import pandas as pd
df = pd.read_json('run-log.jsonl', lines=True)
df.groupby(['llm', 'contextMode'])[['estimatedCostUsd', 'durationMs']].mean()
```

---

## Just want to try the guides?

No setup needed. Copy the contents of `petriflow_reference.md` (the shorter one, ~50KB) into any LLM system prompt and ask:

> *"Create a simple document approval workflow with a submitter and an approver. The approver can approve or reject. On rejection, the submitter can revise and resubmit."*

The guide tells the model what rules to follow, what errors to avoid, and how to structure the output. See what it produces — and if you find cases where it fails, I want to know.

---

## Windows / Java 11 TLS

If you get an SSL handshake error:

```bash
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dhttps.protocols=TLSv1.2,TLSv1.3 -Djdk.tls.client.protocols=TLSv1.2,TLSv1.3"
```

(Already set in `pom.xml`, so plain `mvn spring-boot:run` should work on most setups.)

---

## License

Copyright © 2026 Ľuboš Petrovič. All rights reserved.

This project and all associated documentation are proprietary and confidential. Unauthorized copying, modification, distribution, or use is strictly prohibited without prior written permission from the copyright holder.

See [LICENSE](LICENSE) for full terms.

---

## Contributing / Feedback

This is an open research effort. If you've worked on:
- Prompting strategies for schema-bound code generation
- RAG tuning for structured outputs
- Fine-tuning or few-shot approaches for XML-heavy DSLs
- Evaluation of generated structured documents

...open an issue, start a discussion, or reach out directly. Any direction helps.