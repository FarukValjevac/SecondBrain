# SecondBrain

A planned personal knowledge library built in Java. Upload notes and PDFs, search by meaning, and ask questions with inspectable sources.

## Current state

This repository contains planning, coding guidance, and a reusable evaluation skill. Application code, the Maven build, database migrations, and runtime configuration have not been created yet.

## Documentation

- [Implementation plan](IMPLEMENTATION_PLAN.md): scope, milestones, and acceptance criteria.
- [Coding guidance](AGENTS.md): conventions for Codex and contributors.
- [Architecture](docs/architecture.md): proposed components and invariants.
- [Progress](docs/progress.md): current status and next task.
- [RAG evaluation skill](.agents/skills/evaluate-rag/SKILL.md): repeatable retrieval and answer evaluation.

## Planned stack

Java 25 LTS; Maven Wrapper; Spring Boot 4.1.x; Spring AI 2.0.x; PostgreSQL with pgvector; Flyway; Thymeleaf; Docker Compose; JUnit and Testcontainers.

Verify current compatibility and pin stable patch versions when creating the build.

## First development slice

Create the application and database, upload a Markdown document, and display the extracted text. Follow the implementation plan incrementally.

Prerequisites for that stage are a Java 25 JDK and a Docker-compatible container runtime. Runtime commands will be documented when implemented.

## Open this project with Codex

In the desktop app, add this directory as a local project folder, make it primary, and start a new chat there. In Codex CLI:

```sh
codex --cd /Users/davidoff/Documents/Workplace/SecondBrain
```

Start with: “Read AGENTS.md and docs/progress.md, then implement milestone 1 from IMPLEMENTATION_PLAN.md.”

Project skills live under .agents/skills. Invoke the evaluation workflow with $evaluate-rag when retrieval is implemented.

## Data and credentials

The first release is intended for one local user. Hosted AI providers receive text for embedding and answer generation. Keep real documents, generated indexes, and API keys out of version control. Authentication is required before expanding access beyond the local-user scope.

## References

- [Codex project instructions](https://learn.chatgpt.com/docs/agent-configuration/agents-md)
- [Codex skills](https://learn.chatgpt.com/docs/build-skills)
- [Local project folders](https://learn.chatgpt.com/docs/projects)
- [Spring AI compatibility](https://docs.spring.io/spring-ai/reference/getting-started.html)
# SecondBrain
