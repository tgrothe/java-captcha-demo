import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.function.Function;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MyHttpServer {
  /**
   * This class provides a path and a handle function for HTTP requests. The handle function is a
   * lambda that takes an HttpExchange object and returns a boolean. The handle function is expected
   * to return true if the request should be processed by the associated HttpHandler, false
   * otherwise.
   */
  public record MyPreHttpHandler(Function<HttpExchange, Boolean> handler) {}

  public record MyHttpHandler(String path, HttpHandler handler) {}

  private static final Logger LOGGER = LoggerFactory.getLogger(MyHttpServer.class);

  public static void start(MyPreHttpHandler preHandler, MyHttpHandler... handlers)
      throws IOException {
    if (preHandler == null) {
      throw new IllegalArgumentException("Pre-handler cannot be null.");
    }
    if (handlers == null || handlers.length == 0) {
      throw new IllegalArgumentException("At least one handler must be provided.");
    }
    HttpServer server = HttpServer.create(new InetSocketAddress(80), 0);
    LOGGER.info("MyHttpServer initialized.");
    for (MyHttpHandler handler : handlers) {
      if (handler == null || handler.path() == null || handler.path().isBlank()) {
        throw new IllegalArgumentException("Handler path cannot be null or empty.");
      }
      server.createContext(
          handler.path(),
          exchange -> {
            LOGGER.info(
                "Received request from client: {} for URL: {}",
                getClientIpAddress(exchange),
                exchange.getRequestURI());
            try {
              synchronized (Main.LOCK) {
                if (preHandler.handler.apply(exchange)) {
                  handler.handler().handle(exchange);
                }
              }
            } catch (Exception e) {
              LOGGER.warn("Exception in handler for path {}: {}", handler.path(), e.getMessage());
              send403(exchange, "An error occurred while processing your request.");
              exchange.close();
            }
            LOGGER.info(
                "Request processing completed for client: {} with status code: {}",
                getClientIpAddress(exchange),
                exchange.getResponseCode());
          });
      LOGGER.info("Handler added for path: {}", handler.path());
    }
    server.setExecutor(null); // creates a default executor
    server.start();
    LOGGER.info("Server started on port 80.");
  }

  public static String getClientIpAddress(HttpExchange request) {
    String xForwardedForHeader = request.getRequestHeaders().getFirst("X-Forwarded-For");
    if (xForwardedForHeader == null) {
      return request.getRemoteAddress().getAddress().getHostAddress();
    }
    // As of https://en.wikipedia.org/wiki/X-Forwarded-For
    // The general format of the field is: X-Forwarded-For: client, proxy1, proxy2 ...
    // we only want the client
    return new StringTokenizer(xForwardedForHeader, ",").nextToken().trim();
  }

  public static String getPathPart(HttpExchange ex, int index) {
    String[] parts = ex.getRequestURI().getRawPath().split("/");
    if (index < 0 || index >= parts.length) {
      return null;
    }
    return URLDecoder.decode(parts[index], StandardCharsets.UTF_8);
  }

  public static void sendHtml200(HttpExchange exchange, String responseBody) {
    try {
      exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
      exchange.sendResponseHeaders(200, responseBody.length());
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(responseBody.getBytes(StandardCharsets.UTF_8));
      }
    } catch (IOException e) {
      LOGGER.error("Failed to send HTML 200 response: {}", e.getMessage());
      throw new UncheckedIOException(e);
    }
  }

  public static void send200(HttpExchange exchange, Map<String, Object> optionalResponseData) {
    try {
      JSONObject response = new JSONObject();
      response.put("ok", true);
      if (optionalResponseData != null) {
        for (Map.Entry<String, Object> entry : optionalResponseData.entrySet()) {
          response.put(entry.getKey(), entry.getValue());
        }
      }
      String responseBody = response.toString();
      exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
      exchange.sendResponseHeaders(200, responseBody.length());
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(responseBody.getBytes(StandardCharsets.UTF_8));
      }
    } catch (IOException e) {
      LOGGER.error("Failed to send 200 response: {}", e.getMessage());
      throw new UncheckedIOException(e);
    }
  }

  public static void send403(HttpExchange exchange, String optionalMessage) {
    try {
      JSONObject response = new JSONObject();
      response.put("ok", false);
      response.put(
          "message",
          optionalMessage != null
              ? optionalMessage
              : "Access denied. Please wait 10 seconds before trying again.");
      String responseBody = response.toString();
      exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
      exchange.sendResponseHeaders(403, responseBody.length());
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(responseBody.getBytes(StandardCharsets.UTF_8));
      }
    } catch (IOException e) {
      LOGGER.error("Failed to send 403 response: {}", e.getMessage());
      throw new UncheckedIOException(e);
    }
  }
}
