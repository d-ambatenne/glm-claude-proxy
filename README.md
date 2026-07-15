# glm-claude-proxy

A small Kotlin/Ktor HTTP proxy that exposes an **Anthropic Messages API** locally
and translates it to the **OpenAI Chat Completions** format, forwarding requests to a
[LiteLLM](https://github.com/BerriAI/litellm) upstream. It lets Anthropic-API clients
(claude code, junie, etc.) talk to a non-Anthropic model — by default
`hetzner/zai-org/GLM-5.2-FP8`.

The proxy is transparent: it converts the request, calls upstream, and converts the
response back. It supports **streaming** (SSE) and **non-streaming** responses,
**tools / tool-use**, **system prompts**, **images**, and **stop sequences**.

## How it works

```
Anthropic client                glm-claude-proxy                  LiteLLM upstream
    │                              │                                    │
    │── POST /v1/messages ────────▶│                                    │
    │   (Anthropic Messages JSON)  │                                    │
    │                              │── RequestTranslator ──┐            │
    │                              │   Anthropic → OpenAI   │            │
    │                              │◀───────────────────────┘            │
    │                              │── POST /v1/chat/completions ──────▶│
    │                              │   (OpenAI Chat Completions JSON)    │
    │                              │◀── response / SSE stream ──────────│
    │                              │── ResponseTranslator / StreamTranslator
    │                              │   OpenAI → Anthropic               │
    │◀── Anthropic response/SSE ──│                                    │
```

- **RequestTranslator** — Anthropic `MessagesRequest` → OpenAI `ChatCompletionRequest`
  (system prompts, content blocks, tool definitions, tool results, images, stop sequences).
- **ResponseTranslator** — OpenAI response → Anthropic `MessagesResponse` (non-streaming).
- **StreamTranslator** — OpenAI streaming chunks → Anthropic SSE events
  (`message_start`, `content_block_start/delta/stop`, `message_delta`, `message_stop`).

## Endpoints

| Method | Path           | Description                                                        |
|--------|----------------|--------------------------------------------------------------------|
| `GET`  | `/health`      | Health check — returns `{"status":"ok"}`.                          |
| `GET`  | `/v1/models`   | Lists the configured default model in an Anthropic-style payload. |
| `POST` | `/v1/messages` | The main entry point. Accepts an Anthropic Messages request.       |

Set `"stream": true` in the request body to receive a Server-Sent Events stream.

## Requirements

- **JDK 21** (build and runtime)
- **Amper** — the JetBrains Kotlin toolchain, bundled in this repo as `./amper`
  (macOS/Linux) or `amper.bat` (Windows). No separate Gradle/Maven install needed.

## Configuration

All settings live in [`src/main/resources/application.conf`](src/main/resources/application.conf)
(HOCON). Every value can be overridden with an environment variable.

| Setting                | Env var                      | Default                              | Description                                                            |
|------------------------|------------------------------|--------------------------------------|------------------------------------------------------------------------|
| `server.port`          | `PROXY_PORT`                 | `8948`                               | Port the proxy listens on.                                             |
| `upstream.baseUrl`     | `UPSTREAM_BASE_URL`          | `https://litellm.labs.jb.gg`         | LiteLLM upstream base URL.                                             |
| `upstream.apiKey`      | `UPSTREAM_API_KEY`           | *(committed demo key)*               | Bearer token sent to the upstream.                                    |
| `upstream.defaultModel`| `UPSTREAM_DEFAULT_MODEL`     | `hetzner/zai-org/GLM-5.2-FP8`        | Model reported by `/v1/models`.                                         |
| `upstream.timeoutSeconds`| `UPSTREAM_TIMEOUT_SECONDS` | `300`                                | Upstream request timeout in seconds.                                   |
| `debug.logRequests`    | `PROXY_DEBUG_LOG_REQUESTS`   | `true`                               | Log every request after it completes as `METHOD path -> status`.      |
| `debug.logBodies`      | `PROXY_DEBUG_LOG_BODIES`     | `false`                              | Log raw JSON bodies (`>>>` sent upstream, `<<<` received downstream).  |

> **Note:** `application.conf` ships with a demo upstream API key. Set
> `UPSTREAM_API_KEY` (and review `UPSTREAM_BASE_URL`) before deploying anywhere real.

## Build & run

```bash
# Run the proxy (Amper resolves the JDK, dependencies, and compiles on first run)
./amper runJvm
```

The server starts on port `8948` (or `PROXY_PORT`).

### Tests

```bash
./amper testJvm
```

Unit tests cover the request, response, and stream translators
(`src/test/kotlin/.../translate/`).

### Build a runnable JAR

```bash
./amper executableJarJvm   # produces a self-contained executable JAR
```

### Run an example request

```bash
# Non-streaming
curl http://localhost:8948/v1/messages \
  -H 'content-type: application/json' \
  -d '{
    "model": "claude-3-5-sonnet",
    "max_tokens": 128,
    "messages": [{"role": "user", "content": "Say hello in one sentence."}]
  }'

# Streaming (SSE)
curl -N http://localhost:8948/v1/messages \
  -H 'content-type: application/json' \
  -d '{
    "model": "claude-3-5-sonnet",
    "max_tokens": 128,
    "stream": true,
    "messages": [{"role": "user", "content": "Count to five."}]
  }'
```

The `model` in the request is echoed back in the response; the actual model served
is the upstream's `defaultModel`. Point your Anthropic-compatible client at
`http://localhost:8948` as its base URL.

## Project layout

```
src/main/kotlin/com/jetbrains/glmproxy/
├── Application.kt              # main() — loads config, starts Ktor
├── client/LiteLlmClient.kt     # Ktor HTTP client to the LiteLLM upstream
├── config/ProxyConfig.kt       # HOCON config binding
├── model/
│   ├── anthropic/              # Anthropic request/response/stream models
│   └── openai/                 # OpenAI Chat Completions models
├── server/
│   ├── HttpServer.kt           # Ktor plugins (JSON, StatusPages, request logging)
│   └── Routes.kt               # /health, /v1/models, /v1/messages
└── translate/                  # Anthropic <-> OpenAI translation logic
src/main/resources/
├── application.conf            # config (see Configuration)
└── logback.xml                 # logging config
module.yaml                      # Amper build / dependency descriptor
```

## Tech stack

- **Kotlin** 2.3.20 on **JDK 21**
- **Ktor** 3.5.1 (CIO server & client) — HTTP, content negotiation, SSE
- **kotlinx.serialization** (JSON)
- **Logback** for logging, **typesafe-config** for HOCON
- **Amper** as the build toolchain (`module.yaml`)
