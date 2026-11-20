package lab;

import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import java.util.function.Supplier;

/** One image, two roles: ROLE=callee (CPU-bound server) or ROLE=caller (pooled client in front of it). */
public final class Main {
  public static void main(String[] args) {
    String role = env("ROLE", "callee");
    int port = Integer.parseInt(env("PORT", "8080"));
    // One verticle (event loop) per visible CPU; in a container this follows the CPU limit.
    int instances = Runtime.getRuntime().availableProcessors();
    PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    Supplier<Verticle> verticle = switch (role) {
      case "callee" -> () -> new Callee(registry, port);
      case "caller" -> () -> new Caller(registry, port);
      default -> throw new IllegalArgumentException("unknown ROLE: " + role);
    };

    Vertx.vertx()
        .deployVerticle(verticle, new DeploymentOptions().setInstances(instances))
        .onSuccess(id -> System.out.printf("%s listening on %d with %d event loops%n", role, port, instances))
        .onFailure(e -> {
          e.printStackTrace();
          System.exit(1);
        });
  }

  static String env(String name, String fallback) {
    String v = System.getenv(name);
    return v == null || v.isBlank() ? fallback : v;
  }
}
