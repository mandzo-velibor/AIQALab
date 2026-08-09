# ---- Build stage ----
FROM node:20-alpine AS build
WORKDIR /app

COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend/ .
ARG NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
ENV NEXT_PUBLIC_API_BASE_URL=$NEXT_PUBLIC_API_BASE_URL
RUN npm run build

# ---- Runtime stage ----
FROM node:20-alpine
WORKDIR /app

ENV NODE_ENV=production

COPY --from=build /app/.next ./.next
COPY --from=build /app/public ./public
COPY --from=build /app/package.json ./package.json
COPY --from=build /app/node_modules ./node_modules

EXPOSE 3000

CMD ["npm", "run", "start"]
