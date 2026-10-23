<div align="center">

# HAgent

**Enterprise-grade AI Chat & Knowledge Base Assistant built with Spring Boot 4 and Spring AI 2.0**

*Streaming Chat · RAG Pipeline · Agent Tool Loop · Multi-turn Memory · Admin Console*

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.1-green.svg)](https://spring.io/projects/spring-ai)
[![Vue](https://img.shields.io/badge/Vue-3-42b883.svg)](https://vuejs.org/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-18%20%2B%20pgvector-336791.svg)](https://www.postgresql.org/)
[![GitHub Repo](https://img.shields.io/badge/GitHub-yphyphyph%2Fhagent-black.svg?logo=github)](https://github.com/yphyphyph/hagent)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)

[简体中文](./README.md) | [English](#introduction)

</div>

---

## Table of Contents

- [Introduction](#introduction)
- [System Architecture](#system-architecture)
- [Key Features](#key-features)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Getting Started](#getting-started)
- [Environment Variables](#environment-variables)
- [Production Deployment Notes](#production-deployment-notes)
- [Runtime Configuration (Hot Reload)](#runtime-configuration-hot-reload)
- [API Overview](#api-overview)
- [License](#license)

---

## Introduction

HAgent is a **decoupled, enterprise-grade AI chat and knowledge-base assistant** covering the full pipeline: Retrieval-Augmented Generation (RAG), Agent tool calling, multi-turn conversation memory, and production deployment. It works both as a standalone AI customer-service / knowledge-base product and as a complete reference implementation for learning Spring AI 2.0 and production-grade RAG engineering.

Three core design goals:

1. **Trustworthy answers** — every knowledge-base answer is grounded in real retrieval results. Semantic reranking and an evidence gate filter out noise; when nothing relevant is found, the assistant explicitly says "not found in the knowledge base" instead of hallucinating.
2. **Transparent process** — which document chunks were retrieved, every reasoning step, and every tool call are streamed to the frontend in real time via SSE events. Every answer is traceable to its sources.
3. **Controllable behavior** — the Agent tool loop is guarded by duplicate-call detection, consecutive-miss termination, and a total time budget, eliminating runaway "endless re-search with reworded queries" loops.

### Highlights

- **Streaming chat experience** — an SSE event protocol that interleaves thinking deltas, answer tokens, retrieval sources, and context usage in real time, with an 8-stage thinking animation on the frontend;
- **Complete RAG pipeline** — query rewrite (synonym mapping + LLM coreference resolution) → hybrid retrieval (vector + keyword channels fused with RRF) → semantic reranking (real Rerank model call) → evidence gate (whole-batch discard on low relevance) → streamed generation with source citations;
- **Agent tool loop** — knowledge search, Text-to-SQL data analysis, image understanding, and MCP tool extension, guarded by multiple convergence mechanisms and a 90-second total time budget;
- **Multi-turn memory** — history window + LLM summary compression, bilingual token estimation (CJK at 1.5 chars/token), and a live usage ring on the frontend;
- **Visual admin console** — AI model parameters (persisted in DB, hot-reloaded without restart), knowledge base management (Tika parsing / chunking / vectorization / visibility), Text-to-SQL table policy, MCP tool management, and system log auditing.

---

## System Architecture

The retrieval and generation pipeline consists of 7 stages. The diagram below shows the full path from a user question to the streamed answer (light/dark adaptive):

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="assets/hagent-arch-dark.svg">
  <img src="assets/hagent-arch.svg" alt="HAgent Architecture" width="100%">
</picture>

The backend is organized into four single-direction modules:

```
hagent-back
├── hagent-server            # Bootstrap: Sa-Token auth, system settings, global config
├── hagent-ai                # Core: chat / RAG / Agent / memory / tools / MCP
├── hagent-module-knowledge  # Knowledge base: parsing, chunking, vectorization, permissions
└── hagent-common            # Infrastructure: annotations, aspects, exceptions, result wrappers
```

Dependencies are strictly one-directional: `server → ai / knowledge → common`. No business logic lives in the bootstrap module.

---

## Key Features

### 1. Portal & Streaming Chat

The portal page and chat stream are available without login; signing in unlocks full multi-turn sessions. Thinking stages, answer tokens, retrieval sources, and context usage are delivered as structured JSON events over SSE.

![Portal Home](assets/portal-home.png)

![RAG Chat with Context Usage](assets/chat-rag-context.png)

### 2. RAG Knowledge Base Q&A

- **Query rewrite** — coreferences ("it", "this", "mentioned above") are automatically resolved in multi-turn conversations, combined with a synonym mapping table (e.g. `assistant=HAgent`) to improve recall. LLM rewrite has a 3-second hard timeout with fallback to the original query; consecutive timeouts trip a 60-second circuit breaker;
- **Hybrid retrieval** — a pgvector vector channel and a PostgreSQL full-text keyword channel retrieve in parallel, fused with Reciprocal Rank Fusion (k=20);
- **Semantic reranking** — the top-20 candidates are reranked by a Rerank model down to top-5; reranking failures fall back to the original order without blocking the pipeline;
- **Evidence gate** — when the rerank score is below the threshold (default 0.2), the whole batch is discarded. On empty retrieval the model is explicitly told "not found in the knowledge base — answer from your own knowledge or admit it", eliminating fabrication;
- **Source citations** — cited chunks (file name, score, excerpt) are streamed in real time and rendered on the frontend.

### 3. Agent Tool Loop

The model autonomously decides which tools to call, looping until convergence:

| Tool | Description |
| --- | --- |
| `searchKnowledge` | Semantic knowledge-base search (full RAG pipeline) |
| `queryBusinessData` | Text-to-SQL business data query (built-in SQL security validator: read-only, column blacklist, table policy whitelist) |
| Image understanding | Multimodal model recognizes pasted images |
| MCP tools | Extensions exposed via the MCP protocol (`@McpTool`) |

Convergence guards:

- N consecutive similar calls to the same tool trigger a forced convergence instruction;
- N consecutive irrelevant knowledge retrievals (RAG_MISS) inject a "stop searching, answer directly" instruction;
- A 90-second total time budget for the tool loop; on timeout the loop forcibly converges into the streaming stage;
- Text-form tool calls (`<tool_call>` tags) are stripped by a cross-token state machine, with hallucinated-tag fallback filtering;
- If the streamed answer is detected as an "intent-declaration stub" (e.g. it only outputs "I need to search the knowledge base first…" and stops), a corrective instruction is injected and the stream is re-run once — a mechanical fix for half-answers caused by reasoning-model flip-flops in two-stage rewriting.

### 4. Image Understanding

Paste an image directly into the chat; a multimodal model recognizes its content and answers in combination with the knowledge base.

![Image Understanding](assets/image-understanding.png)

### 5. Visual Admin Console

**AI model parameters** — Base URL, API key, and parameters for chat / embedding / rerank / multimodal models are persisted in the database and hot-reloaded on change, no restart required. Keys are masked on display (first 4 + `****` + last 4), and placeholder values are skipped on save.

![AI Model Settings](assets/admin-settings-ai.png)

![AI Model Settings (cont.)](assets/admin-settings-ai2.png)

**MCP tool management** — view and configure tools exposed via the MCP protocol.

![MCP Tools](assets/admin-mcp-tools.png)

**Knowledge base management** — document upload (Alibaba Cloud OSS), Tika parsing, smart chunking, vectorization, and visibility permissions (public / private).

![Knowledge Base](assets/admin-knowledge.png)

**Text-to-SQL table policy** — configure which business tables may be queried in natural language, backed by the SQL security validator (read-only statements, sensitive-column blacklist, enforced LIMIT).

![SQL Table Policy](assets/admin-sql-policy.png)

**System logs** — operation and system log auditing; sensitive actions such as login and password changes are fully recorded.

![System Logs](assets/admin-logs.png)

---

## Tech Stack

### Backend

| Technology | Version | Purpose |
| --- | --- | --- |
| Java | 17 | Runtime |
| Spring Boot | 4.1.1 | Application framework |
| Spring AI | 2.0.1 (BOM) | Chat / Embedding / tool calling / MCP |
| MyBatis-Plus | latest | ORM (logic delete, camel-case mapping) |
| PostgreSQL | 18 + pgvector | Business DB + vector store |
| Redis | - | Sa-Token sessions, rate limiting, RAG context cache |
| Druid | - | Connection pool |
| Hutool | - | BCrypt password hashing, utilities |
| Tika | - | Knowledge base document parsing |
| Alibaba Cloud OSS | - | Knowledge base file storage |

### Frontend

| Technology | Version | Purpose |
| --- | --- | --- |
| Vue | 3 (Composition API) | Frontend framework |
| Vite | 6 | Build tool (vue-tsc type check gate) |
| Naive UI | - | Component library |
| vue-router | - | Routing (History mode) |
| Pinia / Composables | - | State management and SSE stream aggregation |

### Infrastructure

| Component | Purpose |
| --- | --- |
| Docker Compose | One-command deployment (backend + frontend containers) |
| Nginx | Embedded in the frontend container: static hosting + `/api` prefix-stripping reverse proxy + SSE no-buffering |
| Let's Encrypt | HTTPS certificates |

---

## Project Structure

```
HAgent/
├── assets/                          # README assets (screenshots, architecture diagram)
├── database/backup/                 # Legacy database snapshot
├── docs/                            # Design documents (local)
├── hagent-back/                     # Backend (multi-module Maven)
│   ├── hagent-server/               # Bootstrap + auth + system settings
│   ├── hagent-ai/                   # Chat / RAG / Agent / memory / tools / MCP
│   │   └── src/main/java/com/hagent/ai/
│   │       ├── core/                # AgentToolLoopService (tool loop), SqlSecurityValidator
│   │       ├── retrieval/           # HybridSearchService / RerankingService / EvidenceGate
│   │       ├── service/impl/        # ChatServiceImpl (SSE orchestration), QueryRewriteServiceImpl
│   │       ├── tool/                # KnowledgeTool / TextToSqlTool implementations
│   │       ├── memory/              # ConversationMemoryService (summary compression)
│   │       └── mcp/                 # MCP tool exposure
│   ├── hagent-module-knowledge/     # Knowledge base: parsing / chunking / vectorization / permissions
│   └── hagent-common/               # Annotations / aspects / exceptions / result wrappers
├── hagent-front/                    # Frontend (Vue 3 + Vite 6 + Naive UI)
│   └── src/
│       ├── views/                   # Portal / chat / admin views
│       ├── components/chat/         # Message stream, SSE aggregation, thinking animation
│       ├── api/                     # API wrappers (unified /api prefix)
│       └── utils/                   # request, auth utilities
├── docker-compose.yml               # Production orchestration (backend + frontend)
└── start_hagent.sh                  # One-command deploy script (clone/pull → compose build → up)
```

---

## Getting Started

### Prerequisites

| Component | Version |
| --- | --- |
| JDK | 17+ |
| Node.js | 20+ |
| Maven | 3.9+ |
| Docker + Docker Compose | v2 (with BuildKit) |
| PostgreSQL | 18, with the `pgvector` extension installed |
| Redis | 6+ |

### Option 1: One-command Docker Deployment (Recommended)

```bash
# 1. Clone the repository
git clone https://github.com/yphyphyph/hagent.git
cd HAgent

# 2. Prepare environment variables (fill in real credentials, see Environment Variables below)
cp hagent-back/.env.example .env
vi .env

# 3. Build and start (backend :8099, frontend :3002)
chmod +x start_hagent.sh
./start_hagent.sh

# 4. Follow the backend logs
docker compose logs -f backend
```

Once started, visit `http://<server-ip>:3002` for the portal. A built-in `admin` account is provided — change the password immediately after the first deployment under "Settings → Change Password".

### Option 2: Local Development

```bash
# 1. Initialize the database: create the hagent database in PostgreSQL 18 and enable pgvector
createdb hagent
psql -d hagent -c "CREATE EXTENSION IF NOT EXISTS vector;"
# Reference snapshot for table creation: database/backup/hagent.sql (adjust as needed)

# 2. Configure local backend credentials (gitignored, never committed)
cp hagent-back/hagent-server/src/main/resources/application-local.yml.example \
   hagent-back/hagent-server/src/main/resources/application-local.yml
# Edit application-local.yml: database / Redis / OSS / AI model credentials

# 3. Start the backend (port 8099)
mvn -q spring-boot:run -pl hagent-server -f hagent-back/pom.xml

# 4. Start the frontend (port 3002, /api proxied to 8099)
npm install --prefix hagent-front
npm run dev --prefix hagent-front
```

---

## Environment Variables

In production, sensitive credentials are injected through a root-level `.env` file (template: `hagent-back/.env.example`):

| Variable | Required | Description |
| --- | --- | --- |
| `HAGENT_DB_HOST` | Yes | PostgreSQL host (`host.docker.internal` recommended inside containers to reach the host) |
| `HAGENT_DB_PASSWORD` | Yes | PostgreSQL password |
| `DB_URL` | No | Full JDBC URL override (default assembles from `HAGENT_DB_HOST`, database `hagent`) |
| `DB_USERNAME` | No | Database username (default `postgres`) |
| `HAGENT_REDIS_HOST` | Yes | Redis host (default `host.docker.internal`) |
| `HAGENT_REDIS_PORT` | Yes | Redis port (default 6379) |
| `HAGENT_REDIS_PASSWORD` | Yes | Redis password |
| `HAGENT_OSS_ACCESS_KEY_ID` | Yes | Alibaba Cloud OSS AccessKey (knowledge base file upload) |
| `HAGENT_OSS_ACCESS_KEY_SECRET` | Yes | Alibaba Cloud OSS Secret |
| `JWT_SECRET_KEY` | Recommended | JWT fallback secret (inject a long random string) |
| `OPENAI_API_KEY` | Optional | Startup fallback for the AI model key (normally configured and persisted via the admin console) |
| `SPRING_PROFILES_ACTIVE` | Fixed | Injected as `prod` by compose (disables Swagger, quiet logging) |

---

## Production Deployment Notes

### Nginx Reverse Proxy

The Nginx embedded in the frontend container already handles static hosting, `/api` prefix-stripping, and same-origin proxy cleanup. The host Nginx only needs TLS termination and forwarding. **AI chat is SSE-based — proxy buffering must be disabled**:

```nginx
server {
    listen 443 ssl;
    server_name hagent.example.com;
    ssl_certificate     /etc/letsencrypt/live/hagent.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/hagent.example.com/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    client_max_body_size 50M;    # knowledge base document upload

    location / {
        proxy_pass http://127.0.0.1:3002;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # SSE streaming (AI chat): disable buffering + extend read timeout,
    # otherwise streamed answers stutter into a single dump
    location /api/ {
        proxy_pass http://127.0.0.1:3002;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 300s;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

### Key Design Notes

- **Path conventions** — the backend `context-path` is `/`; controllers use prefix-free paths such as `/auth/*` and `/admin/*`. The frontend uses `/api` as its baseURL, and the frontend container's Nginx strips the prefix before forwarding to port 8099;
- **SSE event protocol** — `meta` (session metadata & context estimate) → `stage` (thinking stage) → `thinking` (reasoning delta, deduplicated against gateway replays) → `source` (retrieval sources) → `token` (answer delta, JSON-escaped to preserve order) → `context-update` (actual usage calibration, stage=actual) → `[FINAL]` (end marker);
- **Security** — Sa-Token sessions + BCrypt passwords, per-endpoint `@RateLimit`, `@RequireAdmin` role checks, `@OperationLog` auditing of sensitive actions (password operations never log parameters), and the SQL security validator (read-only + sensitive-column blacklist + table policy whitelist).

---

## Runtime Configuration (Hot Reload)

The following settings live in the `system_config` table and take effect **immediately after being changed in the admin console — no restart required**:

| Key | Description | Default |
| --- | --- | --- |
| `ai.query-rewrite.enabled` | Query rewrite toggle | `true` |
| `ai.query-rewrite.synonyms` | Synonym mapping (`key=value`, comma-separated) | as needed |
| `ai.rerank.model` / `base-url` / `api-key` | Semantic rerank model | qwen series |
| `ai.chat.context-window` | Context window token limit | `128000` |
| `ai.tools.max-iterations` | Max Agent tool-loop iterations | - |
| `ai.chat.api-key` / `ai.embedding.api-key` etc. | Model credentials (masked on display) | - |

---

## API Overview

| Group | Endpoint | Description |
| --- | --- | --- |
| Auth | `POST /auth/login` | Login (60s/10 attempts rate limit) |
| Auth | `GET /auth/me` | Current user info |
| Auth | `PUT /auth/password` | Change password (60s/5 attempts rate limit) |
| Auth | `PUT /auth/profile` | Update nickname / avatar |
| Chat | `GET /chat/stream` (SSE) | Streaming chat (multi-turn, images, tool loop) |
| Conversations | `/chat/conversations/**` | Conversation CRUD, message paging, summaries |
| Knowledge | `/admin/knowledge/**` | File upload / chunk preview / visibility |
| Admin | `/admin/ai/**` | AI model parameters (with connection test) |
| Admin | `/admin/mcp/**`, `/admin/sql-policy/**` | MCP tools and SQL table policy |
| Admin | `/admin/system-log/**`, `/admin/system/settings` | Log auditing and system settings |

For the complete API, refer to the Swagger docs (auto-enabled in dev, disabled in prod).

---

## License

This project is licensed under the [MIT License](./LICENSE).

---

<div align="center">

**If this project helps you, please consider giving it a Star**

</div>
