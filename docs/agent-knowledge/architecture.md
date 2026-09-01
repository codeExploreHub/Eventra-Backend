# Backend architecture

Eventra Backend targets Java 17 and Spring Boot 3 conventions. The Maven
project includes Spring Web, Security, Actuator, Data JPA, Validation, and test
support. The local backend service is expected on port 8080, with readiness at
`/actuator/health`.

Published API routes, payloads, status codes, and security behavior are frozen
unless an assigned task explicitly changes the contract. Cross-repository
contract changes must be coordinated with the frontend using exact candidate
SHAs.
