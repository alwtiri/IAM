# syntax=docker/dockerfile:1.7
# iam-web image (ADR-0009). Build context: web/
FROM node:22-alpine@sha256:b6f26b36c8ff49624cfdac716b8ea1138d606df02586a77d364bb5536a634f85 AS build
WORKDIR /app
RUN corepack enable
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
RUN pnpm test && pnpm build

FROM nginxinc/nginx-unprivileged:1.27-alpine@sha256:65e3e85dbaed8ba248841d9d58a899b6197106c23cb0ff1a132b7bfe0547e4c0 AS runtime
LABEL org.opencontainers.image.title="iam-web"
# The pinned base (alpine 3.21.3) predates many Alpine security fixes: apply them at build time
# (Trivy: 35 HIGH / 2 CRITICAL fixed in newer packages). Keep until the digest is bumped to a current base.
USER root
RUN apk upgrade --no-cache
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
USER 101
EXPOSE 8080
