FROM eclipse-temurin:11-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --uid 10001 --create-home zyrex \
    && mkdir -p /var/lib/zyrex /opt/zyrex \
    && chown zyrex:zyrex /var/lib/zyrex
COPY target/scala-2.12/zyrex.jar /opt/zyrex/zyrex.jar
USER zyrex
WORKDIR /var/lib/zyrex
VOLUME ["/var/lib/zyrex"]
EXPOSE 19530 19531 19532 19553 19554 19555
ENTRYPOINT ["java", "-jar", "/opt/zyrex/zyrex.jar"]
