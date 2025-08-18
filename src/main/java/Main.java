import com.sun.net.httpserver.HttpExchange;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import net.logicsquad.nanocaptcha.content.LatinContentProducer;
import net.logicsquad.nanocaptcha.image.ImageCaptcha;
import net.logicsquad.nanocaptcha.image.backgrounds.GradiatedBackgroundProducer;
import net.logicsquad.nanocaptcha.image.noise.CurvedLineNoiseProducer;
import net.logicsquad.nanocaptcha.image.noise.StraightLineNoiseProducer;
import net.logicsquad.nanocaptcha.image.renderer.DefaultWordRenderer;
import org.slf4j.Logger;

public class Main {
  private static class MyCaptcha {
    private final ImageCaptcha imageCaptcha;
    private final String base64;
    private final int difficulty;

    public MyCaptcha(User user) {
      if (user.getCaptchaCreations() < DEFAULT_COLORS.size()) {
        imageCaptcha =
            new ImageCaptcha.Builder(500, 150)
                .addContent(
                    new LatinContentProducer(12),
                    new DefaultWordRenderer.Builder()
                        .font(DEFAULT_FONT)
                        .randomColor(DEFAULT_COLORS.get(user.getCaptchaCreations()))
                        .build())
                .addBackground(new GradiatedBackgroundProducer())
                .addNoise(new CurvedLineNoiseProducer())
                .addNoise(new CurvedLineNoiseProducer())
                .addNoise(new StraightLineNoiseProducer())
                .addBorder()
                .build();
        difficulty = 100 - (user.getCaptchaCreations() + 1) * 100 / (DEFAULT_COLORS.size() + 1);
      } else {
        imageCaptcha =
            new ImageCaptcha.Builder(500, 150)
                .addContent(
                    new LatinContentProducer(12),
                    new DefaultWordRenderer.Builder()
                        .font(DEFAULT_FONT)
                        .randomColor(DEFAULT_COLORS.get(DEFAULT_COLORS.size() - 1))
                        .build())
                .addBackground(new GradiatedBackgroundProducer())
                .addNoise(new CurvedLineNoiseProducer())
                .addNoise(new StraightLineNoiseProducer())
                .addBorder()
                .build();
        difficulty = 10;
      }
      user.incrementCaptchaCreations();
      BufferedImage image = imageCaptcha.getImage();
      base64 = imgToBase64String(image);
    }

    public String getBase64Image() {
      return base64;
    }

    public String getCode() {
      return imageCaptcha.getContent();
    }

    public int getImageHash() {
      return base64.hashCode();
    }

    public int getCaptchaDifficulty() {
      return difficulty;
    }
  }

  private static class User {
    private long accessTime;
    private int captchaHash;
    private int captchaCreations;

    public boolean hasAccessedRecently() {
      return System.currentTimeMillis() - accessTime <= ACCESS_TIMEOUT;
    }

    public boolean hasCaptchaExpired() {
      return System.currentTimeMillis() - accessTime > CAPTCHA_TIMEOUT;
    }

    public void updateAccessTime() {
      accessTime = System.currentTimeMillis();
    }

    public int getCaptchaHash() {
      return captchaHash;
    }

    public void setCaptchaHash(int captchaHash) {
      this.captchaHash = captchaHash;
    }

    public void incrementCaptchaCreations() {
      captchaCreations++;
    }

    public int getCaptchaCreations() {
      return captchaCreations;
    }
  }

  public static final Object LOCK = new Object();

  private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(Main.class);

  private static final Font DEFAULT_FONT;
  private static final List<List<Color>> DEFAULT_COLORS;

  private static final Map<Integer, String> CAPTCHA_MAP = new HashMap<>();
  private static final Map<String, User> USER_MAP = new HashMap<>();

  private static final long ACCESS_TIMEOUT = 10_000; // 10 seconds
  private static final long CAPTCHA_TIMEOUT = 60_000; // 60 seconds
  private static final String MY_SECRET_MESSAGE =
      "Congratulations! You have successfully solved the captcha. This was a test of your skills.";

  /*
   * Static block to initialize the default font and color gradient.
   * The font "High Empathy.ttf" is loaded from resources, and a color gradient
   * from white to black is created.
   */
  static {
    // Load the custom font "High Empathy.ttf" from resources
    try {
      GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
      ge.registerFont(
          Font.createFont(
              Font.TRUETYPE_FONT,
              Objects.requireNonNull(Main.class.getResourceAsStream("High Empathy.ttf"))));
      DEFAULT_FONT =
          Arrays.stream(ge.getAllFonts())
              .filter(f -> f.getFontName().equals("High Empathy"))
              .findFirst()
              .orElseThrow(() -> new RuntimeException("Font not found"))
              .deriveFont(Font.PLAIN, 72f);
    } catch (IOException | FontFormatException e) {
      throw new RuntimeException("Failed to load custom font", e);
    }

    DEFAULT_COLORS = new ArrayList<>();
    for (int alpha = 127; alpha <= 255; alpha += 64) {
      ArrayList<Color> colors = new ArrayList<>();
      int numColors = 6; // Number of colors in the gradient
      for (int i = 0; i < numColors; i++) {
        float ratio = (float) i / (numColors - 1);
        colors.add(colorTransition(new Color(127, 127, 255), Color.BLACK, ratio, alpha));
      }
      DEFAULT_COLORS.add(colors);
    }
  }

  public static void main(String[] args) throws IOException {
    new Timer(false)
        .schedule(
            new TimerTask() {
              @Override
              public void run() {
                synchronized (LOCK) {
                  clearExpiredCaptchas();
                }
              }
            },
            0,
            CAPTCHA_TIMEOUT);

    new MyHttpServer()
        .addPreHandler(
            exchange -> {
              String clientIp = MyHttpServer.getClientIpAddress(exchange);
              User user = USER_MAP.computeIfAbsent(clientIp, k -> new User());
              if (user.hasAccessedRecently()) {
                MyHttpServer.send403(exchange, null);
                return;
              }
              user.updateAccessTime();
            })
        .addHandler(
            "/captcha/get",
            exchange -> {
              String clientIp = MyHttpServer.getClientIpAddress(exchange);
              User user = USER_MAP.get(clientIp);
              // Remove the previous captcha if it exists first
              String oldCode = CAPTCHA_MAP.remove(user.getCaptchaHash());
              if (oldCode != null) {
                LOGGER.info("Removed old captcha with code: {}", oldCode);
              }

              MyCaptcha myCaptcha = new MyCaptcha(user);
              String base64Image = myCaptcha.getBase64Image();
              int hash = myCaptcha.getImageHash();
              int difficulty = myCaptcha.getCaptchaDifficulty();
              String code = myCaptcha.getCode();
              LOGGER.info(
                  "Generated captcha with hash: {}, code: {}, difficulty: {}, client IP: {}",
                  hash,
                  code,
                  difficulty,
                  clientIp);
              CAPTCHA_MAP.put(hash, code);
              user.setCaptchaHash(hash);
              MyHttpServer.send200(
                  exchange, Map.of("image", base64Image, "hash", hash, "difficulty", difficulty));
            })
        .addHandler(
            "/captcha/check",
            exchange -> {
              int hash = Integer.parseInt(Objects.requireNonNull(getPathPart(exchange, 3)));
              String code = Objects.requireNonNull(getPathPart(exchange, 4));
              String clientIp = MyHttpServer.getClientIpAddress(exchange);
              LOGGER.info(
                  "Checking captcha with hash: {}, code: {}, client IP: {}", hash, code, clientIp);
              if (!CAPTCHA_MAP.containsKey(hash)) {
                MyHttpServer.send403(exchange, "Captcha hash not found or expired.");
                return;
              }
              if (USER_MAP.get(clientIp).getCaptchaHash() != hash) {
                MyHttpServer.send403(
                    exchange, "Captcha hash does not match the user's last captcha.");
                return;
              }
              if (!CAPTCHA_MAP.get(hash).equals(code)) {
                MyHttpServer.send403(exchange, "Captcha code is incorrect.");
                return;
              }
              LOGGER.info(
                  "Captcha with hash: {} and code: {} is correct for client IP: {}",
                  hash,
                  code,
                  clientIp);
              MyHttpServer.send200(
                  exchange,
                  Map.of("message", "Captcha is correct.", "secret_message", MY_SECRET_MESSAGE));
            })
        .addHandler(
            "/captcha/demo",
            exchange -> {
              try (InputStream templateStream =
                  Main.class.getResourceAsStream("templates/demo.html")) {
                assert templateStream != null;
                MyHttpServer.sendHtml200(
                    exchange, new String(templateStream.readAllBytes(), StandardCharsets.UTF_8));
              }
            })
        .start();
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

  private static String getPathPart(HttpExchange ex, int index) {
    String[] parts = ex.getRequestURI().getRawPath().split("/");
    if (index < 0 || index >= parts.length) {
      return null;
    }
    return URLDecoder.decode(parts[index], StandardCharsets.UTF_8);
  }

  private static void clearExpiredCaptchas() {
    for (Iterator<Map.Entry<String, User>> it = USER_MAP.entrySet().iterator(); it.hasNext(); ) {
      Map.Entry<String, User> entry = it.next();
      if (!entry.getValue().hasCaptchaExpired()) {
        continue; // User has not expired captcha, skip
      }
      LOGGER.info(
          "Removing expired captcha for user {} with code: {}",
          entry.getKey(),
          CAPTCHA_MAP.remove(entry.getValue().getCaptchaHash()));
      it.remove(); // Remove expired user
    }
  }

  private static String imgToBase64String(RenderedImage img) {
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    try {
      ImageIO.write(img, "PNG", os);
      return Base64.getEncoder().encodeToString(os.toByteArray());
    } catch (IOException ioe) {
      throw new UncheckedIOException(ioe);
    }
  }

  private static Color colorTransition(Color color1, Color color2, float ratio, int alpha) {
    int red = (int) (color1.getRed() + (color2.getRed() - color1.getRed()) * ratio);
    int green = (int) (color1.getGreen() + (color2.getGreen() - color1.getGreen()) * ratio);
    int blue = (int) (color1.getBlue() + (color2.getBlue() - color1.getBlue()) * ratio);
    return new Color(red, green, blue, alpha);
  }
}
