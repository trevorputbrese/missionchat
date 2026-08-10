# MissionChat

A Spring Boot demonstration platform showing three distinct patterns for building AI-backed
applications on Cloud Foundry: a **plain chatbot**, an **MCP tool-calling client**, and a
**retrieval-augmented generation (RAG) document assistant**.

The app is intended as a working reference and demo — something you can deploy to a Cloud
Foundry foundation, bind to platform AI services, and use to show each capability end to end.
It is not a production-hardened system; see [Security posture](#security-posture).

---

## What it demonstrates

| Surface | Route | Demonstrates |
| --- | --- | --- |
| **Mission Chat** | `/missionchat` | Straight LLM chat against any OpenAI-compatible endpoint |
| **Cables Chat** | `/cableschat` | An **MCP client** — the model calls tools exposed by bound MCP servers |
| **Document Chat** | `/documentchat` | **RAG** — upload documents, embed them into pgvector, chat grounded in the results |
| **AI Sandbox** | `/aisandbox` | Static landing/scratch page |

All three chat views render assistant replies as markdown (via `marked`) and sanitise the
result with `DOMPurify` before it reaches the DOM, since model output is untrusted input.

Each surface degrades gracefully. If a capability's backing service isn't bound, the feature
reports itself unavailable through a status endpoint rather than failing at request time —
so you can deploy the app with only some services bound and still demo the rest.

---

## Architecture

- **Java 21** / **Spring Boot 3.5.12** / **Spring AI 1.1.3**
- Static HTML/CSS/JS frontend (no build step, no framework)
- **pgvector** on Postgres for the vector store
- **MCP Streamable HTTP** transport for tool calling
- Packaged as an executable JAR, deployed with the offline Java buildpack

### Mission Chat
Thin wrapper over Spring AI's `ChatClient` pointed at `spring.ai.openai.*`. Because the base
URL is configurable, this works against OpenAI proper or any OpenAI-compatible endpoint,
including a platform-provided model service.

### Cables Chat (MCP)
[`CablesMcpRegistry`](src/main/java/gov/state/missionchat/cableschat/CablesMcpRegistry.java)
discovers MCP servers at startup from two sources:

1. **`VCAP_SERVICES`** — any bound service instance whose credentials contain an
   `mcpServiceURL` (or `mcpServiceUrl`) key
2. **`CABLESCHAT_MCP_URLS`** — a comma-separated list, for local development

Each discovered server is initialised over Streamable HTTP with an 8-second timeout. A server
that fails to connect is logged and skipped rather than blocking startup, and connected
servers' tools are handed to the model as a `ToolCallbackProvider`. `GET /api/cableschat/mcp/status`
reports which servers registered.

### Document Chat (RAG)
[`DocumentsRagService`](src/main/java/gov/state/missionchat/documentchat/DocumentsRagService.java)
activates only when both a **non-H2 (Postgres) `DataSource`** and an **`EmbeddingModel`** are
available. Uploads are chunked with `TokenTextSplitter` and written to a `PgVectorStore`.

- Accepted uploads: **PDF** (page-per-document), **`.txt`**, **`.text`**, **`.md`**
- Schema is auto-initialised; the table is recreated automatically if the embedding model's
  vector dimensions stop matching the existing table
- Default table `localdocs`, top-K 10, chunk size 800 — all overridable (see [Configuration](#configuration))

---

## Deploying to Cloud Foundry

### 1. Build

```bash
mvn clean package
```

Produces `target/missionchat-0.0.1-SNAPSHOT.jar`, the artifact referenced by `manifest.yml`.

### 2. Create the backing services

The service *names* are supplied via a vars file rather than hardcoded in the manifest, so the
same manifest works across foundations. The defaults in `vars-dev.yml` / `vars-prod.yml` are:

| Service | Purpose |
| --- | --- |
| `gpt-oss-mission-chat` | Chat completion model |
| `postgres-mcp-server` | MCP server exposing tools (must expose `mcpServiceURL` in its credentials) |
| `missionchat-embeddings-model` | Embedding model for RAG |
| `missionchat-vectordb` | Postgres instance with pgvector |

Bind only what you need — unbound capabilities simply report as unavailable.

### 3. Push

```bash
cf push --vars-file vars-dev.yml
```

`manifest.yml` uses `services: ((services))`, so the vars file supplies the binding list.
Copy `vars-dev.yml` to add a foundation of your own; note that `.gitignore` ignores
`vars-*.yml` by default apart from the checked-in `vars-dev.yml` and `vars-prod.yml`, so your
local variants stay out of git.

### 4. Set the shared password — required

The app is gated behind a shared password that is read from the environment. **It has no
default, and the gate fails closed: if this variable is unset, nobody can unlock the app.**

```bash
cf set-env missionchat MISSIONCHAT_SHARED_PASSWORD '<a-strong-password>'
cf restage missionchat
```

Use single quotes so your shell doesn't interpret `!`, `$`, or other special characters.

### 5. Configure the model endpoint

If the chat model isn't supplied through a bound service, set it explicitly:

```bash
cf set-env missionchat SPRING_AI_OPENAI_BASE_URL 'https://your-endpoint/v1'
cf set-env missionchat SPRING_AI_OPENAI_API_KEY '<key>'
cf set-env missionchat SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL 'gpt-4o-mini'
cf restage missionchat
```

---

## Running locally

```bash
cp .env.local.example .env.local   # then fill in real values
mvn spring-boot:run
```

`.env.local` is gitignored and must never be committed. At minimum set
`MISSIONCHAT_SHARED_PASSWORD` and your model credentials; without the former you will not be
able to get past `/unlock`.

RAG stays disabled locally unless you point the app at a real Postgres instance with pgvector —
an H2 datasource is explicitly rejected, and the status endpoint will tell you so.

```bash
mvn test    # 10 integration tests covering the password gate
```

---

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `MISSIONCHAT_SHARED_PASSWORD` | *(none — gate fails closed)* | **Required.** Unlock password |
| `SPRING_AI_OPENAI_BASE_URL` | `https://api.openai.com/v1` | Chat endpoint |
| `SPRING_AI_OPENAI_API_KEY` | `not-configured` | API key |
| `SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL` | `gpt-4o-mini` | Model name |
| `SPRING_AI_OPENAI_CHAT_OPTIONS_TEMPERATURE` | `0.2` | Sampling temperature |
| `SPRING_AI_OPENAI_CHAT_COMPLETIONS_PATH` | `/chat/completions` | Override for providers with a different path |
| `CABLESCHAT_MCP_URLS` | *(none)* | Comma-separated MCP server URLs (local dev; production uses `VCAP_SERVICES`) |
| `DOCUMENTSCHAT_RAG_TABLE_NAME` | `localdocs` | pgvector table |
| `DOCUMENTSCHAT_RAG_TOP_K` | `10` | Chunks retrieved per query |
| `DOCUMENTSCHAT_RAG_CHUNK_SIZE` | `800` | Token chunk size |
| `DOCUMENTSCHAT_RAG_MIN_CHUNK_SIZE_CHARS` | `120` | Minimum chunk size |
| `DOCUMENTSCHAT_RAG_MIN_CHUNK_LENGTH_TO_EMBED` | `5` | Skip chunks shorter than this |
| `DOCUMENTSCHAT_RAG_MAX_NUM_CHUNKS` | `10000` | Cap on chunks per upload |
| `DOCUMENTSCHAT_RAG_EMBEDDINGS_PATH` | *(empty)* | Override embeddings path |

---

## HTTP API

Every route below the auth gate requires an unlocked session. API routes return
`401` with a JSON body when unauthenticated; page routes redirect to `/unlock`.

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/unlock` | Exchange the shared password for a session |
| `POST` | `/logout` | Invalidate the session |
| `POST` | `/api/chat` | Mission chat completion |
| `GET` | `/api/chat/model/status` | Whether a model is configured |
| `POST` | `/api/cableschat` | Chat with MCP tools available to the model |
| `GET` | `/api/cableschat/mcp/status` | Registered MCP servers |
| `POST` | `/api/documentchat` | Chat grounded in indexed documents |
| `GET` | `/api/documentchat/rag/status` | Whether RAG is available, and why not if it isn't |
| `GET` | `/api/documentchat/documents` | List indexed documents |
| `POST` | `/api/documentchat/documents` | Upload and index documents (multipart, field `files`) |
| `DELETE` | `/api/documentchat/documents` | Clear the index |

---

## Security posture

This is demo software. Understand these limits before exposing it anywhere real:

- **The shared password is a demonstration gate, not authentication.** There are no user
  accounts, no roles, and no audit trail — everyone who unlocks it is the same anonymous
  session. Don't put anything sensitive behind it.
- **Sessions last 2 hours** of inactivity.
- **No secrets belong in this repository.** Credentials come from the environment or from
  bound service credentials in `VCAP_SERVICES`. `.gitignore` excludes `.env*`, `vars-*.yml`,
  `*service-key*`, and common key formats — keep it that way.
- **`DELETE /api/documentchat/documents` truncates the whole index** and is reachable by any
  unlocked session.
- Uploaded documents are embedded through whichever model you configure. Don't upload anything
  you aren't willing to send to that provider.
