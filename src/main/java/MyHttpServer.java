import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.StringTokenizer;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MyHttpServer {
  private static final Logger LOGGER = LoggerFactory.getLogger(MyHttpServer.class);
  private final HttpServer server;
  private HttpHandler preHandler;

  public MyHttpServer() {
    HttpServer server = null;
    try {
      server = HttpServer.create(new InetSocketAddress(80), 0);
      LOGGER.info("MyHttpServer initialized.");
    } catch (IOException e) {
      LOGGER.error("Failed to create HTTP server on port 80: {}", e.getMessage());
    }
    this.server = server;
  }

  public MyHttpServer addPreHandler(HttpHandler handler) {
    preHandler = handler;
    LOGGER.info("Pre-handler added.");
    return this;
  }

  public MyHttpServer addHandler(String path, HttpHandler handler) {
    if (preHandler == null) {
      throw new IllegalArgumentException(
          "Pre-handler must be set before adding a handler for path: " + path);
    }
    server.createContext(
        path,
        exchange -> {
          LOGGER.info(
              "Received request form client: {} for URL: {}",
              getClientIpAddress(exchange),
              exchange.getRequestURI());
          try {
            synchronized (Main.LOCK) {
              preHandler.handle(exchange);
              if (exchange.getResponseCode() == -1) {
                handler.handle(exchange);
              }
            }
            if (exchange.getResponseCode() == -1) {
              throw new IOException("No response set by pre-handler or handler.");
            }
          } catch (Exception e) {
            LOGGER.warn("Exception handling request: {}", e.getMessage());
            send403(exchange, "An error occurred while processing your request.");
          }
          LOGGER.info(
              "Request processing completed for client: {} with status code: {}",
              getClientIpAddress(exchange),
              exchange.getResponseCode());
          exchange.close();
        });
    LOGGER.info("Handler added for path: {}", path);
    return this;
  }

  public void start() {
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

  public static void sendHtml200(HttpExchange exchange, String responseBody) throws IOException {
    exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
    exchange.sendResponseHeaders(200, responseBody.length());
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(responseBody.getBytes(StandardCharsets.UTF_8));
    }
  }

  public static void send200(HttpExchange exchange, Map<String, Object> optionalResponseData)
      throws IOException {
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
  }

  public static void send403(HttpExchange exchange, String optionalMessage) throws IOException {
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
  }
}
