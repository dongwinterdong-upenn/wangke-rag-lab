# syntax=docker/dockerfile:1
# 平台后端：Java 17 + Spring Boot。构建上下文是仓库根目录。
FROM maven:3.9-eclipse-temurin-17 AS build
# Maven 中央库在国内很慢，默认走阿里云镜像。在国外构建可以传 --build-arg MAVEN_MIRROR=https://repo.maven.apache.org/maven2
ARG MAVEN_MIRROR=https://maven.aliyun.com/repository/public
WORKDIR /src
RUN mkdir -p /root/.m2 && printf '<settings><mirrors><mirror><id>m</id><mirrorOf>central</mirrorOf><url>%s</url></mirror></mirrors></settings>' "$MAVEN_MIRROR" > /root/.m2/settings.xml
COPY pom.xml .
COPY src ./src
# 依赖缓存在构建缓存里，改了代码重新构建不用重新下载。
RUN --mount=type=cache,target=/root/.m2/repository mvn -B -q package -DskipTests

FROM eclipse-temurin:17-jre
ENV TZ=Asia/Shanghai
WORKDIR /app
COPY --from=build /src/target/wangke-0.1.0.jar /app/wangke.jar
EXPOSE 8801
ENTRYPOINT ["java", "-Xms128m", "-Xmx768m", "-Duser.timezone=Asia/Shanghai", "-jar", "/app/wangke.jar"]
