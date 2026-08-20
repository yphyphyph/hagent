# syntax=docker/dockerfile:1
# ============================================================
# HAgent 后端 - 多阶段 Docker 构建（BuildKit 优化版）
# ============================================================

# 阶段1：构建
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# 阿里云 Maven 镜像加速
RUN mkdir -p /root/.m2 && \
    printf '<?xml version="1.0" encoding="UTF-8"?>\n' > /root/.m2/settings.xml && \
    printf '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">\n' >> /root/.m2/settings.xml && \
    printf '  <mirrors><mirror><id>aliyun</id><mirrorOf>central</mirrorOf><url>https://maven.aliyun.com/repository/public</url></mirror></mirrors>\n' >> /root/.m2/settings.xml && \
    printf '</settings>\n' >> /root/.m2/settings.xml

# 复制 POM 文件（利用 Docker 层级缓存）
COPY pom.xml .
COPY hagent-common/pom.xml ./hagent-common/
COPY hagent-ai/pom.xml ./hagent-ai/
COPY hagent-module-knowledge/pom.xml ./hagent-module-knowledge/
COPY hagent-server/pom.xml ./hagent-server/

# 下载依赖（使用 BuildKit 缓存挂载，避免重复下载）
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn dependency:go-offline -B

# 复制源代码
COPY hagent-common/src ./hagent-common/src
COPY hagent-ai/src ./hagent-ai/src
COPY hagent-module-knowledge/src ./hagent-module-knowledge/src
COPY hagent-server/src ./hagent-server/src

# 打包（使用 BuildKit 缓存挂载，加速编译）
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn package -DskipTests -B

# 阶段2：运行
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 安装 curl（健康检查用）和时区
RUN apk add --no-cache curl tzdata \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone

# 创建非 root 用户
RUN addgroup -S hagent && adduser -S hagent -G hagent

# 复制 jar
COPY --from=builder /build/hagent-server/target/hagent-server-*.jar app.jar

# 设置权限
RUN chown -R hagent:hagent /app

USER hagent

# 暴露端口（默认 dev 为 8099，生产可通过 --server.port 覆盖）
EXPOSE 8099

# JVM 调优
ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError"

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --retries=5 --start-period=120s \
    CMD curl -f http://localhost:8099/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]