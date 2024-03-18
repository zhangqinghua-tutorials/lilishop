FROM java:openjdk-8u111-jre

# 2.接收从外边传来的参数
ARG         MODULE

# 将上一个容器的jar文件复制到此容器下面
ADD ${MODULE}/target/${MODULE}.jar /app/app.jar

# 调整时区
# RUN rm -f /etc/localtime && ln -sv /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && echo "Asia/Shanghai" > /etc/timezone

EXPOSE      80

# ENTRYPOINT ["java", "-Xmx256m", "-jar", "app/app.jar"]
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=90.0", "-Xmx256m", "-jar", "app/app.jar", "--server.port=80"]