.PHONY: up down up-local down-local

up:
	docker compose -f compose.yml up -d

up-local:
	docker compose -f compose.local.yml up -d

down:
	docker compose -f compose.yml down -v

down-local:
	docker compose -f compose.local.yml down -v

down-redis:
	docker container stop redis-cluster; \
    docker container rm -f -v redis-cluster

down-postgres:
	docker container stop postgres-db; \
    docker container rm -f -v postgres-db

down-app:
	docker container stop spring-boot-app; \
    docker container rm -f -v spring-boot-app

