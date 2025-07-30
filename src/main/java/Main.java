import com.hellokaton.blade.Blade;
import com.hellokaton.blade.mvc.RouteContext;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import javax.imageio.ImageIO;
import net.logicsquad.nanocaptcha.content.LatinContentProducer;
import net.logicsquad.nanocaptcha.image.ImageCaptcha;
import net.logicsquad.nanocaptcha.image.backgrounds.GradiatedBackgroundProducer;
import net.logicsquad.nanocaptcha.image.noise.CurvedLineNoiseProducer;
import net.logicsquad.nanocaptcha.image.noise.StraightLineNoiseProducer;
import net.logicsquad.nanocaptcha.image.renderer.DefaultWordRenderer;
import org.json.JSONObject;
import org.slf4j.Logger;

public class Main {
  private static class MyCaptcha {
    private final ImageCaptcha imageCaptcha;
    private final BufferedImage image;
    private final String base64;

    public MyCaptcha() {
      imageCaptcha =
          new ImageCaptcha.Builder(500, 150)
              .addContent(
                  new LatinContentProducer(12),
                  new DefaultWordRenderer.Builder()
                      .font(DEFAULT_FONT)
                      .randomColor(DEFAULT_COLORS)
                      .build())
              .addBackground(new GradiatedBackgroundProducer())
              .addNoise(new CurvedLineNoiseProducer())
              .addNoise(new StraightLineNoiseProducer())
              .addBorder()
              .build();
      image = imageCaptcha.getImage();
      base64 = imgToBase64String(image, "PNG");
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
  }

  private static class User {
    private long accessTime;
    private int captchaHash;

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
  }

  public abstract static class MyRouteHandler
      implements com.hellokaton.blade.mvc.handler.RouteHandler {
    @Override
    public void handle(RouteContext ctx) {
      synchronized (LOCK) {
        String clientIp = ctx.address();
        User user = USER_MAP.computeIfAbsent(clientIp, k -> new User());
        if (user.hasAccessedRecently()) {
          denyAccess(ctx, null);
          return;
        }
        user.updateAccessTime();
        postHandle(ctx);
      }
    }

    /**
     * This method is called after the initial access checks and before sending the response. It
     * should contain the main logic for handling the request.
     *
     * @param ctx The route context containing request and response information.
     */
    public abstract void postHandle(RouteContext ctx);
  }

  private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(Main.class);

  private static final Font DEFAULT_FONT;
  private static final ArrayList<Color> DEFAULT_COLORS;

  private static final Object LOCK = new Object();
  private static final Map<Integer, String> CAPTCHA_MAP = new HashMap<>();
  private static final Map<String, User> USER_MAP = new HashMap<>();

  private static final long ACCESS_TIMEOUT = 10_000; // 10 seconds
  private static final long CAPTCHA_TIMEOUT = 60_000; // 60 seconds
  private static final String MY_SECRET_MESSAGE =
      "Congratulations! You have successfully solved the captcha. Here is your secret";

  /*
   * Static block to initialize the default font and color gradient.
   * The font "High Empathy.ttf" is loaded from resources, and a color gradient
   * from light blue-grey to black is created.
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

    // Create a color gradient from light blue-grey to black
    Color[] ca = new Color[20];
    for (int i = 0; i < ca.length; i++) {
      float ratio = (float) i / (ca.length - 1);
      ca[i] = colorTransition(new Color(192, 192, 255), Color.BLACK, ratio);
    }
    DEFAULT_COLORS = new ArrayList<>(Arrays.asList(ca));
    DEFAULT_COLORS.add(new Color(255, 0, 0)); // Red
    DEFAULT_COLORS.add(new Color(0, 255, 0)); // Green
    DEFAULT_COLORS.add(new Color(0, 0, 255)); // Blue
    DEFAULT_COLORS.add(new Color(255, 255, 0)); // Yellow
    DEFAULT_COLORS.add(new Color(255, 165, 0)); // Orange
    DEFAULT_COLORS.add(new Color(75, 0, 130)); // Indigo
    DEFAULT_COLORS.add(new Color(238, 130, 238)); // Violet
    DEFAULT_COLORS.add(new Color(128, 128, 128)); // Grey
  }

  public static void main(String[] args) {
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

    // Custom route handlers for captcha generation and validation
    MyRouteHandler getHandler =
        new MyRouteHandler() {
          @Override
          public void postHandle(RouteContext ctx) {
            // Remove the previous captcha if it exists first
            String oldCode = CAPTCHA_MAP.remove(USER_MAP.get(ctx.address()).getCaptchaHash());
            if (oldCode != null) {
              LOGGER.info("Removed old captcha with code: {}", oldCode);
            }

            MyCaptcha myCaptcha = new MyCaptcha();
            String base64Image = myCaptcha.getBase64Image();
            int hash = myCaptcha.getImageHash();
            String code = myCaptcha.getCode();
            LOGGER.info(
                "Generated captcha with hash: {}, code: {}, client IP: {}",
                hash,
                code,
                ctx.address());
            CAPTCHA_MAP.put(hash, code);
            USER_MAP.get(ctx.address()).setCaptchaHash(hash);
            Map<String, Object> responseData = new HashMap<>();
            responseData.put("image", base64Image);
            responseData.put("hash", hash);
            grantAccess(ctx, responseData);
          }
        };

    MyRouteHandler checkHandler =
        new MyRouteHandler() {
          @Override
          public void postHandle(RouteContext ctx) {
            int hash = ctx.pathInt("hash");
            String code = ctx.pathString("code");
            String clientIp = ctx.address();
            LOGGER.info(
                "Checking captcha with hash: {}, code: {}, client IP: {}", hash, code, clientIp);
            if (!CAPTCHA_MAP.containsKey(hash)) {
              denyAccess(ctx, "Captcha hash not found or expired.");
              return;
            }
            if (USER_MAP.get(clientIp).getCaptchaHash() != hash) {
              denyAccess(ctx, "Captcha hash does not match the user's last captcha.");
              return;
            }
            if (!CAPTCHA_MAP.get(hash).equals(code)) {
              denyAccess(ctx, "Captcha code is incorrect.");
              return;
            }
            Map<String, Object> responseData = new HashMap<>();
            responseData.put("message", "Captcha is correct.");
            responseData.put("secret_message", MY_SECRET_MESSAGE);
            grantAccess(ctx, responseData);
          }
        };

    MyRouteHandler demoHandler =
        new MyRouteHandler() {
          @Override
          public void postHandle(RouteContext ctx) {
            ctx.render("demo.html");
          }
        };

    // Start the Blade server
    Blade.create()
        .get("/captcha/get", getHandler)
        .get("/captcha/check/:hash/:code", checkHandler)
        .get("/captcha/demo", demoHandler)
        .listen(80)
        .start();
  }

  private static void denyAccess(RouteContext ctx, String optionalMessage) {
    JSONObject response = new JSONObject();
    response.put("ok", false);
    response.put(
        "message",
        optionalMessage != null
            ? optionalMessage
            : "Access denied. Please wait 10 seconds before trying again.");
    ctx.status(403);
    ctx.json(response.toString());
  }

  private static void grantAccess(RouteContext ctx, Map<String, Object> optionalResponseData) {
    JSONObject response = new JSONObject();
    response.put("ok", true);
    if (optionalResponseData != null) {
      for (Map.Entry<String, Object> entry : optionalResponseData.entrySet()) {
        response.put(entry.getKey(), entry.getValue());
      }
    }
    ctx.status(200);
    ctx.json(response.toString());
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

  private static String imgToBase64String(RenderedImage img, String formatName) {
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    try {
      ImageIO.write(img, formatName, os);
      return Base64.getEncoder().encodeToString(os.toByteArray());
    } catch (IOException ioe) {
      throw new UncheckedIOException(ioe);
    }
  }

  private static Color colorTransition(Color color1, Color color2, float ratio) {
    int red = (int) (color1.getRed() + (color2.getRed() - color1.getRed()) * ratio);
    int green = (int) (color1.getGreen() + (color2.getGreen() - color1.getGreen()) * ratio);
    int blue = (int) (color1.getBlue() + (color2.getBlue() - color1.getBlue()) * ratio);
    return new Color(red, green, blue);
  }
}
