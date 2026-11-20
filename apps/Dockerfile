# The jar is built outside (mvn package) so CI can cache Maven; the image only adds it.
FROM gcr.io/distroless/java21-debian12
COPY target/app.jar /app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
