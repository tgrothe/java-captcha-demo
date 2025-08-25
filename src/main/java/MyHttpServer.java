import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.commons.lang3.exception.UncheckedException;
import org.apache.commons.text.StringSubstitutor;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MyHttpServer {
  public record MyPreHttpHandler(Function<HttpExchange, Boolean> preHandler) {}

  public record MyHttpHandler(String path, HttpHandler handler) {}

  public enum ContentType {
    HTML("text/html"),
    JSON("application/json"),
    TEXT("text/plain");
    private final String type;

    ContentType(String type) {
      this.type = type;
    }

    @Override
    public String toString() {
      return type;
    }
  }

  private static final Logger LOGGER = LoggerFactory.getLogger(MyHttpServer.class);
  private static final Object LOCK = new Object();
  private static final HashMap<String, String> templates = new HashMap<>();

  public static void start(MyPreHttpHandler preHandler, MyHttpHandler... handlers)
      throws IOException {
    if (handlers == null || handlers.length == 0) {
      throw new IllegalArgumentException("At least one handler must be provided.");
    }
    System.setProperty("java.net.preferIPv4Stack", "true");
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
              synchronized (LOCK) {
                if (preHandler != null) {
                  if (preHandler.preHandler().apply(exchange)) {
                    handler.handler().handle(exchange);
                  }
                } else {
                  handler.handler().handle(exchange);
                }
              }
              if (exchange.getResponseCode() == -1) {
                LOGGER.warn("No response sent for path: {}", handler.path());
                sendCustomResponse(
                    exchange, 404, ContentType.TEXT, null, "Not found or access denied.");
              }
            } catch (Exception e) {
              LOGGER.warn("Exception in handler for path {}", handler.path(), e);
              sendCustomResponse(
                  exchange,
                  404,
                  ContentType.TEXT,
                  null,
                  "An error occurred while processing your request.");
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
      throw new IllegalArgumentException("Index out of bounds for path parts.");
    }
    return URLDecoder.decode(parts[index], StandardCharsets.UTF_8);
  }

  private static String getTemplate(String path, String... args) throws IOException {
    if (path == null || path.isBlank() || args == null) {
      throw new IllegalArgumentException("Path and args cannot be null or empty.");
    }
    if (!templates.containsKey(path)) {
      try (InputStream templateStream = Main.class.getResourceAsStream(path)) {
        assert templateStream != null;
        templates.put(path, new String(templateStream.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    return StringSubstitutor.replace(
        templates.get(path),
        IntStream.range(0, args.length)
            .boxed()
            .collect(
                Collectors.toMap(
                    i -> "" + i, // key is the index as a string
                    i -> args[i] // value is the corresponding argument
                    )),
        "{{",
        "}}");
  }

  public static void sendCustomResponse(
      HttpExchange exchange,
      int statusCode,
      ContentType contentType,
      String templatePath,
      String... templateValues) {
    try {
      String responseBody =
          templatePath != null
              ? getTemplate(templatePath, templateValues)
              : String.join("\n", templateValues);
      byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", contentType + "; charset=utf-8");
      exchange.sendResponseHeaders(statusCode, bytes.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    } catch (Exception e) {
      throw new UncheckedException(e);
    }
  }

  public static void sendHtml200(HttpExchange exchange, String responseBody) {
    sendCustomResponse(exchange, 200, ContentType.HTML, null, responseBody);
  }

  public static void send200(HttpExchange exchange, Map<String, Object> optionalResponseData) {
    JSONObject response = new JSONObject();
    response.put("ok", true);
    if (optionalResponseData != null) {
      for (Map.Entry<String, Object> entry : optionalResponseData.entrySet()) {
        response.put(entry.getKey(), entry.getValue());
      }
    }
    String responseBody = response.toString();
    sendCustomResponse(exchange, 200, ContentType.JSON, null, responseBody);
  }

  public static void send403(HttpExchange exchange, String optionalMessage) {
    JSONObject response = new JSONObject();
    response.put("ok", false);
    response.put(
        "message",
        optionalMessage != null
            ? optionalMessage
            : "Access denied. Please wait 10 seconds before trying again.");
    String responseBody = response.toString();
    sendCustomResponse(exchange, 403, ContentType.JSON, null, responseBody);
  }
}
