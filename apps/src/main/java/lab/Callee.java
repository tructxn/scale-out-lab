package lab;

import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Burns WORK_US microseconds of CPU on the event loop per /work request, so pod capacity is known. */
final class Callee extends AbstractVerticle {
  private static volatile long sink;
  // Shared by all event loops of this pod; registered once.
  private static final AtomicInteger OPEN_CONNECTIONS = new AtomicInteger();

  private final PrometheusMeterRegistry registry;
  private final int port;
  private final long workNanos = Long.parseLong(Main.env("WORK_US", "1000")) * 1_000;

  Callee(PrometheusMeterRegistry registry, int port) {
    this.registry = registry;
    this.port = port;
  }

  @Override
  public void start(Promise<Void> started) {
    // Handler time only: queueing before the handler runs is invisible here (see eBPF section).
    Timer timer = Timer.builder("callee_request").publishPercentileHistogram().register(registry);
    registry.gauge("callee_open_connections", OPEN_CONNECTIONS);

    vertx.createHttpServer()
        .connectionHandler(conn -> {
          OPEN_CONNECTIONS.incrementAndGet();
          conn.closeHandler(v -> OPEN_CONNECTIONS.decrementAndGet());
        })
        .requestHandler(req -> {
          switch (req.path()) {
            case "/work" -> {
              long t0 = System.nanoTime();
              burn(workNanos);
              req.response().end("ok");
              timer.record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);
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

  static void burn(long nanos) {
    long end = System.nanoTime() + nanos;
    long n = 0;
    while (System.nanoTime() < end) {
      n++;
    }
    sink = n;
  }
}
