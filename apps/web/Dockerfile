# syntax=docker/dockerfile:1.7
# Builds the web app and serves it with Caddy, which also proxies /api to the gateway (same origin, no CORS).
# Build from the repository root:  docker build -f apps/web/Dockerfile -t web .

FROM node:24-alpine AS build
WORKDIR /app
COPY apps/web/package.json apps/web/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY apps/web/ ./
RUN npm run build

FROM caddy:2.10-alpine
COPY infra/caddy/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /app/dist /srv
RUN addgroup -S -g 10001 web && adduser -S -D -H -u 10001 -G web web \
    && mkdir -p /data /config && chown -R 10001:10001 /data /config
USER 10001:10001
ENV GATEWAY_UPSTREAM=gateway:8080 SITE_ADDRESS=:8080
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1
