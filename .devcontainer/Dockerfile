FROM alpine:3.24

RUN apk add --no-cache bash ca-certificates curl git tar gzip findutils procps-ng coreutils python3 fontconfig ttf-dejavu libgcc libstdc++ tzdata zlib binutils docker-cli docker-cli-compose

COPY --from=eclipse-temurin:27-jdk-alpine /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
RUN java --version && javac --version

COPY --from=maven:3-eclipse-temurin-25 /usr/share/maven /usr/share/maven
ENV MAVEN_HOME=/usr/share/maven
ENV PATH="${MAVEN_HOME}/bin:${PATH}"
RUN mvn --version

RUN git config --system --add safe.directory '/workspaces/*'

WORKDIR /workspaces
CMD ["sleep", "infinity"]
