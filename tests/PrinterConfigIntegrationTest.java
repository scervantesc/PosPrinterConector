import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.Map;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

/** Isolated HTTP tests; never submits jobs to a printer. */
public class PrinterConfigIntegrationTest {
    static HttpClient client = HttpClient.newHttpClient();
    static String base;
    static HttpResponse<String> post(String path, byte[] bytes) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path))
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofString());
    }
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/printer/config", new Main.TicketConfigHandler());
        server.createContext("/printer/logo", new Main.LogoHandler());
        server.createContext("/", new Main.RootHandler());
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            var saved = post("/printer/config", "{\"paperWidthMm\":58,\"charactersPerLine\":30,\"printableWidthDots\":360,\"printLogo\":true,\"companyFontSize\":2,\"companySpacing\":2}".getBytes());
            check(saved.statusCode() == 200 && saved.body().contains("\"charactersPerLine\":30"), "Save format");
            Main.Settings loaded = (Main.Settings) PrintingRegressionTest.call("loadSettings", new Class<?>[]{});
            check(loaded.paperWidthMm == 58 && loaded.charactersPerLine == 30 && loaded.printableWidthDots == 360, "Persistence");
            check(loaded.companyFontSize == 2 && loaded.companySpacing == 2, "Company settings persistence");
            check(post("/printer/config", "{\"companyFontSize\":4}".getBytes()).statusCode() == 400, "Reject invalid company font");
            var invalid = post("/printer/config", "{\"paperWidthMm\":58,\"charactersPerLine\":0}".getBytes());
            check(invalid.statusCode() == 400, "Reject invalid characters");
            check((int) PrintingRegressionTest.call("parseTicketWidth", new Class<?>[]{Map.class}, Map.of()) == 30, "Saved default used");
            check(post("/printer/logo", new byte[]{1, 2, 3}).statusCode() == 400, "Reject invalid upload");
            check(post("/printer/logo", new byte[2 * 1024 * 1024 + 1]).statusCode() == 400, "Reject oversized upload");
            BufferedImage image = new BufferedImage(1000, 800, BufferedImage.TYPE_INT_ARGB);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(image, "png", png);
            check(post("/printer/logo", png.toByteArray()).statusCode() == 200, "Upload logo");
            Path logo = Path.of(System.getenv("APPDATA"), "TicketPrinter", "logo.png");
            check(Files.size(logo) < 2 * 1024 * 1024, "Stored logo size");
            byte[] ticket = (byte[]) PrintingRegressionTest.call("buildEscPosBytes", new Class<?>[]{Map.class}, Map.of("text", "Logo test", "cut", false));
            boolean raster = false;
            for (int i = 0; i + 2 < ticket.length; i++) if (ticket[i] == 29 && ticket[i+1] == 118 && ticket[i+2] == 48) raster = true;
            check(raster, "Saved logo included in ticket");
            check(post("/printer/logo", new byte[0]).statusCode() == 200 && !Files.exists(logo), "Remove logo");
            var html = client.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString());
            String page = html.body();
            Files.writeString(Path.of(".verification", "ui.js"), page.substring(page.indexOf("<script>") + 8, page.indexOf("</script>")));
            check(page.contains("Cargar logotipo") && page.contains("Caracteres por línea"), "Configuration UI");
            post("/printer/config", "{\"paperWidthMm\":80,\"charactersPerLine\":42,\"printableWidthDots\":576}".getBytes());
            System.out.println("Printer configuration HTTP checks passed.");
        } finally { server.stop(0); }
    }
}
