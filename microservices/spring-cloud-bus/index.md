# Spring Cloud Bus

| File | Description |
|------|-------------|
| [notes.md](notes.md) | Concept notes — What / Why / How / Code Example / Interview Angles |
| [pom.xml](pom.xml) | Parent POM — Spring Boot 3.2.5, Spring Cloud 2023.0.1, three child modules |
| [config-repo/](config-repo/) | Config property files served by Config Server (order-service.yml, inventory-service.yml) |
| [config-server/](config-server/) | Spring Cloud Config Server + Bus AMQP (port 8888) — serves config files from config-repo/, relays busrefresh events |
