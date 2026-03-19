FROM clojure:temurin-21-lein-2.11.2-alpine AS builder

WORKDIR /app

COPY project.clj .
RUN lein deps

COPY . .
RUN lein uberjar

FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S app && adduser -S app -G app

WORKDIR /app

COPY --from=builder /app/target/url-shortener-*-standalone.jar app.jar

RUN chown -R app:app /app

USER app

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -qO- http://localhost:8080/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
