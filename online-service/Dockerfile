ARG EASYTIER_VERSION=2.6.4

FROM debian:bookworm-slim AS easytier

ARG EASYTIER_VERSION
ARG TARGETARCH

RUN apt-get update \
  && apt-get install -y --no-install-recommends ca-certificates curl unzip \
  && rm -rf /var/lib/apt/lists/*

RUN case "${TARGETARCH}" in \
      amd64) easytier_arch=x86_64; easytier_sha256=61b659eaedba658fa66fe47d17e1426cdd77e5d02fa15fed447bb4357c09dfd6 ;; \
      arm64) easytier_arch=aarch64; easytier_sha256=f533ec25a7ea714e09f645615012200278058525795cc3bb690ff011aec1a70f ;; \
      *) echo "Unsupported EasyTier target architecture: ${TARGETARCH}" >&2; exit 1 ;; \
    esac \
  && curl --fail --location --retry 3 --retry-all-errors \
      "https://github.com/EasyTier/EasyTier/releases/download/v${EASYTIER_VERSION}/easytier-linux-${easytier_arch}-v${EASYTIER_VERSION}.zip" \
      --output /tmp/easytier.zip \
  && echo "${easytier_sha256}  /tmp/easytier.zip" | sha256sum --check --status \
  && unzip -q /tmp/easytier.zip -d /tmp/easytier \
  && install -D -m 0755 "$(find /tmp/easytier -type f -name easytier-core -print -quit)" /opt/easytier/easytier-core \
  && install -D -m 0755 "$(find /tmp/easytier -type f -name easytier-web-embed -print -quit)" /opt/easytier/easytier-web-embed

FROM node:20-bookworm-slim AS deps

WORKDIR /app

RUN apt-get update \
  && apt-get install -y --no-install-recommends python3 make g++ ca-certificates \
  && rm -rf /var/lib/apt/lists/*

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

FROM node:20-bookworm-slim AS runtime

WORKDIR /app

ENV NODE_ENV=production \
  HOST=0.0.0.0 \
  PORT=3001 \
  PRESENCE_DB_PATH=/app/data/presence.sqlite \
  EASYTIER_WEB_EMBED_BINARY_PATH=/opt/easytier/easytier-web-embed \
  EASYTIER_CORE_BINARY_PATH=/opt/easytier/easytier-core

COPY --from=deps /app/node_modules ./node_modules
COPY package.json package-lock.json ./
COPY src ./src
COPY --from=easytier /opt/easytier /opt/easytier

RUN mkdir -p /app/data \
  && chown -R node:node /app /opt/easytier

USER node

EXPOSE 3001/tcp
EXPOSE 22020/udp
EXPOSE 11010/tcp
EXPOSE 11010/udp

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:' + (process.env.PORT || '3001') + '/healthz').then(r => process.exit(r.ok ? 0 : 1)).catch(() => process.exit(1))"

CMD ["npm", "start"]
