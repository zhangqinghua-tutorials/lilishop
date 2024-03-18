FROM java:openjdk-8u111-jre

# 将上一个容器的jar文件复制到此容器下面
ADD manager-api/target/manager-api-4.3.jar /app/app.jar

# 调整时区
RUN rm -f /etc/localtime && ln -sv /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && echo "Asia/Shanghai" > /etc/timezone

EXPOSE      80

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-Xmx256m", "-jar", "app/app.jar", "--server.port=80"]
