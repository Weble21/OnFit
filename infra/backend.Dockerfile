FROM eclipse-temurin:21-jdk-jammy AS verify
RUN apt-get update -qq && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-kor fonts-nanum fonts-dejavu-core fontconfig \
    && rm -rf /var/lib/apt/lists/*
ENV ONFIT_REQUIRE_OCR=true
WORKDIR /src/backend
COPY backend/ ./
RUN --mount=type=cache,target=/root/.gradle bash gradlew --no-daemon test recommendationEvaluation bootJar --console=plain

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN apt-get update -qq && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-kor curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 onfit && useradd --uid 10001 --gid onfit --no-create-home onfit
WORKDIR /app
RUN mkdir logs && chown -R onfit:onfit /app
COPY --from=verify /src/backend/build/libs/*-SNAPSHOT.jar /app/onfit.jar
USER onfit
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl --fail --silent http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/onfit.jar"]
