FROM --platform=$BUILDPLATFORM node:22-alpine AS builder

WORKDIR /app
ARG VITE_API_BASE_URL=/api
ARG VITE_UPLOAD_MAX_BYTES=10485760
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
ENV VITE_UPLOAD_MAX_BYTES=$VITE_UPLOAD_MAX_BYTES
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
ENV NGINX_ENVSUBST_OUTPUT_DIR=/etc/nginx \
    NGINX_ENVSUBST_FILTER=^WEB_
COPY nginx/nginx.conf.template /etc/nginx/templates/nginx.conf.template
COPY nginx/05-validate-proxy-env.envsh /docker-entrypoint.d/05-validate-proxy-env.envsh
RUN chmod 0755 /docker-entrypoint.d/05-validate-proxy-env.envsh
COPY --from=builder /app/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1/healthz || exit 1
