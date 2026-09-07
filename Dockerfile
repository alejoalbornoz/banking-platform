# One Dockerfile for all five services, selected by build arg.
#
# The build stage is deliberately identical for every service - it builds the
# whole reactor - and only the runtime stage differs. That ordering is what
# makes `docker compose build` cheap: because the expensive stage's
# instructions and context are the same for all five images, Docker builds it
# once and the other four hit the layer cache, instead of running Maven five
# times over the same source. Every module sets <finalName> to its own name,
# so the jar path is predictable from the module alone.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY . .
# Tests run in CI, not here. The integration tests would need a Docker daemon
# of their own anyway, which an image build has no business requiring.
RUN mvn -B --no-transfer-progress package -DskipTests

FROM eclipse-temurin:21-jre-alpine
ARG MODULE
WORKDIR /app

# Nothing in these services needs root, and a container that never needs it
# shouldn't run as it.
RUN addgroup -S banking && adduser -S banking -G banking

# ARG can't be referenced from ENTRYPOINT (it's a build-time value and the
# exec form doesn't expand shell variables), so the jar gets a fixed name here
# and the module only matters at COPY time.
COPY --from=build /build/${MODULE}/target/${MODULE}.jar app.jar

USER banking
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
