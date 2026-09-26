# ---- Build stage ----
FROM node:20-alpine AS build
WORKDIR /app

COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend/ .
# The API base URL is deliberately NOT baked in here. Next inlines NEXT_PUBLIC_*
# at build time, so a build-time value would make this image permanently bound to
# one backend. It is served at runtime from /config.js (src/app/config.js/route.ts)
# and read from QALAB_API_BASE_URL / API_BASE_URL, so a single image can be
# deployed to any environment. The value below is only a last-resort fallback for
# `next dev` without any configuration.
ARG NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
ENV NEXT_PUBLIC_API_BASE_URL=$NEXT_PUBLIC_API_BASE_URL
RUN npm run build

# ---- Runtime stage ----
FROM node:20-alpine
WORKDIR /app

ENV NODE_ENV=production
# Read at request time by the /config.js route handler, not at build time.
# QALAB_API_BASE_URL=https://qa.example.com

COPY --from=build /app/.next ./.next
COPY --from=build /app/public ./public
COPY --from=build /app/package.json ./package.json
COPY --from=build /app/node_modules ./node_modules

EXPOSE 3000

CMD ["npm", "run", "start"]
