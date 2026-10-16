# Starts the backend with the `demo` profile against the WSL Postgres (5433) + Redis (6379)
# set up by scripts/wsl-infra-setup.sh. The demo profile seeds two users, a repo and five PRs,
# and needs no GitHub OAuth app or LLM API key.
$ErrorActionPreference = 'Stop'
$env:SPRING_PROFILES_ACTIVE = 'demo'
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://127.0.0.1:5433/codelensai'
$env:SPRING_REDIS_HOST = '127.0.0.1'
# Infra runs in WSL, not Docker: skip spring-boot-docker-compose's 'docker compose up'.
$env:SPRING_DOCKER_COMPOSE_ENABLED = 'false'
Set-Location (Join-Path $PSScriptRoot '..\codelensai')
& .\mvnw.cmd -B -o spring-boot:run
