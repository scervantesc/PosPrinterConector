import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

/** No printers or HTTP service are started. APPDATA must point to a test directory. */
public class PrintingRegressionTest {
    static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = Main.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, args);
    }
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Class<?>[] mapType = {Map.class};
        check((int) call("parseTicketWidth", mapType, Map.of("paperWidthMm", 58)) == 32, "58 mm default");
        check((int) call("parseTicketWidth", mapType, Map.of("paperWidthMm", 80)) == 42, "80 mm default");
        check((int) call("parseTicketWidth", mapType, Map.of("ticketWidth", 48)) == 48, "Legacy width");
        check((int) call("parseTicketWidth", mapType, Map.of("ticketWidth", 48, "charactersPerLine", 30)) == 30, "Explicit characters win");
        try { call("parseTicketWidth", mapType, Map.of("charactersPerLine", 0)); throw new AssertionError("Invalid width accepted"); }
        catch (java.lang.reflect.InvocationTargetException e) { check(e.getCause() instanceof IllegalArgumentException, "Validation"); }
        List<String> lines = new ArrayList<>();
        call("appendWrapped", new Class<?>[]{List.class, String.class, int.class}, lines,
            "Una descripcion larga con palabras y 123456789012345678901234567890123456789", 24);
        check(lines.stream().allMatch(line -> line.length() <= 24), "Wrapping exceeds width");
        for (int width : new int[]{16, 32, 42, 48}) {
            List<String> product = new ArrayList<>();
            call("appendProductText", new Class<?>[]{List.class, String.class, int.class}, product,
                "2 x Descripcion de producto con muchas palabras ".repeat(8), width);
            check(product.size() == 2 && product.get(1).endsWith("..."), "Product must truncate after two lines");
            check(product.stream().allMatch(line -> line.length() <= width), "Product exceeds width");
        }
        List<String> shortProduct = new ArrayList<>();
        call("appendProductText", new Class<?>[]{List.class, String.class, int.class}, shortProduct, "1 x Cable", 32);
        check(shortProduct.equals(List.of("1 x Cable")), "Short product modified");
        byte[] raw = {27, 64, 10};
        check(Arrays.equals(raw, (byte[]) call("buildEscPosBytes", mapType,
            Map.of("rawBase64", Base64.getEncoder().encodeToString(raw)))), "Raw data modified");
        byte[] ticket = (byte[]) call("buildEscPosBytes", mapType,
            Map.of("paperWidthMm", 58, "charactersPerLine", 24, "printLogo", false,
                "lines", List.of("123456789012345678901234567890"), "cut", false));
        String output = new String(ticket, StandardCharsets.ISO_8859_1);
        check(output.contains("123456789012345678901234\n"), "Plain ticket not wrapped");
        for (int size = 1; size <= 3; size++) {
            ByteArrayOutputStream headerOut = new ByteArrayOutputStream();
            try (var escpos = new com.github.anastaciocintra.escpos.EscPos(headerOut)) {
                call("writeCompanyHeader", new Class<?>[]{com.github.anastaciocintra.escpos.EscPos.class, String.class, int.class, Map.class},
                    escpos, "123456789012345678901234567890", size == 3 ? 16 : 32, Map.of("ticketFontSize", size, "companySpacing", 2));
            }
            byte[] header = headerOut.toByteArray();
            int sizeByte = size == 3 ? 17 : size == 2 ? 1 : 0;
            boolean fontFound = false;
            for (int i = 0; i + 2 < header.length; i++) {
                if (header[i] == 29 && header[i+1] == 33 && header[i+2] == sizeByte) fontFound = true;
            }
            check(fontFound && header[header.length - 1] == 10 && header[header.length - 2] == 10, "Company font and spacing commands");
            String headerText = new String(header, StandardCharsets.ISO_8859_1);
            check(headerText.contains(size == 3 ? "1234567890123456\n" : "123456789012345678901234567890\n"), "Company wrapping for font size");
        }
        byte[] largeTicket = (byte[]) call("buildEscPosBytes", mapType,
            Map.of("text", "123456789012345678901234567890", "charactersPerLine", 32,
                "ticketFontSize", 3, "printLogo", false, "feed", 0, "cut", false));
        check(new String(largeTicket, StandardCharsets.ISO_8859_1).contains("1234567890123456\n"), "Global large font wrapping");
        boolean globalFont = false;
        for (int i = 0; i + 2 < largeTicket.length; i++)
            if (largeTicket[i] == 29 && largeTicket[i+1] == 33 && largeTicket[i+2] == 17) globalFont = true;
        check(globalFont, "Global font missing from plain ticket");
        for (String type : List.of("factura", "nota_venta")) {
            byte[] structured = (byte[]) call("buildEscPosBytes", mapType, Map.of("ticketType", type,
                "data", Map.of("Empresa", "Empresa de prueba", "Venta", Map.of("Serie", "A", "Folio", "123")),
                "paperWidthMm", 58, "printLogo", false, "cut", false));
            boolean hasCut = false;
            for (int i = 0; i + 1 < structured.length; i++) if (structured[i] == 29 && structured[i+1] == 86) hasCut = true;
            check(!hasCut, "Structured renderer ignores cut=false");
        }
        BufferedImage image = new BufferedImage(800, 100, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 20; y++) for (int x = 0; x < 20; x++) image.setRGB(x, y, 0xff000000);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        byte[] raster = (byte[]) call("logoRaster", new Class<?>[]{byte[].class, int.class}, png.toByteArray(), 384);
        int rowBytes = (raster[7] & 255) + ((raster[8] & 255) << 8);
        check(rowBytes == 48, "58 mm raster wider than 384 dots");
        check((raster[11] & 128) != 0, "Black pixel missing");
        check(raster[20] == 0, "Transparency must print white");
        try { call("decodeLogo", new Class<?>[]{byte[].class}, new byte[]{1, 2, 3}); throw new AssertionError("Invalid image accepted"); }
        catch (java.lang.reflect.InvocationTargetException e) { check(e.getCause() instanceof IllegalArgumentException, "Image validation"); }
        System.out.println("Printing regression checks passed.");
    }
}
