FROM node:20-alpine AS build

# Declared so the shared CI build-arg doesn't warn; the version is surfaced by
# the backend's /api/v1/status endpoint, not the frontend image.
ARG APP_VERSION=dev

WORKDIR /app

# Copy package files
COPY package.json package-lock.json ./

# Install dependencies (legacy-peer-deps for @toast-ui/react-editor React 17 peer dep)
RUN npm ci --legacy-peer-deps

# Copy source code
COPY . .

# Build the application
RUN npm run build

FROM nginx:alpine

WORKDIR /usr/share/nginx/html

# Install wget for health checks
RUN apk add --no-cache wget

# Copy built files to nginx
COPY --from=build /app/dist /usr/share/nginx/html

# Copy nginx configuration
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

# Health check
HEALTHCHECK --interval=5s --timeout=3s --start-period=10s --retries=5 \
  CMD wget --quiet --tries=1 --spider http://localhost:80 || exit 1

CMD ["nginx", "-g", "daemon off;"]
