# Petriflow Generation

> **A tool for building enterprise process applications from natural language.**  
> Describe your workflow in plain text - get a valid, deployable [PetriFlow](https://petriflow.com) XML process in seconds.

---

## Table of Contents

1. [Quick Start](#1-quick-start)
2. [What this is](#2-what-this-is)
3. [The Netgrif ecosystem](#3-the-netgrif-ecosystem)
4. [How it works](#4-how-it-works)
5. [Example workflows](#5-example-workflows)
6. [Supported providers and models](#6-supported-providers-and-models)
7. [Context modes](#7-context-modes)
8. [Key features](#8-key-features)
9. [Under the hood: the reference document](#9-under-the-hood-the-reference-document)
10. [API](#10-api)
11. [Windows / Java 11 TLS](#11-windows--java-11-tls)
12. [License](#12-license)

---

## 1. Quick Start

### Option 1: Docker (Recommended)

```bash
# 1. Copy environment template
cp .env.example .env

# 2. Add your API keys (at least one)
# ANTHROPIC_API_KEY, OPENAI_API_KEY, or GEMINI_API_KEY

# 3. Start
docker-compose up
```

Open `http://localhost:8080`

### Option 2: Local Maven

```bash
cp src/main/resources/application.properties.example src/main/resources/application.properties
# Edit application.properties - add API keys and choose provider
mvn spring-boot:run
```

### Prerequisites

**Option 1 (Docker):** Docker and Docker Compose — Java is bundled in the container.  
**Option 2 (Local Maven):** Java 11, Maven 3.8+

---

## 2. What this is

Enterprise process applications are hard to build. A workflow that looks simple on a whiteboard - submit, review, approve, notify - quickly becomes hundreds of lines of configuration: forms, roles, routing logic, automation scripts, email triggers, cross-process integrations.

This tool lets you describe a workflow in natural language and generates a complete, deployable process application from it - validated and ready to run. Under the hood it targets [PetriFlow](https://petriflow.com), an open-source process language by [Netgrif](https://netgrif.com), which means the output isn't pseudocode or a diagram - it's a real application file you can import and run immediately.

Generating full application code - Java, C#, Angular, React - is expensive. A non-trivial application can easily run into tens of thousands of tokens per generation, and the output is rarely deployable without significant manual work. Targeting a declarative low-code language like PetriFlow changes the economics entirely: a complete, production-ready process typically fits in 500–2000 tokens of output, the structure is predictable, and validation is deterministic. The model has a much smaller, well-defined target to hit.

It supports three LLM backends (Claude, OpenAI, Gemini), two context strategies (full prompt vs. RAG), and includes a chat interface with persistent history so you can iterate on a process across sessions.

---

## 3. The Netgrif ecosystem

Generated PetriFlow XML doesn't live in isolation. Here's how it fits into the broader Netgrif stack:

**PetriFlow**  
The process definition language this tool targets. A single `.xml` file defines the complete application - data model, UI forms, role-based access, routing logic, Groovy automation, notifications. One file, one deployable process.

**Netgrif Application Builder**  
A visual editor for PetriFlow processes available at [builder.netgrif.com](https://builder.netgrif.com). You can import a process in three ways:
- **URL** - paste a publicly accessible link to a raw XML file (e.g. a raw GitHub file) and Builder fetches it directly
- **File upload** - upload the XML file manually from your local machine

**eTask**  
The Netgrif runtime where processes actually run. Users log in, see their tasks, fill in forms, and move cases through the workflow. eTask is available as a cloud-hosted environment at [etask.netgrif.com](https://etask.netgrif.com). You can import a process in three ways:
- **File upload** - upload the XML manually through the admin interface
- **API** - `POST /api/petrinet/upload` with the XML file

For a full walkthrough of how to create and deploy a process manually in Builder and eTask, watch this official guide: [Getting started with Netgrif Builder and eTask](https://youtu.be/FpwyhyO6yQ8?si=7rfuqAi5HKCz2hpl)

**The "Open in Builder" flow**

When you click "Open in Builder" in the chat interface, this is what happens:

1. The generated XML is pushed to a configured GitHub repository as a new file
2. The raw GitHub URL (which is publicly accessible) is passed to Netgrif Builder
3. Builder opens the process directly from that URL - no file download or manual upload needed
4. From Builder you can inspect the Petri net visually, make edits, and export or deploy to eTask

To use this feature, configure `github.token`, `github.username`, and `github.repo` in `application.properties` or `.env`. The repository must be public for Builder to access the raw file URL.

---

## 4. How it works

1. **Describe your process** - use the chat or pick a suggestion to get started
2. **The LLM generates PetriFlow XML** - guided by a detailed reference document covering the full schema, critical rules, and common patterns
3. **XML is automatically validated** - 15 structural checks run on every output
4. **If errors are found, a second call fires automatically** - the model sees the exact errors and self-corrects before you receive the response
5. **Copy the XML or open it directly in Netgrif Builder** - one click uploads to GitHub and opens the process in the visual editor

---

## 5. Example workflows

- **Public request & response** - anonymous submission form → routing to Legal or PR → public status page
- **Leave request approval** - employee submits → manager approves/rejects → HR confirms → email notifications
- **Email processing pipeline** - intake → AI classification + entity extraction → reviewer edits and routes
- **Order & invoice (linked processes)** - two separate processes with real-time cross-process embedding
- **Bug report lifecycle** - submission → triage → developer fix → QA verification loop
- **Purchase order (parallel review)** - Legal + Finance review in parallel, both must approve before final decision

These are also available as one-click suggestion chips in the UI.

---

## 6. Supported providers and models

| Provider | Default model | Alternatives |
|----------|--------------|-------------|
| **Claude** (Anthropic) | `claude-sonnet-4-6` | `claude-opus-4-6`, `claude-haiku-4-5` |
| **OpenAI** | `gpt-4.1` | `gpt-4o`, `gpt-4o-mini` |
| **Gemini** (Google) | `gemini-2.5-flash` | `gemini-2.0-flash`, `gemini-1.5-pro` |

Configure in `application.properties` or `.env`.

---

## 7. Context modes

| Mode | How it works | Best for |
|------|-------------|---------|
| `full` | Entire reference document injected into system prompt. Claude uses prompt caching to reduce cost on repeated calls. | Highest accuracy, complex processes |
| `rag` | Top-K most relevant chunks retrieved per query using embeddings. | Lower cost, faster iteration |

RAG embeddings are computed on first run and cached in `rag-cache/`. After editing the reference document, reload with:

```bash
curl -X POST http://localhost:8080/api/reload
```

**Recommendation:** Start with `full` — it produces the most reliable results and Claude's prompt caching keeps repeat costs low. Switch to `rag` if you are running high volumes of generations and cost is a concern, or if your process descriptions are short and focused.

---

## 8. Key features

**Two-pass XML validation**  
Every generated XML runs through 15 structural checks (well-formedness, invalid arc directions, missing imports, forbidden patterns, unbootstrapped system tasks, duplicate arcs, escaped characters, etc.). If any check fails, a second LLM call is made automatically with the exact errors - the corrected XML is what you receive.

**Chat persistence**  
Conversations are saved to `chats.json` and survive restarts. Switch between past sessions from the sidebar.

**Open in Builder**  
Generated XML can be uploaded directly to a GitHub repository and opened in Netgrif Builder for visual editing - one click from the chat. See [section 3](#3-the-netgrif-ecosystem) for how this works.

**Run log**  
Every generation is appended as a JSON line to `run-log.jsonl` with token counts, cost estimates, duration, and the prompt used. Useful for comparing providers and context strategies.

**Extended thinking**  
Configurable for both Claude and Gemini for more complex or ambiguous process descriptions.

---

## 9. Under the hood: the reference document

The quality of generation depends almost entirely on `petriflow_reference.md` - a ~60KB document that encodes:

- The full PetriFlow XML schema with annotated examples
- 9 critical rules covering the most common failure modes (arc direction, action phases, cross-process patterns, forbidden constructs)
- 15 validation checks that mirror what the runtime enforces
- Generation workflow (conversational vs. partial vs. fully specified requests)
- QueryDSL limitations and correct workarounds
- IPC patterns for cross-process communication

`petriflow_guide.md` (~250KB) is the human-readable companion - use it for understanding the schema or maintaining the reference.

**You can use the reference document without the app** - paste `petriflow_reference.md` into any LLM system prompt and ask it to generate a process. The app adds validation, retry, provider switching, chat history, and the Builder integration on top.

---

## 10. API

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
- `chunk` → `{"text": "..."}` - streaming token
- `done` → `{"full": "complete response"}` - save this to history
- `error` → error message string

### `GET /api/config`
Returns active configuration (provider, model, context mode, RAG settings).

### `GET /api/runs?limit=20`
Returns the last N run log entries (newest first).

### `POST /api/reload`
Recomputes RAG embeddings after updating the reference document.

### `GET /api/health`
Health check.

### `POST /api/reload`
Recomputes RAG embeddings after editing `petriflow_reference.md` or `petriflow_guide.md`. Only relevant when `context.mode=rag`. No request body needed.

---

## 11. Windows / Java 11 TLS

If you get an SSL handshake error on local Maven:

```bash
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dhttps.protocols=TLSv1.2,TLSv1.3 -Djdk.tls.client.protocols=TLSv1.2,TLSv1.3"
```

(Already set in `pom.xml`, so plain `mvn spring-boot:run` should work on most setups.)

---

## 12. License

Copyright © 2026 Ľuboš Petrovič. All rights reserved.

This project and all associated documentation are proprietary and confidential. Unauthorized copying, modification, distribution, or use is strictly prohibited without prior written permission from the copyright holder.

See [LICENSE](LICENSE) for full terms.