FROM openjdk:11-jre-slim

RUN mkdir /app
COPY . /app

WORKDIR /app
CMD ["java", "-cp", "jrodns.jar:libs/*", "cn.foperate.ros.IPset"]