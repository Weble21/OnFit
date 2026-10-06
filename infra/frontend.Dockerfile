FROM node:24-alpine AS build
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
COPY backend/src/main/resources/skill-aliases.json /src/backend/src/main/resources/
COPY backend/src/main/resources/seed/job-postings.json /src/backend/src/main/resources/seed/
RUN npm test && npm run check

FROM node:24-alpine
WORKDIR /app
COPY --from=build /src/frontend/dist ./dist
COPY frontend/server.mjs ./server.mjs
USER node
ENV HOST=0.0.0.0 PORT=5173
EXPOSE 5173
CMD ["node", "server.mjs"]
