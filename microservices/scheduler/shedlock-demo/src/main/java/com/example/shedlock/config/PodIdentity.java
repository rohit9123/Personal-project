package com.example.shedlock.config;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Single stable identifier for this JVM process.
 * In Kubernetes this resolves to the pod hostname (e.g. "myapp-7d9f4b-xkp2n").
 * Locally it falls back to a short random UUID suffix.
 *
 * Inject this instead of calling UUID.randomUUID() in each job class so that
 * all jobs on the same pod report the same ID in logs and in the shedlock table.
 */
@Component
public class PodIdentity {

    private final String id = System.getenv().getOrDefault(
        "HOSTNAME", "pod-" + UUID.randomUUID().toString().substring(0, 6));

    public String getId() {
        return id;
    }
}
