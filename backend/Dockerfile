FROM node:22-alpine AS docs
WORKDIR /workspace/docs
COPY docs/package.json docs/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY docs/ ./
COPY backend/api/openapi.yaml /workspace/backend/api/openapi.yaml
COPY backend/README.md /workspace/backend/README.md
COPY client/roadmap.md /workspace/client/roadmap.md
RUN npm run build

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace/backend

COPY backend/gradlew backend/settings.gradle.kts backend/build.gradle.kts backend/gradle.properties ./
COPY backend/gradle/ gradle/
COPY backend/app/build.gradle.kts app/build.gradle.kts
COPY backend/app/src/ app/src/
COPY backend/shared/kernel/ shared/kernel/
COPY backend/modules/ modules/
COPY --from=docs /workspace/docs/site/.vitepress/dist /workspace/docs/site/.vitepress/dist
RUN ./gradlew :app:bootJar --no-daemon -PdocsPrebuilt=true

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/backend/app/build/libs/*.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
