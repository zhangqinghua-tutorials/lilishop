# 1. 加载Easybyte通用库和运行环境（lib有70MB Java应用环境100MB）
# FROM        airdock/oraclejdk:1.8
# FROM openjdk:20-slim-buster
# FROM java:openjdk-8-jre
# FROM openjdk:8-jre-nanoserver
FROM adoptopenjdk/openjdk8

# 2.接收从外边传来的参数 easybyte-auth/auth-starter
ARG         MODULE
ARG         APP_NAME

# 3. 把应用包复制进容器（排除了lib的应用体积大概在1MB左右）复制本地的renren-fast.jar文件到容器/目录并改名app.jar easybyte-auth/target/easybyte-auth.jar -> /app.jar
ADD         ${MODULE}/target/${APP_NAME}.jar /app.jar

# 4. 对外开放的端口，8070给Txlcn用，8987是consumer的websocket端口
EXPOSE      80
EXPOSE      8987

# 5. 启动应用，指定外部通用库目录 在应用的启动参数
ENTRYPOINT  ["java", "-jar", "/app.jar", "--server.port=80"]