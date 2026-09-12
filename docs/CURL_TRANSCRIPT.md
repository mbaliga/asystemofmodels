# asom v1 — desktop curl transcript (P3 gate)

Captured live against `./gradlew :server:run` (fixture catalogue, fake drivers) on 2026-07-07.

```console
$ curl -s http://127.0.0.1:11435/admin/health -H "Authorization: Bearer asom-dev-token"
{"status":"ok","version":"0.1.0","hasLocalEngine":false,"catalogueVersion":1}

$ curl -s -X POST http://127.0.0.1:11435/v1/chat/completions -d "{}"   # no token → 401 NOT_PAIRED
{"error":{"message":"missing Authorization bearer token; pair with asom first","type":"authentication_error","param":null,"code":"NOT_PAIRED"}}

$ curl -si http://127.0.0.1:11435/v1/chat/completions -H "Authorization: Bearer asom-dev-token" \
    -d '{"model":"cheapest","messages":[{"role":"user","content":"hello asom"}]}'
HTTP/1.1 200 OK
X-Asom-Served-By: trainy-ai/llama-3.3-70b
X-Asom-Egress: cloud
X-Asom-Cost-Est: 0.00000058
X-Asom-Cost-Basis: usage
Content-Length: 302
Content-Type: application/json

{"id":"chatcmpl-fake-trainy-ai","object":"chat.completion","created":1783457758,"model":"llama-3.3-70b","choices":[{"index":0,"message":{"role":"assistant","content":"fake:trainy-ai/llama-3.3-70b:hello asom"},"finish_reason":"stop"}],"usage":{"prompt_tokens":2,"completion_tokens":9,"total_tokens":11}}

$ # the watched object: X-Asom-Served-By / X-Asom-Egress / X-Asom-Cost-Est + Basis on every /v1 response

$ curl -si http://127.0.0.1:11435/v1/chat/completions -H "Authorization: Bearer asom-dev-token" \
    -H "X-Asom-No-Train: true" -H "X-Asom-Policy: cheapest" \
    -d '{"model":"llama-3.3-70b","messages":[{"role":"user","content":"no training"}]}' | grep X-Asom
X-Asom-Served-By: openrouter/llama-3.3-70b
X-Asom-Egress: cloud
X-Asom-Cost-Est: 0.0000032
X-Asom-Cost-Basis: usage

$ # trainy-ai (cheapest but trainsOnData:true) was excluded by X-Asom-No-Train ✓

$ curl -sN http://127.0.0.1:11435/v1/chat/completions -H "Authorization: Bearer asom-dev-token" \
    -d '{"model":"cheapest","stream":true,"messages":[{"role":"user","content":"stream!"}]}'
data: {"id":"chatcmpl-fake-trainy-ai","object":"chat.completion.chunk","created":1783457758,"model":"llama-3.3-70b","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}],"usage":null}

data: {"id":"chatcmpl-fake-trainy-ai","object":"chat.completion.chunk","created":1783457758,"model":"llama-3.3-70b","choices":[{"index":0,"delta":{"role":null,"content":"fake:trainy-ai/lla"},"finish_reason":null}],"usage":null}

data: {"id":"chatcmpl-fake-trainy-ai","object":"chat.completion.chunk","created":1783457758,"model":"llama-3.3-70b","choices":[{"index":0,"delta":{"role":null,"content":"ma-3.3-70b:stream!"},"finish_reason":null}],"usage":null}

data: {"id":"chatcmpl-fake-trainy-ai","object":"chat.completion.chunk","created":1783457758,"model":"llama-3.3-70b","choices":[{"index":0,"delta":{"role":null,"content":null},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":9,"total_tokens":10}}

data: [DONE]


$ curl -s http://127.0.0.1:11435/v1/chat/completions -H "Authorization: Bearer asom-dev-token" \
    -d '{"model":"local-only","messages":[{"role":"user","content":"x"}]}'   # v1: typed 501, fails loudly
{"error":{"message":"no local engine in v1; 'local-only' cannot be served","type":"server_error","param":null,"code":"LOCAL_ENGINE_ABSENT"}}

$ curl -s http://127.0.0.1:11435/v1/models -H "Authorization: Bearer asom-dev-token"
{"object":"list","data":[{"id":"claude-sonnet-4-5","object":"model","created":0,"owned_by":"anthropic"},{"id":"deepseek-v3","object":"model","created":0,"owned_by":"openrouter"},{"id":"llama-3.3-70b","object":"model","created":0,"owned_by":"groq,openrouter,trainy-ai,webchat-only"},{"id":"auto","object":"model","created":0,"owned_by":"asom-virtual"},{"id":"best-reasoning","object":"model","created":0,"owned_by":"asom-virtual"},{"id":"cheapest","object":"model","created":0,"owned_by":"asom-virtual"},{"id":"fastest","object":"model","created":0,"owned_by":"asom-virtual"},{"id":"local-only","object":"model","created":0,"owned_by":"asom-virtual"}]}

$ curl -s http://127.0.0.1:11435/v1/completions -H "Authorization: Bearer asom-dev-token" \
    -d '{"model":"cheapest","prompt":"legacy shim"}'
{"id":"chatcmpl-fake-trainy-ai","object":"text_completion","created":1783457758,"model":"llama-3.3-70b","choices":[{"index":0,"text":"fake:trainy-ai/llama-3.3-70b:legacy shim","finish_reason":"stop"}],"usage":{"prompt_tokens":2,"completion_tokens":10,"total_tokens":12}}

$ curl -s http://127.0.0.1:11435/v1/embeddings -H "Authorization: Bearer asom-dev-token" \
    -d '{"model":"llama-3.3-70b","input":"embed me"}'
{"object":"list","data":[{"object":"embedding","index":0,"embedding":[0.1,0.2,0.3]}],"model":"llama-3.3-70b","usage":{"prompt_tokens":2,"completion_tokens":0,"total_tokens":2}}

$ curl -s http://127.0.0.1:11435/admin/catalogue -H "Authorization: Bearer asom-dev-token" | head -c 700
{"catalogue":{"version":1,"updatedAt":"2026-07-04T00:00:00Z","providers":[{"id":"openrouter","displayName":"OpenRouter","kind":"openai-compat","baseUrl":"https://openrouter.ai/api/v1","auth":{"type":"bearer"},"trainsOnData":false,"programmaticAllowed":true,"rate":{"rpm":60,"rpd":10000},"pricing":{"llama-3.3-70b":{"inPerMTok":0.1,"outPerMTok":0.3},"deepseek-v3":{"inPerMTok":0.25,"outPerMTok":0.85}},"models":["llama-3.3-70b","deepseek-v3"]},{"id":"groq","displayName":"Groq","kind":"openai-compat","baseUrl":"https://api.groq.com/openai/v1","auth":{"type":"bearer"},"trainsOnData":false,"programmaticAllowed":true,"rate":{"rpm":30,"rpd":14400},"pricing":{"llama-3.3-70b":{"inPerMTok":0.59,"outPerMT
…(truncated)
```
