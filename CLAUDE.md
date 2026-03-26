# Fortress Minecraft Server — Project Documentation

## Overview

This is an enterprise-grade Minecraft infrastructure project ("Fortress Minecraft Server") running on OCI (Oracle Cloud Infrastructure). It demonstrates infrastructure engineering skills across Kubernetes, networking, observability, GitOps, and custom AI-powered plugin development.

**Owner**: darkvoid15 (GRANTUR on GitHub) — bank teller at JPMC transitioning to infrastructure engineer.

## Infrastructure

### Server
- **Host**: OCI ARM instance (4-core Ampere Neoverse-N1, 22GB RAM)
- **Hostname**: high-palace
- **OS**: Oracle Linux 9 (aarch64)
- **K8s**: k3s single-node cluster
- **Game**: Paper Minecraft 1.21.11 running in `minecraft` namespace
- **Java**: OpenJDK 21.0.10 (switched from 17 for Paper API compatibility)
- **Domain**: gigilab.duckdns.org

### Network Topology
```
Internet → OCI Instance (10.0.0.64)
  ├── k3s cluster
  │   ├── minecraft namespace
  │   │   ├── minecraft pod (game:25565, mc-monitor:8080, plugin-metrics:9225)
  │   │   ├── ollama-relay service → host socat → Tailscale → PC
  │   │   └── network policies (zero-trust)
  │   ├── kube-system namespace
  │   │   ├── Traefik ingress (80/443, auto-TLS via Let's Encrypt)
  │   │   └── ArgoCD
  │   └── argocd namespace
  ├── Docker (monitoring)
  │   ├── Prometheus (:9090, path /prometheus/)
  │   ├── Grafana (:3000, path /grafana/)
  │   └── Node Exporter (:9100)
  └── Tailscale VPN (100.92.211.3) → PC (100.93.245.19)
```

### Tailscale Mesh
- **Server**: 100.92.211.3 (high-palace)
- **PC**: 100.93.245.19 (terminal) — Windows 11, RTX 5070 Ti, Ollama
- Pod traffic → K8s Service `ollama-relay` → socat systemd service on host → Tailscale → PC

## Phases Completed

| Phase | Feature | Key Files |
|-------|---------|-----------|
| 1 | Observability (Prometheus + Grafana) | `/home/opc/monitoring/docker-compose.yml`, prometheus.yml |
| 2 | Zero-trust NetworkPolicies | `network-policies.yml` |
| 3 | Traefik ingress + auto-TLS | `traefik-config.yml`, `traefik-routes.yml` |
| 4 | Automated CronJob backups | `backups.yml` |
| 5 | RBAC, Secrets, ResourceQuotas | `rbac-secrets.yml` |
| 6 | GitOps with ArgoCD | `argocd-ingress.yml` |
| 7 | AI NPC plugin + LLM observability | `dynasty-npc/`, `dynastynpc-metrics.yml` |

## File Locations

### Kubernetes Manifests (working copies)
`/home/opc/k8s-minecraft/`
- `minecraft.yml` — Deployment, PVC, Services (NodePort 30565 game, 30225 metrics)
- `network-policies.yml` — 7 policies incl. allow-ollama-out
- `backups.yml` — CronJob every 6h, 14 retention
- `rbac-secrets.yml` — ServiceAccounts, Roles, ResourceQuota, LimitRange, Secret
- `traefik-config.yml` — HelmChartConfig (deployed to `/var/lib/rancher/k3s/server/manifests/`)
- `traefik-routes.yml` — IngressRoutes for Grafana + Prometheus
- `argocd-ingress.yml` — ArgoCD routing via manual Endpoints
- `dynastynpc-metrics.yml` — NodePort 30226 for plugin metrics

### Git Repos
- **fortress-minecraft**: `https://github.com/GRANTUR/fortress-minecraft` — K8s manifests (secrets stripped)
  - Local: `/home/opc/fortress-minecraft/`
- **dynasty-npc**: `https://github.com/GRANTUR/dynasty-npc` — AI NPC plugin source
  - Local: `/home/opc/dynasty-npc/`

### Plugin Source (`/home/opc/dynasty-npc/`)
- `pom.xml` — Maven, Java 21, Paper API 1.21.11-R0.1-SNAPSHOT
- `src/main/java/com/highpalace/dynastynpc/`
  - `DynastyNPC.java` — Main plugin class, wires all components
  - `OllamaClient.java` — Async HTTP to Ollama, metrics instrumentation
  - `NPCManager.java` — NPC data, villager spawning, YAML persistence
  - `MemoryManager.java` — 3-tier persistent memory (short-term, disk, LLM summary)
  - `MetricsCollector.java` — Thread-safe Prometheus metrics (counters, histograms)
  - `MetricsServer.java` — Embedded JDK HttpServer on port 9225
  - `ChatListener.java` — AsyncChatEvent handler with memory + metrics
  - `CommandHandler.java` — /dynastynpc and /ask commands
  - `NPCInteractListener.java` — Prevent villager trade/damage on NPCs
- `src/main/resources/config.yml` — Ollama host, model, memory settings, metrics port
- `src/main/resources/plugin.yml` — Plugin descriptor

### Monitoring
- `/home/opc/monitoring/docker-compose.yml` — Prometheus + Grafana + Node Exporter
- `/home/opc/monitoring/prometheus.yml` — Scrape configs (minecraft, dynastynpc, node)

### Systemd Services
- `ollama-relay.service` — socat TCP:11434 relay to Tailscale peer for Ollama
- `dynastynpc-metrics-relay.service` — socat TCP:9226 relay for Prometheus→plugin metrics

## Key Configuration

### Ollama
- **Model**: `llama2:13b-chat-q4_K_M` (good for roleplay, no <think> tags)
- **Available models**: deepseek-r1 (8B, has think-tag issue), qwen2.5-coder:14b, qwen3-coder:30b, llama2:13b
- **Relay chain**: Pod → K8s Service `ollama-relay` (ClusterIP) → socat on host (port 11434) → Tailscale → PC Ollama

### Grafana Dashboards
- **Minecraft Server** — mc-monitor metrics (players, TPS, memory)
- **AI Operations - DynastyNPC** — LLM metrics (request rate, P50/P95/P99 latency, tokens/min, conversations by NPC, errors)
- Datasource UID: `cfh4mi27np9mof`
- Grafana password was reset to `admin123`

### NPCs
- **Scholar Wu** (§6 gold) — Librarian villager, wise Song Dynasty advisor
- **Merchant Chen** (§a green) — Cartographer villager, marketplace trader
- **General Zhao** (§c red) — Weaponsmith villager, palace guard commander

### Secrets (NOT in Git)
- RCON password: stored in `rbac-secrets.yml` on server only (not in git)
- Prometheus basic auth: in `traefik-routes.yml` on server only (stripped from git)
- **Never commit secrets to CLAUDE.md or any tracked file**

## Build & Deploy

```bash
# Build plugin
cd /home/opc/dynasty-npc
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-21.0.10.0.7-1.0.1.el9.aarch64
mvn clean package

# Deploy to server
POD=$(kubectl get pods -n minecraft -l app=minecraft -o jsonpath='{.items[0].metadata.name}')
kubectl cp target/dynasty-npc-1.0.0.jar minecraft/$POD:/data/plugins/DynastyNPC.jar -c minecraft
kubectl rollout restart deployment/minecraft -n minecraft
```

## Known Issues & Workarounds
- **Pod→Tailscale routing**: Pods can't reach Tailscale IPs directly. Solved with socat relay + K8s Service/Endpoints pattern.
- **Docker→NodePort**: Some NodePorts aren't reachable from Docker bridge (172.17.0.1). Solved with socat relay binding to Docker bridge IP.
- **DeepSeek-R1 empty responses**: Model wraps all output in `<think>` tags. Switched to llama2:13b.
- **Traefik cross-namespace routing**: Doesn't work with CRD IngressRoutes. Solved with manual Service+Endpoints in `kube-system`.
- **Paper API requires Java 21**: Paper 1.21.11 targets class version 65.0 (Java 21).
- **ResourceQuota too tight for rolling updates**: Increased to 10 CPU / 20Gi memory limits.
