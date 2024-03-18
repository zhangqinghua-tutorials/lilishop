FROM zhangqinghua/lilishop-manager:latest

# 调整时区
RUN rm -f /etc/localtime && ln -sv /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && echo "Asia/Shanghai" > /etc/timezone

EXPOSE      ${PORT}

# 指定docker容器启动时运行jar包
# -XX:+UseContainerSupport -XX:MaxRAMPercentage=90.0 探测容器内存大小，使用内存不能超过容器的90%
# -Djava.security.egd=file:/dev/./urandom 修复容器Bug
# ${JAVA_OPTS} Java的一些运行参数
# -Xmx50m                       最大可使用堆内存
# -XX:MaxMetaspaceSize          最大可使用元空间
# -XX:ReservedCodeCacheSize=10m CodeHeap大小
# ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=90.0", "-Djava.security.egd=file:/dev/./urandom", "-Xmx256m", "-jar", "app/app.jar", "--server.port=${PORT}", "--spring.profiles.active=product"]
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-Xmx512m", "-jar", "app/app.jar", "--server.port=${PORT}"]
