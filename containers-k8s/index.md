# Docker & Kubernetes Deep Dive — SDE2 Backend + Prod-Ops Prep

Deep-dive notes on containers and orchestration, built for SDE2/FAANG system-design rounds **and**
the day-to-day reality of running Spring Boot microservices on Kubernetes (the Maersk stack:
`isce-*` services, rolling deploys, HPA, probes, resource limits, debugging pods at 2am).

The bar in an interview isn't "I've written a `Dockerfile` and run `kubectl apply`." It's:
*what actually isolates a container* (there is no VM), *what you lose and gain vs a VM*,
*how the scheduler decides where a pod lands*, *why your JVM pod gets `OOMKilled` when the node has
free RAM*, and *how a rolling update stays zero-downtime*. This track is written to answer those cold.

Each note follows the same five-section structure as the [Redis](../redis/index.md) and
[Postgres](../postgres/index.md) tracks: **What → Why → How (internals) → Code/Example → Interview Angles**,
plus **One-Line Recall Cards** at the end.

## Why these topics (the SDE2 + prod-ops angle)

Four themes run through everything an interviewer — or an incident — will push on:

1. **There is no VM.** A container is just a normal Linux process with a restricted *view* of the
   system (namespaces) and a *budget* of resources (cgroups). Everything else — "lightweight," "fast
   startup," "shared kernel," the security caveats — falls out of that one fact. (Topics 1–3)
2. **Declarative desired state + reconciliation.** Kubernetes is not a script runner; it's a set of
   controllers that continuously drive *actual state → desired state*. Once you internalize the reconcile
   loop, Deployments, self-healing, rollbacks, and autoscaling all become the same idea. (Topics 7–9)
3. **Resources & scheduling are where prod breaks.** Requests vs limits, QoS classes, OOMKills,
   evictions, CPU throttling, and the JVM-in-a-cgroup trap are the difference between "works on my
   laptop" and "survives a node under memory pressure." (Topics 6, 10, 14, 15)
4. **The network is four separate problems.** Container↔container, pod↔pod, pod↔service, and
   external↔service are solved by *different* mechanisms (CNI, Services, kube-proxy, Ingress). Interviewers
   love to see if you can separate them. (Topics 4, 11)

## Curriculum

### Part A — Containers / Docker (the foundation)

| # | Topic | File | Status |
|---|-------|------|--------|
| 1 | What a container *really* is — namespaces (pid/net/mnt/uts/ipc/user), cgroups v2, capabilities, why it's not a VM | [01-what-is-a-container.md](01-what-is-a-container.md) | ✅ |
| 2 | Images & layers — OverlayFS union mounts, layer caching, Dockerfile build internals, multi-stage & distroless | [02-images-layers-overlayfs.md](02-images-layers-overlayfs.md) | ✅ |
| 3 | The runtime stack — Docker → containerd → runc, the OCI image/runtime spec, `docker run` end-to-end | [03-runtime-stack-oci.md](03-runtime-stack-oci.md) | ✅ |
| 4 | Container networking — bridge/host/none, veth pairs, NAT/iptables, port publishing, embedded DNS | [04-container-networking.md](04-container-networking.md) | ✅ |
| 5 | Storage — the writable layer (CoW), volumes vs bind mounts vs tmpfs, data persistence & lifecycle | [05-container-storage.md](05-container-storage.md) | ✅ |
| 6 | Production images for Spring Boot — JVM cgroup awareness (heap/CPU sensing), layered jars, buildpacks, image size & CVE hardening | [06-spring-boot-images-jvm.md](06-spring-boot-images-jvm.md) | ✅ |

### Part B — Kubernetes (orchestration)

| # | Topic | File | Status |
|---|-------|------|--------|
| 7 | Architecture — control plane (API server, etcd, scheduler, controller-manager) + node (kubelet, kube-proxy, runtime); the **reconciliation loop** | [07-architecture-reconcile-loop.md](07-architecture-reconcile-loop.md) | ✅ |
| 8 | Pods & the object model — pod lifecycle, init vs sidecar containers, pause container, declarative desired-state, labels/selectors | [08-pods-object-model.md](08-pods-object-model.md) | ✅ |
| 9 | Controllers — ReplicaSet, Deployment (rolling update / rollback), StatefulSet, DaemonSet, Job/CronJob | [09-controllers-deployments.md](09-controllers-deployments.md) | ✅ |
| 10 | Scheduling & resources — requests/limits, QoS classes, node/pod affinity, taints/tolerations, eviction & **OOMKills**, CPU throttling | [10-scheduling-resources-qos.md](10-scheduling-resources-qos.md) | ✅ |
| 11 | Networking — the 4 networking problems, Services (ClusterIP/NodePort/LoadBalancer), kube-proxy (iptables vs IPVS), CoreDNS, Ingress, the CNI | [11-k8s-networking-services-ingress.md](11-k8s-networking-services-ingress.md) | ✅ |
| 12 | Configuration & secrets — ConfigMaps, Secrets (base64 ≠ encryption, etcd encryption-at-rest), env vs mounted, projected volumes, external secrets | [12-configmaps-secrets.md](12-configmaps-secrets.md) | ✅ |
| 13 | Storage — PV / PVC / StorageClass, static vs dynamic provisioning, CSI drivers, StatefulSet volume claims, access modes | [13-k8s-storage-pv-pvc-csi.md](13-k8s-storage-pv-pvc-csi.md) | ✅ |
| 14 | Health & self-healing — liveness / readiness / startup probes, restart policy & backoff, PodDisruptionBudgets, wiring Spring Boot Actuator | [14-probes-self-healing.md](14-probes-self-healing.md) | ✅ |
| 15 | Autoscaling — HPA (metrics pipeline), VPA, Cluster Autoscaler, scaling Spring Boot services safely | [15-autoscaling-hpa-vpa-ca.md](15-autoscaling-hpa-vpa-ca.md) | ✅ |
| 16 | Observability & debugging prod pods — logs/events/`kubectl` triage, the **CrashLoopBackOff / ImagePullBackOff / OOMKilled** playbook | [16-observability-debugging.md](16-observability-debugging.md) | ✅ |
| 17 | Security & multi-tenancy — RBAC, ServiceAccounts, namespaces, NetworkPolicies, Pod Security Standards, resource quotas & LimitRanges | [17-security-rbac-networkpolicy.md](17-security-rbac-networkpolicy.md) | ✅ |
| 18 | Rollouts & production ops — rolling / blue-green / canary, Helm basics, GitOps, zero-downtime deploys for microservices | [18-rollouts-helm-gitops.md](18-rollouts-helm-gitops.md) | ✅ |

## The questions this track must let you answer cold

- **"What actually isolates a container — how is it different from a VM?"** → namespaces give an
  isolated *view* (pid/net/mnt/…), cgroups give a resource *budget*; it's one shared kernel, no
  hypervisor. That's why it starts in ms and why a kernel exploit is a bigger deal than for a VM. (Topic 1)
- **"Walk me from `docker run nginx` to a running process."** → CLI → daemon → containerd → runc →
  `clone()` with new namespaces + cgroup + rootfs from OverlayFS. (Topics 1–3)
- **"Why is your image 900MB and how do you get it to 90MB?"** → base image choice, multi-stage builds,
  layer ordering for cache hits, `.dockerignore`, distroless/jlink. (Topics 2, 6)
- **"Your Spring Boot pod gets OOMKilled but `top` shows the node has free memory. Why?"** → the *limit*
  is a cgroup ceiling on that container, not the node; JVM must sense the cgroup (`MaxRAMPercentage`),
  and RSS includes off-heap/metaspace/threads. (Topics 6, 10)
- **"How does a Deployment do a zero-downtime rolling update?"** → new ReplicaSet scaled up under
  `maxSurge`/`maxUnavailable`, readiness gates traffic, old RS scaled down; rollback = re-point to prior RS. (Topics 9, 14, 18)
- **"How does a request reach a pod — trace ClusterIP → pod."** → Service is a stable VIP; kube-proxy
  programs iptables/IPVS to DNAT to a pod IP from Endpoints; CoreDNS resolves the name; CNI makes pod IPs routable. (Topic 11)
- **"What decides which node a pod lands on?"** → scheduler filter (fit: requests, taints, affinity) +
  score phase; unschedulable if no node fits requests. (Topic 10)
- **"Liveness vs readiness — what breaks if you swap them?"** → readiness controls *traffic*, liveness
  controls *restarts*; a liveness probe that's really a readiness check causes restart loops under load. (Topic 14)
- **"Is a Kubernetes Secret encrypted?"** → base64 is encoding, not encryption; needs etcd
  encryption-at-rest + RBAC to be meaningful. (Topic 12)
- **"Pod is CrashLoopBackOff — how do you debug it?"** → `describe` (events/last state/exit code) →
  `logs --previous` → check probes/resources/config; distinguish app crash vs OOMKill vs bad image. (Topic 16)

## Primary sources (the depth these rounds expect)

- **Official Kubernetes docs** — *Concepts* section is genuinely good: pod lifecycle, controllers,
  services-networking, scheduling-eviction, and storage are primary, not blog hearsay.
- **Docker docs + OCI specs** — the [OCI Image Spec](https://github.com/opencontainers/image-spec) and
  [Runtime Spec](https://github.com/opencontainers/runtime-spec) define what an image and a container
  *are* at the byte/JSON level.
- **"Kubernetes in Action" — Marko Lukša** — the practical bible; deep on the object model and controllers.
- **Linux man pages** — `namespaces(7)`, `cgroups(7)`, `capabilities(7)`, `overlayfs`, `veth(4)`. This is
  where "a container is just a process" stops being a slogan.
- **containerd / runc source & docs** — for the runtime stack below the Docker CLI.
- **Brendan Burns et al. — "Designing Distributed Systems"** — the sidecar/ambassador/adapter patterns
  that show *why* pods group containers.
- **JVM ergonomics docs / `UseContainerSupport`** — the exact flags behind the OOMKill story (Topic 6).

→ **Start:** [01 — What a Container Really Is](01-what-is-a-container.md) (namespaces, cgroups, why it's not a VM)
→ **Capstone:** [18 — Rollouts & Production Ops](18-rollouts-helm-gitops.md) — ties the whole track back to
shipping the Spring Boot microservices from the [microservices track](../microservices/) safely.
