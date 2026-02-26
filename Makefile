.PHONY: up down

up:
	docker compose -f docker-compose.yml up -d

down:
	docker compose -f docker-compose.yml down -v

down-redis:
	docker container stop leaderboard-redis-cluster; \
    docker container rm -f -v leaderboard-redis-cluster

down-postgres:
	docker container stop leaderboard-postgres; \
    docker container rm -f -v leaderboard-postgres