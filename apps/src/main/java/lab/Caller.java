package lab;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import java.util.concurrent.TimeUnit;

/**
 * Forwards /work to the callee Service over a keep-alive pool, like a gateway.
 * Each event loop owns its own pool, so total connections = event loops x POOL_SIZE.
 */
final class Caller extends AbstractVerticle {
  private final PrometheusMeterRegistry registry;
  private final int port;

  Caller(PrometheusMeterRegistry registry, int port) {
    this.registry = registry;
    this.port = port;
  }

  @Override
  public void start(Promise<Void> started) {
    HttpClient client = vertx.createHttpClient(new HttpClientOptions()
        .setDefaultHost(Main.env("CALLEE_HOST", "callee"))
        .setDefaultPort(Integer.parseInt(Main.env("CALLEE_PORT", "8080")))
        .setKeepAlive(true)
        .setMaxPoolSize(Integer.parseInt(Main.env("POOL_SIZE", "20"))));

    Timer ok = Timer.builder("caller_request").tag("outcome", "ok").publishPercentileHistogram().register(registry);
    Counter failed = Counter.builder("caller_request_failed").register(registry);

    vertx.createHttpServer()
        .requestHandler(req -> {
          switch (req.path()) {
            case "/work" -> {
              long t0 = System.nanoTime();
              client.request(HttpMethod.GET, "/work")
                  .compose(r -> r.send())
                  .compose(resp -> resp.body().map(body -> resp.statusCode() == 200 ? body : null))
                  .onComplete(ar -> {
                    if (ar.succeeded() && ar.result() != null) {
                      ok.record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);
                      req.response().end(ar.result());
                    } else {
                      failed.increment();
                      req.response().setStatusCode(502).end();
                    }
                  });
            }
            case "/metrics" -> req.response()
                .putHeader("content-type", "text/plain; version=0.0.4")
                .end(registry.scrape());
            case "/health" -> req.response().end("ok");
            default -> req.response().setStatusCode(404).end();
          }
        })
        .listen(port)
        .<Void>mapEmpty()
        .onComplete(started);
  }
}
