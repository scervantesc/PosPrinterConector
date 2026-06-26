import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.github.anastaciocintra.escpos.EscPos;
import com.github.anastaciocintra.escpos.EscPosConst;
import com.github.anastaciocintra.escpos.Style;
import com.github.anastaciocintra.escpos.barcode.BarCode;
import com.github.anastaciocintra.escpos.barcode.QRCode;
import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import javax.print.Doc;
import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.SimpleDoc;
import javax.print.attribute.PrintServiceAttributeSet;
import javax.print.attribute.standard.PrinterIsAcceptingJobs;
import javax.print.attribute.standard.QueuedJobCount;
import javax.swing.JCheckBox;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.JTextField;

public class Main {
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5001;
    private static final Path APP_DATA_DIR = resolveAppDataDir();
    private static final Path SELECTED_PRINTER_FILE = APP_DATA_DIR.resolve("selected-printer.txt");
    private static final Path SETTINGS_FILE = APP_DATA_DIR.resolve("settings.properties");
    private static final AtomicReference<String> selectedPrinter = new AtomicReference<>(loadSelectedPrinter());
    private static final AtomicReference<Settings> settingsRef = new AtomicReference<>(loadSettings());
    private static HttpServer server;
    private static TrayIcon trayIcon;
    private static MenuItem trayStatusItem;

    public static void main(String[] args) throws Exception {
        boolean noUi = false;
        for (String arg : args) {
            if ("--no-ui".equalsIgnoreCase(arg)) {
                noUi = true;
            }
        }

        boolean shouldStartService = noUi || settingsRef.get().startServiceOnLaunch;
        if (shouldStartService) {
            startHttpServer();
        }
        Runtime.getRuntime().addShutdownHook(new Thread(Main::stopHttpServer));

        if (!noUi && !GraphicsEnvironment.isHeadless()) {
            if (SystemTray.isSupported()) {
                SwingUtilities.invokeLater(() -> {
                    try {
                        installSystemTray();
                    } catch (Exception e) {
                        showDesktopUi();
                    }
                });
            } else {
                SwingUtilities.invokeLater(Main::showDesktopUi);
            }
        }
    }

    private static synchronized void startHttpServer() throws IOException {
        if (server != null) {
            return;
        }
        server = HttpServer.create(new InetSocketAddress(HOST, PORT), 0);
        server.createContext("/", new RootHandler());
        server.createContext("/health", new HealthHandler());
        server.createContext("/printers", new PrintersHandler());
        server.createContext("/printer/select", new SelectPrinterHandler());
        server.createContext("/queue", new QueueHandler());
        server.createContext("/label/brother/config", new BrotherLabelConfigHandler());
        server.createContext("/label/brother", new BrotherLabelHandler());
        server.createContext("/printer", new PrintTicketHandler());
        server.setExecutor(null);
        server.start();
        System.out.println("Servicio listo en http://" + HOST + ":" + PORT);
        System.out.println("Impresora seleccionada: " + selectedPrinter.get());
        refreshTrayStatus();
    }

    private static synchronized void stopHttpServer() {
        if (server != null) {
            try {
                server.stop(0);
            } finally {
                server = null;
            }
        }
        refreshTrayStatus();
    }

    private static void updateSelectedPrinter(String name) {
        selectedPrinter.set(name);
        saveSelectedPrinter(name);
        refreshTrayStatus();
    }

    private static Path resolveAppDataDir() {
        String appData = System.getenv("APPDATA");
        Path base = (appData == null || appData.isBlank()) ? Paths.get(System.getProperty("user.home")) : Paths.get(appData);
        return base.resolve("TicketPrinter");
    }

    private static void ensureAppDataDir() {
        try {
            Files.createDirectories(APP_DATA_DIR);
        } catch (IOException e) {
            throw new RuntimeException("No se pudo crear directorio de configuracion: " + APP_DATA_DIR, e);
        }
    }

    private static void installSystemTray() throws AWTException {
        if (trayIcon != null) {
            return;
        }
        PopupMenu menu = new PopupMenu();

        trayStatusItem = new MenuItem("Servicio: " + (isServerRunning() ? "ACTIVO" : "DETENIDO"));
        trayStatusItem.setEnabled(false);
        menu.add(trayStatusItem);

        MenuItem startServiceItem = new MenuItem("Iniciar servicio");
        startServiceItem.addActionListener(e -> {
            try {
                startHttpServer();
                notifyTray("Servicio iniciado", "Endpoint activo en 127.0.0.1:5001");
            } catch (Exception ex) {
                notifyTray("Error", ex.getMessage());
            }
        });
        menu.add(startServiceItem);

        MenuItem stopServiceItem = new MenuItem("Detener servicio");
        stopServiceItem.addActionListener(e -> {
            stopHttpServer();
            notifyTray("Servicio detenido", "El endpoint local fue detenido.");
        });
        menu.add(stopServiceItem);

        menu.addSeparator();

        MenuItem openSelectorItem = new MenuItem("Seleccionar impresora");
        openSelectorItem.addActionListener(e -> SwingUtilities.invokeLater(Main::showDesktopUi));
        menu.add(openSelectorItem);

        MenuItem queueItem = new MenuItem("Mostrar cola de impresion");
        queueItem.addActionListener(e -> showPrintQueueDialog());
        menu.add(queueItem);

        MenuItem testPrintItem = new MenuItem("Enviar texto de prueba");
        testPrintItem.addActionListener(e -> sendTestPrint());
        menu.add(testPrintItem);

        menu.addSeparator();

        JCheckBox startupCheckbox = new JCheckBox("Iniciar app con Windows", settingsRef.get().startWithWindows);
        MenuItem startWithWindowsItem = new MenuItem("Configurar inicio con Windows...");
        startWithWindowsItem.addActionListener(e -> SwingUtilities.invokeLater(() -> {
            int choice = JOptionPane.showConfirmDialog(
                null,
                startupCheckbox,
                "Opciones de arranque",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
            );
            if (choice == JOptionPane.OK_OPTION) {
                boolean enabled = startupCheckbox.isSelected();
                try {
                    setStartWithWindows(enabled);
                    Settings s = settingsRef.get();
                    s.startWithWindows = enabled;
                    saveSettings(s);
                    notifyTray("Configuracion guardada", enabled ? "Iniciara con Windows." : "Ya no iniciara con Windows.");
                } catch (Exception ex) {
                    startupCheckbox.setSelected(!enabled);
                    notifyTray("Error", ex.getMessage());
                }
            }
        }));
        menu.add(startWithWindowsItem);

        JCheckBox launchServiceCheckbox = new JCheckBox("Iniciar servicio al arrancar app", settingsRef.get().startServiceOnLaunch);
        MenuItem serviceLaunchItem = new MenuItem("Configurar arranque del servicio...");
        serviceLaunchItem.addActionListener(e -> SwingUtilities.invokeLater(() -> {
            int choice = JOptionPane.showConfirmDialog(
                null,
                launchServiceCheckbox,
                "Opciones de arranque",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
            );
            if (choice == JOptionPane.OK_OPTION) {
                Settings s = settingsRef.get();
                s.startServiceOnLaunch = launchServiceCheckbox.isSelected();
                saveSettings(s);
                notifyTray("Configuracion guardada", "Arranque del servicio actualizado.");
            }
        }));
        menu.add(serviceLaunchItem);

        menu.addSeparator();

        MenuItem openWebItem = new MenuItem("Abrir UI web");
        openWebItem.addActionListener(e -> {
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().browse(new URI("http://" + HOST + ":" + PORT + "/"));
                }
            } catch (Exception ex) {
                notifyTray("Error", ex.getMessage());
            }
        });
        menu.add(openWebItem);

        MenuItem exitItem = new MenuItem("Salir");
        exitItem.addActionListener(e -> {
            stopHttpServer();
            if (trayIcon != null) {
                SystemTray.getSystemTray().remove(trayIcon);
            }
            System.exit(0);
        });
        menu.add(exitItem);

        trayIcon = new TrayIcon(createTrayImage(), "Ticket Printer ESC/POS", menu);
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(e -> SwingUtilities.invokeLater(Main::showDesktopUi));
        SystemTray.getSystemTray().add(trayIcon);
        refreshTrayStatus();
        notifyTray("Ticket Printer listo", "Usa el icono del System Tray.");
    }

    private static Image createTrayImage() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        int bg = 0xFF1E1E1E;
        int fg = 0xFFFFFFFF;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                image.setRGB(x, y, bg);
            }
        }
        for (int x = 3; x <= 12; x++) {
            image.setRGB(x, 4, fg);
        }
        for (int x = 4; x <= 11; x++) {
            image.setRGB(x, 7, fg);
        }
        for (int x = 5; x <= 10; x++) {
            image.setRGB(x, 10, fg);
        }
        image.setRGB(8, 13, fg);
        return image;
    }

    private static void notifyTray(String title, String message) {
        if (trayIcon != null) {
            trayIcon.displayMessage(title, message, TrayIcon.MessageType.INFO);
        }
    }

    private static synchronized void refreshTrayStatus() {
        if (trayStatusItem != null) {
            String serviceState = isServerRunning() ? "ACTIVO" : "DETENIDO";
            String printer = selectedPrinter.get();
            if (printer == null || printer.isBlank()) {
                printer = "sin seleccionar";
            }
            trayStatusItem.setLabel("Servicio: " + serviceState + " | Impresora: " + printer);
        }
    }

    private static boolean isServerRunning() {
        return server != null;
    }

    private static void showDesktopUi() {
        JFrame frame = new JFrame("Ticket Printer ESC/POS");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(720, 340);
        frame.setLocationRelativeTo(null);

        JLabel endpointLabel = new JLabel("Endpoint: http://" + HOST + ":" + PORT + "/printer");
        JLabel statusLabel = new JLabel("Servicio HTTP: " + (isServerRunning() ? "ACTIVO" : "DETENIDO"));
        JLabel selectedLabel = new JLabel("Impresora seleccionada: " + selectedPrinter.get());
        JComboBox<String> printersCombo = new JComboBox<>();
        JLabel labelPrinterLabel = new JLabel("Impresora de etiquetas: " + asString(settingsRef.get().labelPrinterName, ""));
        JComboBox<String> labelPrintersCombo = new JComboBox<>();
        JTextField labelTemplateDirField = new JTextField(asString(settingsRef.get().labelTemplateDir, defaultLabelTemplateDir().toString()));

        JButton refreshButton = new JButton("Actualizar impresoras");
        refreshButton.addActionListener(e -> {
            loadPrintersIntoCombo(printersCombo);
            loadLabelPrintersIntoCombo(labelPrintersCombo);
        });

        JButton saveButton = new JButton("Guardar seleccion");
        saveButton.addActionListener(e -> {
            Object item = printersCombo.getSelectedItem();
            if (item == null) {
                JOptionPane.showMessageDialog(frame, "No hay impresora seleccionada.");
                return;
            }
            String name = String.valueOf(item);
            PrintService printer = findPrinterByName(name);
            if (printer == null) {
                JOptionPane.showMessageDialog(frame, "La impresora ya no existe.");
                return;
            }
            updateSelectedPrinter(printer.getName());
            selectedLabel.setText("Impresora seleccionada: " + printer.getName());
            JOptionPane.showMessageDialog(frame, "Impresora guardada.");
        });

        JButton openWebButton = new JButton("Abrir UI web");
        openWebButton.addActionListener(e -> {
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().browse(new URI("http://" + HOST + ":" + PORT + "/"));
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(frame, "No se pudo abrir el navegador: " + ex.getMessage());
            }
        });

        JButton stopButton = new JButton("Detener servicio");
        stopButton.addActionListener(e -> {
            stopHttpServer();
            statusLabel.setText("Servicio HTTP: DETENIDO");
        });

        JButton startButton = new JButton("Iniciar servicio");
        startButton.addActionListener(e -> {
            try {
                startHttpServer();
                statusLabel.setText("Servicio HTTP: ACTIVO");
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(frame, "No se pudo iniciar: " + ex.getMessage());
            }
        });

        JButton saveLabelButton = new JButton("Guardar etiquetas");
        saveLabelButton.addActionListener(e -> {
            Object item = labelPrintersCombo.getSelectedItem();
            if (item == null) {
                JOptionPane.showMessageDialog(frame, "No hay impresora de etiquetas seleccionada.");
                return;
            }
            String name = String.valueOf(item);
            PrintService printer = findPrinterByName(name);
            if (printer == null) {
                JOptionPane.showMessageDialog(frame, "La impresora de etiquetas ya no existe.");
                return;
            }
            Path templateDir = Paths.get(labelTemplateDirField.getText().trim()).toAbsolutePath().normalize();
            if (!Files.isDirectory(templateDir)) {
                JOptionPane.showMessageDialog(frame, "No existe la carpeta de plantillas: " + templateDir);
                return;
            }
            Settings s = settingsRef.get();
            s.labelPrinterName = printer.getName();
            s.labelTemplateDir = templateDir.toString();
            saveSettings(s);
            labelPrinterLabel.setText("Impresora de etiquetas: " + printer.getName());
            JOptionPane.showMessageDialog(frame, "Configuracion de etiquetas guardada.");
        });

        JPanel top = new JPanel(new BorderLayout());
        top.add(endpointLabel, BorderLayout.NORTH);
        top.add(statusLabel, BorderLayout.SOUTH);

        JPanel center = new JPanel(new BorderLayout());
        JPanel ticketPanel = new JPanel(new BorderLayout());
        ticketPanel.add(printersCombo, BorderLayout.NORTH);
        ticketPanel.add(selectedLabel, BorderLayout.SOUTH);
        JPanel labelPanel = new JPanel(new BorderLayout());
        labelPanel.add(labelPrintersCombo, BorderLayout.NORTH);
        labelPanel.add(labelTemplateDirField, BorderLayout.CENTER);
        labelPanel.add(labelPrinterLabel, BorderLayout.SOUTH);
        center.add(ticketPanel, BorderLayout.NORTH);
        center.add(labelPanel, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(refreshButton);
        buttons.add(saveButton);
        buttons.add(openWebButton);
        buttons.add(startButton);
        buttons.add(stopButton);
        buttons.add(saveLabelButton);

        frame.setLayout(new BorderLayout());
        frame.add(top, BorderLayout.NORTH);
        frame.add(center, BorderLayout.CENTER);
        frame.add(buttons, BorderLayout.SOUTH);

        loadPrintersIntoCombo(printersCombo);
        loadLabelPrintersIntoCombo(labelPrintersCombo);
        frame.setVisible(true);
    }

    private static void loadPrintersIntoCombo(JComboBox<String> combo) {
        combo.removeAllItems();
        for (String name : listPrinterNames()) {
            combo.addItem(name);
        }
        String selected = selectedPrinter.get();
        if (selected != null && !selected.isBlank()) {
            combo.setSelectedItem(selected);
        }
    }

    private static void loadLabelPrintersIntoCombo(JComboBox<String> combo) {
        combo.removeAllItems();
        for (String name : listPrinterNames()) {
            combo.addItem(name);
        }
        String selected = asString(settingsRef.get().labelPrinterName, "");
        if (!selected.isBlank()) {
            combo.setSelectedItem(selected);
        }
    }

    private static void sendTestPrint() {
        try {
            String selected = selectedPrinter.get();
            PrintService printer = findPrinterByName(selected);
            if (printer == null) {
                notifyTray("Sin impresora", "Selecciona una impresora primero.");
                return;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (EscPos escpos = new EscPos(out)) {
                escpos.initializePrinter();
                escpos.setCharsetName("Cp850");
                Style style = new Style().setJustification(EscPosConst.Justification.Left_Default).setBold(false);
                Style title = new Style().setFontSize(Style.FontSize._2, Style.FontSize._2).setJustification(EscPosConst.Justification.Center);
                Style subtitle = new Style(escpos.getStyle()).setBold(true).setUnderline(Style.Underline.OneDotThick);
                escpos.writeLF(title, "PRUEBA ESC/POS");
                escpos.writeLF(subtitle, "Servicio local activo");
                escpos.writeLF(style, "127.0.0.1:5001");
                escpos.writeLF(style, "----");
                escpos.writeLF(style, "Impresion OK");
                 escpos.feed(5);
                 escpos.cut(EscPos.CutMode.PART);
            }
            printEscPos(printer, out.toByteArray());
            notifyTray("Prueba enviada", "Se envio ticket de prueba a: " + printer.getName());
        } catch (Exception e) {
            notifyTray("Error de impresion", e.getMessage());
        }
    }

    private static void showPrintQueueDialog() {
        SwingUtilities.invokeLater(() -> {
            String selected = selectedPrinter.get();
            PrintService printer = findPrinterByName(selected);
            if (printer == null) {
                JOptionPane.showMessageDialog(null, "No hay impresora seleccionada.");
                return;
            }
            String info = getQueueSummary(printer);
            JOptionPane.showMessageDialog(null, info, "Cola de impresion", JOptionPane.INFORMATION_MESSAGE);
        });
    }

    private static String getQueueSummary(PrintService printer) {
        PrintServiceAttributeSet attrs = printer.getAttributes();
        QueuedJobCount queued = (QueuedJobCount) attrs.get(QueuedJobCount.class);
        PrinterIsAcceptingJobs accepting = (PrinterIsAcceptingJobs) attrs.get(PrinterIsAcceptingJobs.class);
        int queue = queued == null ? -1 : queued.getValue();
        String queueText = queue >= 0 ? String.valueOf(queue) : "No disponible";
        String acceptText = accepting == null ? "No disponible" : accepting.toString();
        return "Impresora: " + printer.getName() + "\nTrabajos en cola: " + queueText + "\nEstado: " + acceptText;
    }

    private static Settings loadSettings() {
        ensureAppDataDir();
        Settings settings = new Settings();
        settings.startServiceOnLaunch = true;
        settings.startWithWindows = false;
        settings.labelPrinterName = "";
        settings.labelTemplateDir = defaultLabelTemplateDir().toString();
        if (!Files.exists(SETTINGS_FILE)) {
            return settings;
        }
        try {
            Properties p = new Properties();
            p.load(Files.newBufferedReader(SETTINGS_FILE, StandardCharsets.UTF_8));
            settings.startServiceOnLaunch = Boolean.parseBoolean(p.getProperty("startServiceOnLaunch", "true"));
            settings.startWithWindows = Boolean.parseBoolean(p.getProperty("startWithWindows", "false"));
            settings.labelPrinterName = p.getProperty("labelPrinterName", "");
            settings.labelTemplateDir = p.getProperty("labelTemplateDir", defaultLabelTemplateDir().toString());
            return settings;
        } catch (IOException e) {
            return settings;
        }
    }

    private static void saveSettings(Settings settings) {
        try {
            ensureAppDataDir();
            Properties p = new Properties();
            p.setProperty("startServiceOnLaunch", String.valueOf(settings.startServiceOnLaunch));
            p.setProperty("startWithWindows", String.valueOf(settings.startWithWindows));
            p.setProperty("labelPrinterName", asString(settings.labelPrinterName, ""));
            p.setProperty("labelTemplateDir", asString(settings.labelTemplateDir, defaultLabelTemplateDir().toString()));
            try (OutputStream os = Files.newOutputStream(SETTINGS_FILE)) {
                p.store(os, "Ticket Printer settings");
            }
            settingsRef.set(settings);
        } catch (IOException e) {
            throw new RuntimeException("No se pudo guardar settings", e);
        }
    }

    private static void setStartWithWindows(boolean enabled) throws IOException {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            throw new IOException("No se encontro APPDATA.");
        }
        Path startupDir = Paths.get(appData, "Microsoft", "Windows", "Start Menu", "Programs", "Startup");
        Files.createDirectories(startupDir);
        Path cmdPath = startupDir.resolve("TicketPrinterServer.cmd");

        if (!enabled) {
            Files.deleteIfExists(cmdPath);
            return;
        }

        Path launchTarget = resolveLaunchTarget();
        String script;
        if (launchTarget.toString().toLowerCase(Locale.ROOT).endsWith(".exe")) {
            script = "@echo off\r\n" +
                "start \"\" \"" + launchTarget + "\"\r\n";
        } else {
            String javaw = Paths.get(System.getProperty("java.home"), "bin", "javaw.exe").toString();
            script = "@echo off\r\n" +
                "start \"\" \"" + javaw + "\" -jar \"" + launchTarget + "\"\r\n";
        }
        Files.writeString(cmdPath, script, StandardCharsets.UTF_8);
    }

    private static Path resolveLaunchTarget() throws IOException {
        String command = ProcessHandle.current().info().command().orElse("").trim();
        if (!command.isBlank()) {
            Path cmdPath = Paths.get(command);
            if (Files.exists(cmdPath) && command.toLowerCase(Locale.ROOT).endsWith(".exe")
                && !command.toLowerCase(Locale.ROOT).endsWith("java.exe")
                && !command.toLowerCase(Locale.ROOT).endsWith("javaw.exe")) {
                return cmdPath.toAbsolutePath().normalize();
            }
        }
        String classPath = System.getProperty("java.class.path", "");
        if (!classPath.isBlank()) {
            String first = classPath.split(";")[0].trim();
            if (!first.isBlank()) {
                Path jarPath = Paths.get(first);
                if (Files.exists(jarPath)) {
                    return jarPath.toAbsolutePath().normalize();
                }
            }
        }
        throw new IOException("No se pudo resolver ruta de arranque para inicio con Windows.");
    }

    static class Settings {
        boolean startServiceOnLaunch;
        boolean startWithWindows;
        String labelPrinterName;
        String labelTemplateDir;
    }

    private static Map<String, Object> printBrotherBpacLabel(Map<String, Object> payload) throws Exception {
        Path templatePath = resolveBrotherTemplatePath(payload);
        if (!Files.exists(templatePath)) {
            throw new IllegalArgumentException("No existe la plantilla Brother: " + templatePath);
        }

        String printerName = asString(payload.get("printerName"), "").trim();
        if (printerName.isBlank()) {
            printerName = asString(settingsRef.get().labelPrinterName, "").trim();
        }
        if (printerName == null || printerName.isBlank()) {
            throw new IllegalArgumentException("Falta 'printerName' o configura la impresora de etiquetas en /label/brother/config.");
        }
        if (findPrinterByName(printerName) == null) {
            throw new IllegalArgumentException("Impresora de etiquetas no encontrada: " + printerName);
        }

        Object fieldsObject = payload.get("fields");
        if (fieldsObject == null) {
            fieldsObject = payload.get("objects");
        }
        if (!(fieldsObject instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Falta 'fields' como objeto { \"NombreObjeto\": \"valor\" }.");
        }

        int copies = asInt(payload.get("copies"), 1);
        if (copies < 1) {
            throw new IllegalArgumentException("'copies' debe ser mayor o igual a 1.");
        }

        boolean ignoreMissingObjects = asBoolean(payload.get("ignoreMissingObjects"), false);
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("templatePath", templatePath.toAbsolutePath().normalize().toString());
        command.put("printerName", printerName);
        command.put("copies", copies);
        command.put("ignoreMissingObjects", ignoreMissingObjects);
        command.put("fields", fieldsObject);

        ensureAppDataDir();
        Path scriptPath = ensureBrotherBpacScript();
        Path inputPath = Files.createTempFile(APP_DATA_DIR, "brother-label-", ".json");
        try {
            Files.writeString(inputPath, Json.stringify(command), StandardCharsets.UTF_8);
            ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                scriptPath.toString(),
                "-InputJsonPath",
                inputPath.toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String stdout;
            try (InputStream out = process.getInputStream()) {
                stdout = new String(out.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException(stdout.isBlank() ? "Error ejecutando Brother b-PAC." : stdout);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("printer", printerName);
            result.put("templatePath", command.get("templatePath"));
            result.put("templateName", templatePath.getFileName().toString());
            result.put("copies", copies);
            result.put("sdk", "Brother b-PAC");
            result.put("output", stdout);
            return result;
        } finally {
            try {
                Files.deleteIfExists(inputPath);
            } catch (IOException ignored) {
            }
        }
    }

    private static Path resolveBrotherTemplatePath(Map<String, Object> payload) {
        String templatePath = asString(payload.get("templatePath"), "").trim();
        if (!templatePath.isBlank()) {
            return Paths.get(templatePath).toAbsolutePath().normalize();
        }

        String templateName = asString(payload.get("templateName"), "").trim();
        if (templateName.isBlank()) {
            templateName = asString(payload.get("template"), "").trim();
        }
        if (templateName.isBlank()) {
            throw new IllegalArgumentException("Falta 'templateName' o 'templatePath' para la etiqueta Brother.");
        }
        if (!templateName.toLowerCase(Locale.ROOT).endsWith(".lbx")) {
            templateName = templateName + ".lbx";
        }
        Path base = Paths.get(asString(settingsRef.get().labelTemplateDir, defaultLabelTemplateDir().toString()));
        Path resolved = base.resolve(templateName).normalize();
        if (!resolved.toAbsolutePath().startsWith(base.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Nombre de plantilla invalido.");
        }
        return resolved;
    }

    private static Path defaultLabelTemplateDir() {
        Path common = Paths.get("C:\\Etiquetas");
        return Files.exists(common) ? common : APP_DATA_DIR.resolve("templates");
    }

    private static List<String> listBrotherTemplateNames() {
        List<String> names = new ArrayList<>();
        Path dir = Paths.get(asString(settingsRef.get().labelTemplateDir, defaultLabelTemplateDir().toString()));
        if (!Files.isDirectory(dir)) {
            return names;
        }
        try (var stream = Files.list(dir)) {
            stream
                .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".lbx"))
                .forEach(p -> names.add(p.getFileName().toString()));
        } catch (IOException ignored) {
        }
        return names;
    }

    private static Path ensureBrotherBpacScript() throws IOException {
        ensureAppDataDir();
        Path scriptPath = APP_DATA_DIR.resolve("brother-bpac-print.ps1");
        Files.writeString(scriptPath, brotherBpacScript(), StandardCharsets.UTF_8);
        return scriptPath;
    }

    private static String brotherBpacScript() {
        return """
param(
    [Parameter(Mandatory = $true)]
    [string] $InputJsonPath
)

$ErrorActionPreference = 'Stop'
$payload = Get-Content -Raw -Encoding UTF8 $InputJsonPath | ConvertFrom-Json

if (-not $payload.templatePath) {
    throw 'Falta templatePath.'
}
if (-not (Test-Path -LiteralPath $payload.templatePath)) {
    throw "No existe la plantilla: $($payload.templatePath)"
}
if (-not $payload.fields) {
    throw 'Falta fields.'
}

$doc = New-Object -ComObject bpac.Document
try {
    if (-not $doc.Open($payload.templatePath)) {
        throw "Brother b-PAC no pudo abrir la plantilla: $($payload.templatePath)"
    }

    if ($payload.printerName) {
        if (-not $doc.SetPrinter($payload.printerName, $true)) {
            throw "Brother b-PAC no pudo seleccionar la impresora: $($payload.printerName)"
        }
    }

    foreach ($property in $payload.fields.PSObject.Properties) {
        $object = $doc.GetObject($property.Name)
        if ($null -eq $object) {
            if ($payload.ignoreMissingObjects) {
                continue
            }
            throw "La plantilla no contiene el objeto: $($property.Name)"
        }
        $object.Text = [string] $property.Value
    }

    $copies = 1
    if ($payload.copies) {
        $copies = [int] $payload.copies
    }

    $doc.StartPrint('', 0) | Out-Null
    $doc.PrintOut($copies, 0) | Out-Null
    $doc.EndPrint() | Out-Null
    Write-Output "OK"
}
finally {
    if ($null -ne $doc) {
        try { $doc.Close() | Out-Null } catch {}
        [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($doc)
    }
}
""";
    }

    private static String loadSelectedPrinter() {
        try {
            if (Files.exists(SELECTED_PRINTER_FILE)) {
                return Files.readString(SELECTED_PRINTER_FILE, StandardCharsets.UTF_8).trim();
            }
        } catch (IOException ignored) {
        }
        return "";
    }

    private static void saveSelectedPrinter(String name) {
        try {
            ensureAppDataDir();
            Files.writeString(SELECTED_PRINTER_FILE, name, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("No se pudo guardar la impresora seleccionada", e);
        }
    }

    private static List<String> listPrinterNames() {
        List<String> names = new ArrayList<>();
        PrintService[] services = PrintServiceLookup.lookupPrintServices(null, null);
        for (PrintService service : services) {
            names.add(service.getName());
        }
        return names;
    }

    private static PrintService findPrinterByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        PrintService[] services = PrintServiceLookup.lookupPrintServices(null, null);
        for (PrintService service : services) {
            if (service.getName().equalsIgnoreCase(name.trim())) {
                return service;
            }
        }
        return null;
    }

    private static void printEscPos(PrintService printer, byte[] bytes) throws Exception {
        DocPrintJob job = printer.createPrintJob();
        Doc doc = new SimpleDoc(bytes, DocFlavor.BYTE_ARRAY.AUTOSENSE, null);
        job.print(doc, null);
    }

    private static byte[] buildEscPosBytes(Map<String, Object> payload) throws Exception {
        if (payload.containsKey("rawBase64")) {
            String b64 = asString(payload.get("rawBase64"), "");
            if (!b64.isBlank()) {
                return Base64.getDecoder().decode(b64);
            }
        }

        PrintOptions options = parsePrintOptions(payload);
        RenderPlan plan = renderByTemplate(payload, options);
        if (plan.lines.isEmpty()) {
            throw new IllegalArgumentException("JSON invalido para '" + options.ticketType + "'.");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (EscPos escpos = new EscPos(out)) {
            escpos.initializePrinter();
            escpos.setCharsetName(options.charset.name());

            if (options.drawer) {
                escpos.write(new byte[] {0x1B, 0x70, 0x00, 0x19, (byte) 0xFA}, 0, 5);
            }

            boolean rendered = false;
            if ("factura".equals(options.ticketType) && hasStructuredData(payload)) {
                renderFacturaDirect(escpos, payload, options);
                rendered = true;
            } else if ("nota_venta".equals(options.ticketType) && hasStructuredData(payload)) {
                renderNotaVentaDirect(escpos, payload, options);
                rendered = true;
            }

            if (!rendered) {
                String align = plan.alignOverride == null ? options.align : plan.alignOverride;
                boolean bold = plan.boldOverride == null ? options.bold : plan.boldOverride.booleanValue();
                Style lineStyle = new Style()
                    .setJustification(toJustification(align))
                    .setBold(bold);
                for (String line : plan.lines) {
                    escpos.writeLF(lineStyle, line);
                }
                if (plan.qrData != null && !plan.qrData.isBlank()) {
                    QRCode qrCode = new QRCode()
                        .setJustification(EscPosConst.Justification.Left_Default)
                        .setSize(options.qrModuleSize)
                        .setErrorCorrectionLevel(options.qrErrorLevel);
                    escpos.write(qrCode, plan.qrData.trim());
                    escpos.writeLF("");
                }
            }

            if (options.feed > 0) {
                escpos.feed(Math.min(options.feed, 10));
            }
            if (options.cut) {
                escpos.cut(EscPos.CutMode.FULL);
            }
        }
        return out.toByteArray();
    }

    private static void renderNotaVentaDirect(EscPos escpos, Map<String, Object> payload, PrintOptions options) throws Exception {
        int width = options.ticketWidth;
        String divider = getDivider(options, width);

        Map<String, Object> data = asMap(payload.get("data"));
        if (data.isEmpty()) {
            data = payload;
        }
        Map<String, Object> cliente = asMap(data.get("Cliente"));
        Map<String, Object> venta = asMap(data.get("Venta"));
        @SuppressWarnings("unchecked")
        List<Object> productos = (List<Object>) venta.getOrDefault("Productos", List.of());

        Style normalLeft = new Style().setJustification(EscPosConst.Justification.Left_Default).setBold(false);
        Style boldLeft = new Style().setJustification(EscPosConst.Justification.Left_Default).setBold(true);
        Style boldCenter = new Style().setJustification(EscPosConst.Justification.Center).setBold(true);
        Style title = new Style().setFontSize(Style.FontSize._2, Style.FontSize._2).setJustification(EscPosConst.Justification.Center).setFontName(Style.FontName.Font_B).setBold(true);
        
        writeWrapped(escpos, title, asString(data.get("Empresa"), ""), width);
        writeWrapped(escpos, normalLeft, asString(data.get("Razon_Social"), ""), width);
        writeWrapped(escpos, normalLeft, "RFC: " + asString(data.get("Rfc"), ""), width);
        writeWrapped(escpos, normalLeft, asString(data.get("Direccion"), ""), width);
        writeWrapped(escpos, normalLeft, "TEL: " + asString(data.get("Telefono"), ""), width);
        escpos.writeLF(normalLeft, divider);

        writeWrapped(escpos, normalLeft, "Folio: " + asString(venta.get("Serie"), "") + "-" + asString(venta.get("Folio"), ""), width);
        writeWrapped(escpos, normalLeft, "Fecha: " + asString(venta.get("Fecha"), ""), width);
        writeWrapped(escpos, normalLeft, "Cliente: " + asString(cliente.get("Nombre"), ""), width);
        writeWrapped(escpos, normalLeft, "RFC: " + asString(cliente.get("Rfc"), ""), width);
        escpos.writeLF(normalLeft, divider);
        escpos.writeLF(boldLeft, "Cant / Clave / Descripcion / P.Unit / Importe");
        escpos.writeLF(normalLeft, divider);

        for (Object obj : productos) {
            Map<String, Object> p = asMap(obj);
            String cantidad = asString(p.get("Cantidad"), "1");
            String clave = asString(p.get("Clave"), "");
            String nombre = asString(p.get("Nombre"), "");
            String precio = asString(p.get("Precio"), "0.00");
            String importe = asString(p.get("Importe"), "0.00");
            if (nombre.isBlank()) {
                nombre = clave;
            }
            writeWrapped(escpos, normalLeft, cantidad + " x " + nombre, width);
            if (!clave.isBlank() && !clave.equals(nombre)) {
                writeWrapped(escpos, normalLeft, "Clave: " + clave, width);
            }
            escpos.writeLF(normalLeft, twoCol("P.Unit: " + precio, "Imp: " + importe, width));
            escpos.writeLF(normalLeft, repeat(".", width));
        }

        escpos.writeLF(normalLeft, divider);
        escpos.writeLF(normalLeft, twoCol("Subtotal", asString(venta.get("SubTotal"), "0.00"), width));
        escpos.writeLF(normalLeft, twoCol("Descuento", asString(venta.get("Descuento"), "0.00"), width));
        escpos.writeLF(normalLeft, twoCol("Impuestos", asString(venta.get("Impuestos"), "0.00"), width));
        escpos.writeLF(boldLeft, twoCol("Total", asString(venta.get("Total"), "0.00"), width));
        escpos.writeLF(normalLeft, twoCol("Cambio", asString(venta.get("Cambio"), "0.00"), width));
        escpos.writeLF(normalLeft, divider);
        writeWrapped(escpos, normalLeft, asString(venta.get("CantLetra"), ""), width);
        escpos.writeLF(normalLeft, divider);
        writeWrapped(escpos, normalLeft, asString(venta.get("TkFooter"), ""), width);
        writeWrapped(escpos, normalLeft, asString(venta.get("SitioWeb"), ""), width);
        escpos.write("");
        BarCode barcode = new BarCode();
        escpos.write(barcode, asString(venta.get("Serie"), "") + asString(venta.get("Folio"), ""));
        escpos.writeLF(boldCenter, "");
        escpos.feed(2);
        escpos.close();
    }

    private static void renderFacturaDirect(EscPos escpos, Map<String, Object> payload, PrintOptions options) throws Exception {
        int width = options.ticketWidth;
        String divider = getDivider(options, width);

        Map<String, Object> data = asMap(payload.get("data"));
        if (data.isEmpty()) {
            data = payload;
        }
        Map<String, Object> cliente = asMap(data.get("Cliente"));
        Map<String, Object> venta = asMap(data.get("Venta"));
        @SuppressWarnings("unchecked")
        List<Object> productos = (List<Object>) venta.getOrDefault("Productos", List.of());

        Style normalLeft = new Style().setJustification(EscPosConst.Justification.Left_Default).setBold(false);
        Style boldLeft = new Style().setJustification(EscPosConst.Justification.Left_Default).setBold(true);
        Style center = new Style().setJustification(EscPosConst.Justification.Center).setBold(false);
        Style small = new Style().setJustification(EscPosConst.Justification.Left_Default).setBold(false).setFontSize(Style.FontSize._1, Style.FontSize._1);
        // Style boldCenter = new Style().setJustification(EscPosConst.Justification.Center).setBold(true);
        Style title = new Style().setFontSize(Style.FontSize._2, Style.FontSize._2).setJustification(EscPosConst.Justification.Center).setFontName(Style.FontName.Font_B).setBold(true);
        String empresa = asString(data.get("Empresa"), "");
        if (!empresa.isBlank()) {
            escpos.writeLF(title, empresa);
        }
        String razon = asString(data.get("Razon_Social"), "");
        if (!razon.isBlank()) {
            escpos.writeLF(center, razon);
        }
        String rfc = asString(data.get("Rfc"), "");
        if (!rfc.isBlank()) {
            escpos.writeLF(center, "R.F.C: " + rfc);
        }
        String direccion = asString(data.get("Direccion"), "");
        if (!direccion.isBlank()) {
            writeWrapped(escpos, center, direccion, width);
        }
        String telefono = asString(data.get("Telefono"), "");
        if (!telefono.isBlank()) {
            escpos.writeLF(center, "Telefono: " + telefono);
        }
        String regimen = asString(data.get("Reg_Fiscal"), "");
        if (!regimen.isBlank()) {
            writeWrapped(escpos, center, "Regimen Fiscal: " + regimen, width);
        }
        escpos.writeLF(normalLeft, divider);

        writeWrapped(escpos, normalLeft, "Cliente: " + asString(cliente.get("Nombre"), ""), width);
        writeWrapped(escpos, normalLeft, "RFC: " + asString(cliente.get("Rfc"), ""), width);
        writeWrapped(escpos, normalLeft, "Regimen Fiscal: " + asString(cliente.get("Reg_Fiscal"), ""), width);
        writeWrapped(escpos, normalLeft, "CP: " + asString(cliente.get("CP"), ""), width);
        escpos.writeLF(normalLeft, divider);

        writeWrapped(escpos, normalLeft, "Serie/Folio: " + asString(venta.get("Serie"), "") + "-" + asString(venta.get("Folio"), ""), width);
        writeWrapped(escpos, normalLeft, "UUID: " + asString(venta.get("Folio Fiscal"), ""), width);
        writeWrapped(escpos, normalLeft, "Fecha Emision: " + asString(firstPresentValue(venta, "Fecha y hora de Emision", "Fecha y hora de Emisión"), ""), width);
        writeWrapped(escpos, normalLeft, "Fecha Certificacion: " + asString(firstPresentValue(venta, "Fecha y hora de Certificacion", "Fecha y hora de Certificación"), ""), width);
        writeWrapped(escpos, normalLeft, "Moneda: " + asString(venta.get("Moneda"), ""), width);
        writeWrapped(escpos, normalLeft, "Metodo de Pago: " + asString(firstPresentValue(venta, "Metodo de Pago", "Método de Pago"), ""), width);
        writeWrapped(escpos, normalLeft, "Forma de Pago: " + asString(venta.get("Forma de Pago"), ""), width);
        writeWrapped(escpos, normalLeft, "Uso CFDI: " + asString(venta.get("Uso CFDI"), ""), width);
        escpos.feed(1);
        escpos.writeLF(normalLeft, "Cant, / Unidad / P. Unit / Importe");
        escpos.writeLF(normalLeft, divider);
        for (Object obj : productos) {
            Map<String, Object> p = asMap(obj);
            String cantidad = asString(p.get("Cantidad"), "1");
            String nombre = asString(p.get("Nombre"), "");
            String clave = asString(p.get("Clave"), "");
            String importe = asString(p.get("Importe"), "0.00");
            writeWrapped(escpos, normalLeft, cantidad + " x " + nombre, width);
            writeWrapped(escpos, normalLeft, "Clave: " + clave, width);
            escpos.writeLF(normalLeft, rightText(importe, width));
        }

        escpos.writeLF(normalLeft, divider);
        escpos.writeLF(normalLeft, twoCol("Subtotal",  asString(venta.get("Subtotal"), "0.00"), width));
        escpos.writeLF(normalLeft, twoCol("Impuestos",  asString(venta.get("Impuestos"), "0.00"), width));
        escpos.writeLF(boldLeft, twoCol("Total",  asString(venta.get("Total"), "0.00"), width));
        escpos.writeLF(normalLeft, divider);
        writeWrapped(escpos, normalLeft, asString(venta.get("CantLetra"), ""), width);
        escpos.writeLF(normalLeft, divider);
        escpos.writeLF(boldLeft, "Sello Digital del CFDI:");
        escpos.writeLF(small, asString(venta.get("Sello Digital del CFDI"), ""));
        escpos.writeLF(boldLeft, "Sello del SAT:");
        escpos.writeLF(small, asString(venta.get("Sello Digital del SAT"), ""));
        escpos.writeLF(boldLeft, "Cadena Original del Complemento de Certificación Digital del SAT:");
        escpos.writeLF(small, asString(venta.get("COCCSAT"), ""));
        escpos.feed(1);
        String qrText = asString(firstPresentValue(venta, "Qr", "QR"), "");
        if (!qrText.isBlank()) {
            escpos.writeLF(center, "Escanea QR para validar CFDI");
            escpos.feed(1);
            QRCode qrCode = new QRCode()
                .setJustification(EscPosConst.Justification.Center)
                .setSize(options.qrModuleSize)
                .setErrorCorrectionLevel(options.qrErrorLevel);
            escpos.write(qrCode, qrText.trim());
            escpos.writeLF("");
            escpos.writeLF(small, "Este documento es una representación impresa de un CFDI 4.0");
        }
        escpos.feed(5).cut(EscPos.CutMode.FULL);
        escpos.close();
    }

    private static void writeWrapped(EscPos escpos, Style style, String text, int width) throws Exception {
        List<String> tmp = new ArrayList<>();
        appendWrapped(tmp, text, width);
        for (String line : tmp) {
            escpos.writeLF(style, line);
        }
    }

    private static String detectTicketType(Map<String, Object> payload) {
        String type = asString(payload.get("ticketType"), "");
        if (type.isBlank()) {
            type = asString(payload.get("tipo"), "");
        }
        if (type.isBlank()) {
            type = asString(payload.get("type"), "");
        }
        if (type.isBlank()) {
            return "nota_venta";
        }
        type = type.trim().toLowerCase(Locale.ROOT);
        if ("factura".equals(type) || "nota_venta".equals(type)) {
            return type;
        }
        return "nota_venta";
    }

    private static PrintOptions parsePrintOptions(Map<String, Object> payload) {
        PrintOptions options = new PrintOptions();
        options.ticketType = detectTicketType(payload);
        options.align = asString(payload.get("align"), "left").toLowerCase(Locale.ROOT);
        options.bold = asBoolean(payload.get("bold"), false);
        options.cut = asBoolean(payload.get("cut"), true);
        options.drawer = asBoolean(payload.get("drawer"), false);
        options.feed = asInt(payload.get("feed"), 3);
        options.ticketWidth = parseTicketWidth(payload);
        options.designVersion = parseDesignVersion(payload);
        options.qrModuleSize = clamp(asInt(payload.get("qrSize"), 4), 2, 10);
        options.qrErrorLevel = parseQrErrorLevel(asString(payload.get("qrErrorLevel"), "L"));

        String charsetName = asString(payload.get("charset"), "Cp850");
        try {
            options.charset = Charset.forName(charsetName);
        } catch (Exception e) {
            options.charset = Charset.forName("Cp850");
        }
        return options;
    }

    private static RenderPlan renderByTemplate(Map<String, Object> payload, PrintOptions options) {
        RenderPlan plan = new RenderPlan();
        if ("factura".equals(options.ticketType)) {
            plan.lines = buildFacturaLines(payload, options);
            plan.alignOverride = "left";
            plan.boldOverride = Boolean.FALSE;
            Map<String, Object> data = asMap(payload.get("data"));
            if (data.isEmpty()) {
                data = payload;
            }
            Map<String, Object> venta = asMap(data.get("Venta"));
            plan.qrData = asString(firstPresentValue(venta, "Qr", "QR"), "");
            return plan;
        }
        if ("nota_venta".equals(options.ticketType) && hasStructuredData(payload)) {
            plan.lines = buildNotaVentaLines(payload, options);
            plan.alignOverride = "left";
            plan.boldOverride = Boolean.FALSE;
            return plan;
        }

        plan.lines = new ArrayList<>();
        Object linesObj = payload.get("lines");
        if (linesObj instanceof List<?>) {
            for (Object item : (List<?>) linesObj) {
                plan.lines.add(item == null ? "" : String.valueOf(item));
            }
        } else {
            String single = asString(payload.get("text"), "");
            if (!single.isBlank()) {
                plan.lines.add(single);
            }
        }
        return plan;
    }

    private static int parseTicketWidth(Map<String, Object> payload) {
        int width = asInt(payload.get("ticketWidth"), 42);
        if (width == 32 || width == 42 || width == 48) {
            return width;
        }
        return clamp(width, 24, 72);
    }

    private static String parseDesignVersion(Map<String, Object> payload) {
        String value = asString(payload.get("designVersion"), "v1").trim();
        return value.isBlank() ? "v1" : value.toLowerCase(Locale.ROOT);
    }

    private static QRCode.QRErrorCorrectionLevel parseQrErrorLevel(String level) {
        String v = level == null ? "M" : level.trim().toUpperCase(Locale.ROOT);
        return switch (v) {
            case "L" -> QRCode.QRErrorCorrectionLevel.QR_ECLEVEL_L;
            case "Q" -> QRCode.QRErrorCorrectionLevel.QR_ECLEVEL_Q;
            case "H" -> QRCode.QRErrorCorrectionLevel.QR_ECLEVEL_H;
            default -> QRCode.QRErrorCorrectionLevel.QR_ECLEVEL_M_Default;
        };
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static EscPosConst.Justification toJustification(String align) {
        if ("center".equalsIgnoreCase(align)) {
            return EscPosConst.Justification.Center;
        }
        if ("right".equalsIgnoreCase(align)) {
            return EscPosConst.Justification.Right;
        }
        return EscPosConst.Justification.Left_Default;
    }

    private static boolean hasStructuredData(Map<String, Object> payload) {
        Map<String, Object> data = asMap(payload.get("data"));
        return !data.isEmpty() && data.containsKey("Venta");
    }

    private static List<String> buildNotaVentaLines(Map<String, Object> payload, PrintOptions options) {
        int width = options.ticketWidth;
        String divider = getDivider(options, width);
        List<String> lines = new ArrayList<>();

        Map<String, Object> data = asMap(payload.get("data"));
        if (data.isEmpty()) {
            data = payload;
        }
        Map<String, Object> cliente = asMap(data.get("Cliente"));
        Map<String, Object> venta = asMap(data.get("Venta"));
        @SuppressWarnings("unchecked")
        List<Object> productos = (List<Object>) venta.getOrDefault("Productos", List.of());

        lines.add(centerText("NOTA DE VENTA", width));
        lines.add(divider);
        appendWrapped(lines, asString(data.get("Empresa"), ""), width);
        appendWrapped(lines, asString(data.get("Razon_Social"), ""), width);
        appendWrapped(lines, "RFC: " + asString(data.get("Rfc"), ""), width);
        appendWrapped(lines, asString(data.get("Direccion"), ""), width);
        appendWrapped(lines, "TEL: " + asString(data.get("Telefono"), ""), width);
        lines.add(divider);

        appendWrapped(lines, "Folio: " + asString(venta.get("Serie"), "") + "-" + asString(venta.get("Folio"), ""), width);
        appendWrapped(lines, "Fecha: " + asString(venta.get("Fecha"), ""), width);
        appendWrapped(lines, "Cliente: " + asString(cliente.get("Nombre"), ""), width);
        appendWrapped(lines, "RFC: " + asString(cliente.get("Rfc"), ""), width);
        lines.add(divider);
        lines.add("PRODUCTOS");
        lines.add(divider);

        for (Object obj : productos) {
            Map<String, Object> p = asMap(obj);
            String cantidad = asString(p.get("Cantidad"), "1");
            String clave = asString(p.get("Clave"), "");
            String nombre = asString(p.get("Nombre"), "");
            String precio = asString(p.get("Precio"), "0.00");
            String importe = asString(p.get("Importe"), "0.00");

            if (nombre.isBlank()) {
                nombre = clave;
            }
            appendWrapped(lines, cantidad + " x " + nombre, width);
            if (!clave.isBlank() && !clave.equals(nombre)) {
                appendWrapped(lines, "Clave: " + clave, width);
            }
            lines.add(twoCol("P.Unit: $" + precio, "Imp: $" + importe, width));
            lines.add(repeat(".", width));
        }

        lines.add(divider);
        lines.add(twoCol("SUBTOTAL", "$ " + asString(venta.get("SubTotal"), "0.00"), width));
        lines.add(twoCol("DESCUENTO", "$ " + asString(venta.get("Descuento"), "0.00"), width));
        lines.add(twoCol("IMPUESTOS", "$ " + asString(venta.get("Impuestos"), "0.00"), width));
        lines.add(twoCol("TOTAL", "$ " + asString(venta.get("Total"), "0.00"), width));
        lines.add(twoCol("CAMBIO", "$ " + asString(venta.get("Cambio"), "0.00"), width));
        lines.add(divider);
        appendWrapped(lines, asString(venta.get("CantLetra"), ""), width);
        lines.add(divider);
        appendWrapped(lines, asString(venta.get("TkFooter"), ""), width);
        appendWrapped(lines, asString(venta.get("SitioWeb"), ""), width);
        lines.add(centerText("GRACIAS POR SU COMPRA", width));
        return lines;
    }

    private static List<String> buildFacturaLines(Map<String, Object> payload, PrintOptions options) {
        int width = options.ticketWidth;
        String divider = getDivider(options, width);
        List<String> lines = new ArrayList<>();

        Map<String, Object> data = asMap(payload.get("data"));
        if (data.isEmpty()) {
            data = payload;
        }
        Map<String, Object> cliente = asMap(data.get("Cliente"));
        Map<String, Object> venta = asMap(data.get("Venta"));
        @SuppressWarnings("unchecked")
        List<Object> productos = (List<Object>) venta.getOrDefault("Productos", List.of());

        String empresa = asString(data.get("Empresa"), "");
        if (!empresa.isBlank()) {
            lines.add("\u001bE\u0001" + centerText(empresa, width) + "\u001bE\u0000");
        }
        String razon = asString(data.get("Razon_Social"), "");
        if (!razon.isBlank()) {
            lines.add(centerText(razon, width));
        }
        String rfc = asString(data.get("Rfc"), "");
        if (!rfc.isBlank()) {
            lines.add(centerText("R.F.C: " + rfc, width));
        }
        String direccion = asString(data.get("Direccion"), "");
        if (!direccion.isBlank()) {
            lines.add(centerText(direccion, width));
        }
        String telefono = asString(data.get("Telefono"), "");
        if (!telefono.isBlank()) {
            lines.add(centerText("Telefono: " + telefono, width));
        }
        String regimen = asString(data.get("Reg_Fiscal"), "");
        if (!regimen.isBlank()) {
            lines.add(centerText("Regimen Fiscal: " + regimen, width));
        }
        lines.add(divider);

        appendWrapped(lines, "Cliente: " + asString(cliente.get("Nombre"), ""), width);
        appendWrapped(lines, "RFC: " + asString(cliente.get("Rfc"), ""), width);
        appendWrapped(lines, "Regimen Fiscal: " + asString(cliente.get("Reg_Fiscal"), ""), width);
        appendWrapped(lines, "CP: " + asString(cliente.get("CP"), ""), width);
        lines.add(divider);

        appendWrapped(lines, "Serie/Folio: " + asString(venta.get("Serie"), "") + "-" + asString(venta.get("Folio"), ""), width);
        appendWrapped(lines, "UUID: " + asString(venta.get("Folio Fiscal"), ""), width);
        appendWrapped(lines, "Fecha Emision: " + asString(firstPresentValue(venta, "Fecha y hora de Emision", "Fecha y hora de Emisión"), ""), width);
        appendWrapped(lines, "Fecha Certificacion: " + asString(firstPresentValue(venta, "Fecha y hora de Certificacion", "Fecha y hora de Certificación"), ""), width);
        appendWrapped(lines, "Moneda: " + asString(venta.get("Moneda"), ""), width);
        appendWrapped(lines, "Metodo de Pago: " + asString(firstPresentValue(venta, "Metodo de Pago", "Método de Pago"), ""), width);
        appendWrapped(lines, "Forma de Pago: " + asString(venta.get("Forma de Pago"), ""), width);
        appendWrapped(lines, "Uso CFDI: " + asString(venta.get("Uso CFDI"), ""), width);
        lines.add(divider);

        lines.add("Cant, / Unidad / P. Unit / Importe");
        lines.add(divider);
        for (Object obj : productos) {
            Map<String, Object> p = asMap(obj);
            String cantidad = asString(p.get("Cantidad"), "1");
            String nombre = asString(p.get("Nombre"), "");
            String clave = asString(p.get("Clave"), "");
            String importe = asString(p.get("Importe"), "0.00");

            appendWrapped(lines, cantidad + " x " + nombre, width);
            appendWrapped(lines, "Clave: " + clave, width);
            lines.add(rightText("$ " + importe, width));
        }

       
        lines.add(twoCol("Subtotal", "$ " + asString(venta.get("Subtotal"), "0.00"), width));
        lines.add(twoCol("Impuestos", "$ " + asString(venta.get("Impuestos"), "0.00"), width));
        lines.add(twoCol("Total", "$ " + asString(venta.get("Total"), "0.00"), width));
        lines.add(divider);
        appendWrapped(lines, asString(venta.get("CantLetra"), ""), width);
        lines.add(divider);
        appendWrapped(lines, "Sello Digital del CFDI:", width);
        appendWrapped(lines, asString(venta.get("Sello Digital del CFDI"), ""), width);
        appendWrapped(lines, "Sello Digital del SAT:", width);
        appendWrapped(lines, asString(venta.get("Sello Digital del SAT"), ""), width);
        appendWrapped(lines, "Cadena Original del CFDI:", width);
        appendWrapped(lines, asString(venta.get("COCCSAT"), ""), width);
        appendWrapped(lines, "Escanea QR para validar CFDI", width);
        return lines;
    }

    private static String getDivider(PrintOptions options, int width) {
        if ("v2".equals(options.designVersion) || "compact".equals(options.designVersion)) {
            return repeat("=", width);
        }
        return repeat("-", width);
    }

    private static Object firstPresentValue(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key) && map.get(key) != null) {
                return map.get(key);
            }
        }
        return null;
    }

    static class PrintOptions {
        String ticketType;
        String align;
        boolean bold;
        boolean cut;
        boolean drawer;
        int feed;
        Charset charset;
        int ticketWidth;
        String designVersion;
        int qrModuleSize;
        QRCode.QRErrorCorrectionLevel qrErrorLevel;
    }

    static class RenderPlan {
        List<String> lines = new ArrayList<>();
        String qrData;
        String alignOverride;
        Boolean boldOverride;
    }

    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return new LinkedHashMap<>();
    }

    private static void appendWrapped(List<String> lines, String text, int width) {
        if (text == null || text.isBlank()) {
            return;
        }
        String normalized = text.replace("\r", " ").replace("\n", " ").trim();
        while (normalized.length() > width) {
            int cut = normalized.lastIndexOf(' ', width);
            if (cut <= 0) {
                cut = width;
            }
            lines.add(normalized.substring(0, cut).trim());
            normalized = normalized.substring(cut).trim();
        }
        if (!normalized.isBlank()) {
            lines.add(normalized);
        }
    }

    private static String repeat(String text, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(text);
        }
        return sb.toString();
    }

    private static String centerText(String text, int width) {
        if (text == null) {
            text = "";
        }
        if (text.length() >= width) {
            return text.substring(0, width);
        }
        int left = (width - text.length()) / 2;
        return repeat(" ", left) + text;
    }

    private static String rightText(String text, int width) {
        if (text == null) {
            text = "";
        }
        if (text.length() >= width) {
            return text;
        }
        return repeat(" ", width - text.length()) + text;
    }

    private static String twoCol(String left, String right, int width) {
        if (left == null) {
            left = "";
        }
        if (right == null) {
            right = "";
        }
        int spaces = width - left.length() - right.length();
        if (spaces < 1) {
            spaces = 1;
        }
        return left + repeat(" ", spaces) + right;
    }

    private static String asString(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static boolean asBoolean(Object value, boolean fallback) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return fallback;
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }


    private static void setJsonHeaders(Headers headers) {
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void sendJson(HttpExchange exchange, int statusCode, Map<String, Object> body) throws IOException {
        byte[] response = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
        setJsonHeaders(exchange.getResponseHeaders());
        exchange.sendResponseHeaders(statusCode, response.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }

    private static void sendHtml(HttpExchange exchange, String html) throws IOException {
        byte[] response = html.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/html; charset=utf-8");
        headers.set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, response.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static boolean handleOptions(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            setJsonHeaders(exchange.getResponseHeaders());
            exchange.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    static class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, Map.of("ok", false, "error", "Metodo no permitido"));
                return;
            }
            String html =
                "<!doctype html><html><head><meta charset='utf-8'><title>Ticket Printer</title>" +
                "<style>body{font-family:Segoe UI,Arial,sans-serif;margin:24px;max-width:760px}section{border:1px solid #ddd;padding:16px;margin:0 0 16px}label{display:block;margin:10px 0 4px}select,input{min-width:360px;padding:6px}button{margin-top:12px;padding:7px 12px}</style>" +
                "</head><body>" +
                "<h2>Ticket Printer</h2>" +
                "<section><h3>Tickets ESC/POS</h3>" +
                "<label>Impresora de tickets</label><select id='printers'></select> <button onclick='saveTicketPrinter()'>Guardar</button>" +
                "<p id='selected'></p></section>" +
                "<section><h3>Etiquetas Brother</h3>" +
                "<label>Impresora de etiquetas</label><select id='labelPrinter'></select>" +
                "<label>Carpeta de plantillas .lbx</label><input id='labelTemplateDir' placeholder='C:\\\\Etiquetas'>" +
                "<p id='labelSelected'></p><p id='templates'></p><button onclick='saveLabelConfig()'>Guardar etiquetas</button></section>" +
                "<script>" +
                "let printers=[];" +
                "function fillSelect(id,selected){const s=document.getElementById(id);s.innerHTML='';printers.forEach(p=>{const o=document.createElement('option');o.value=p;o.textContent=p;if(p===selected){o.selected=true;}s.appendChild(o);});}" +
                "async function load(){const r=await fetch('/printers');const j=await r.json();printers=j.printers||[];fillSelect('printers',j.selected);" +
                "document.getElementById('selected').textContent='Actual: '+(j.selected||'Sin seleccionar');" +
                "const lr=await fetch('/label/brother/config');const lj=await lr.json();fillSelect('labelPrinter',lj.printerName);" +
                "document.getElementById('labelTemplateDir').value=lj.templateDir||'';" +
                "document.getElementById('labelSelected').textContent='Actual: '+(lj.printerName||'Sin seleccionar');" +
                "document.getElementById('templates').textContent='Plantillas: '+((lj.templates||[]).join(', ')||'sin .lbx detectadas');}" +
                "async function saveTicketPrinter(){const name=document.getElementById('printers').value;" +
                "await fetch('/printer/select',{method:'POST',headers:{'Content-Type':'application/json'}," +
                "body:JSON.stringify({name})});await load();alert('Impresora guardada');}" +
                "async function saveLabelConfig(){const printerName=document.getElementById('labelPrinter').value;const templateDir=document.getElementById('labelTemplateDir').value;" +
                "const r=await fetch('/label/brother/config',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({printerName,templateDir})});const j=await r.json();await load();alert(j.ok?'Configuracion de etiquetas guardada':j.error);}" +
                "load();" +
                "</script></body></html>";
            sendHtml(exchange, html);
        }
    }

    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) {
                return;
            }
            sendJson(exchange, 200, Map.of("ok", true, "service", "ticket-printer", "selected", selectedPrinter.get()));
        }
    }

    static class PrintersHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) {
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, Map.of("ok", false, "error", "Metodo no permitido"));
                return;
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ok", true);
            response.put("selected", selectedPrinter.get());
            response.put("printers", listPrinterNames());
            sendJson(exchange, 200, response);
        }
    }

    static class QueueHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) {
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, Map.of("ok", false, "error", "Metodo no permitido"));
                return;
            }
            String selected = selectedPrinter.get();
            PrintService printer = findPrinterByName(selected);
            if (printer == null) {
                sendJson(exchange, 400, Map.of("ok", false, "error", "No hay impresora seleccionada"));
                return;
            }
            PrintServiceAttributeSet attrs = printer.getAttributes();
            QueuedJobCount queued = (QueuedJobCount) attrs.get(QueuedJobCount.class);
            PrinterIsAcceptingJobs accepting = (PrinterIsAcceptingJobs) attrs.get(PrinterIsAcceptingJobs.class);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ok", true);
            response.put("printer", printer.getName());
            response.put("queueCount", queued == null ? null : queued.getValue());
            response.put("acceptingJobs", accepting == null ? null : accepting.toString());
            sendJson(exchange, 200, response);
        }
    }

    static class SelectPrinterHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) {
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, Map.of("ok", false, "error", "Metodo no permitido"));
                return;
            }
            try {
                String body = readBody(exchange);
                Object parsed = Json.parse(body);
                if (!(parsed instanceof Map<?, ?> map)) {
                    sendJson(exchange, 400, Map.of("ok", false, "error", "JSON invalido"));
                    return;
                }
                String name = asString(map.get("name"), "").trim();
                if (name.isBlank()) {
                    sendJson(exchange, 400, Map.of("ok", false, "error", "Falta 'name'"));
                    return;
                }
                PrintService printer = findPrinterByName(name);
                if (printer == null) {
                    sendJson(exchange, 404, Map.of("ok", false, "error", "Impresora no encontrada"));
                    return;
                }
                updateSelectedPrinter(printer.getName());
                sendJson(exchange, 200, Map.of("ok", true, "selected", printer.getName()));
            } catch (Exception e) {
                sendJson(exchange, 500, Map.of("ok", false, "error", e.getMessage()));
            }
        }
    }

    static class BrotherLabelConfigHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) {
                return;
            }
            try {
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendJson(exchange, 200, brotherLabelConfigResponse());
                    return;
                }
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendJson(exchange, 405, Map.of("ok", false, "error", "Metodo no permitido"));
                    return;
                }

                String body = readBody(exchange);
                Object parsed = Json.parse(body);
                if (!(parsed instanceof Map<?, ?> map)) {
                    sendJson(exchange, 400, Map.of("ok", false, "error", "JSON invalido"));
                    return;
                }

                String printerName = asString(map.get("printerName"), "").trim();
                if (printerName.isBlank()) {
                    printerName = asString(map.get("name"), "").trim();
                }
                if (printerName.isBlank()) {
                    sendJson(exchange, 400, Map.of("ok", false, "error", "Falta 'printerName'"));
                    return;
                }
                PrintService printer = findPrinterByName(printerName);
                if (printer == null) {
                    sendJson(exchange, 404, Map.of("ok", false, "error", "Impresora de etiquetas no encontrada"));
                    return;
                }

                String templateDir = asString(map.get("templateDir"), "").trim();
                if (templateDir.isBlank()) {
                    templateDir = asString(map.get("templatesDir"), "").trim();
                }
                if (templateDir.isBlank()) {
                    templateDir = defaultLabelTemplateDir().toString();
                }
                Path dir = Paths.get(templateDir).toAbsolutePath().normalize();
                if (!Files.isDirectory(dir)) {
                    sendJson(exchange, 400, Map.of("ok", false, "error", "No existe la carpeta de plantillas: " + dir));
                    return;
                }

                Settings s = settingsRef.get();
                s.labelPrinterName = printer.getName();
                s.labelTemplateDir = dir.toString();
                saveSettings(s);
                sendJson(exchange, 200, brotherLabelConfigResponse());
            } catch (Exception e) {
                sendJson(exchange, 500, Map.of("ok", false, "error", e.getMessage()));
            }
        }

        private Map<String, Object> brotherLabelConfigResponse() {
            Settings s = settingsRef.get();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ok", true);
            response.put("printerName", asString(s.labelPrinterName, ""));
            response.put("templateDir", asString(s.labelTemplateDir, defaultLabelTemplateDir().toString()));
            response.put("templates", listBrotherTemplateNames());
            response.put("printers", listPrinterNames());
            return response;
        }
    }

    static class PrintTicketHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) {
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, Map.of("ok", false, "error", "Metodo no permitido"));
                return;
            }
            try {
                String selected = selectedPrinter.get();
                PrintService printer = findPrinterByName(selected);
                if (printer == null) {
                    sendJson(exchange, 400, Map.of("ok", false, "error", "No hay impresora seleccionada. Usa /printer/select"));
                    return;
                }

                String body = readBody(exchange);
                Object parsed = Json.parse(body);
                if (!(parsed instanceof Map<?, ?> map)) {
                    sendJson(exchange, 400, Map.of("ok", false, "error", "JSON invalido"));
                    return;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = (Map<String, Object>) map;

                PrintOptions options = parsePrintOptions(payload);
                byte[] bytes = buildEscPosBytes(payload);
                printEscPos(printer, bytes);
                sendJson(exchange, 200, Map.of(
                    "ok", true,
                    "bytes", bytes.length,
                    "printer", printer.getName(),
                    "ticketType", options.ticketType,
                    "ticketWidth", options.ticketWidth,
                    "designVersion", options.designVersion
                ));
            } catch (IllegalArgumentException e) {
                sendJson(exchange, 400, Map.of("ok", false, "error", e.getMessage()));
            } catch (Exception e) {
                sendJson(exchange, 500, Map.of("ok", false, "error", e.getMessage()));
            }
        }
    }

    static class BrotherLabelHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) {
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, Map.of("ok", false, "error", "Metodo no permitido"));
                return;
            }
            try {
                String body = readBody(exchange);
                Object parsed = Json.parse(body);
                if (!(parsed instanceof Map<?, ?> map)) {
                    sendJson(exchange, 400, Map.of("ok", false, "error", "JSON invalido"));
                    return;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = (Map<String, Object>) map;

                Map<String, Object> result = printBrotherBpacLabel(payload);
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("ok", true);
                response.putAll(result);
                sendJson(exchange, 200, response);
            } catch (IllegalArgumentException e) {
                sendJson(exchange, 400, Map.of("ok", false, "error", e.getMessage()));
            } catch (Exception e) {
                sendJson(exchange, 500, Map.of(
                    "ok", false,
                    "error", e.getMessage(),
                    "hint", "Verifica que el driver Brother QL-800 y el SDK Brother b-PAC esten instalados."
                ));
            }
        }
    }

    static class Json {
        public static Object parse(String text) {
            return new Parser(text).parseValue();
        }

        public static String stringify(Object value) {
            StringBuilder sb = new StringBuilder();
            writeJson(value, sb);
            return sb.toString();
        }

        private static void writeJson(Object value, StringBuilder sb) {
            if (value == null) {
                sb.append("null");
                return;
            }
            if (value instanceof String s) {
                sb.append('"').append(escape(s)).append('"');
                return;
            }
            if (value instanceof Number || value instanceof Boolean) {
                sb.append(value);
                return;
            }
            if (value instanceof Map<?, ?> map) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    sb.append('"').append(escape(String.valueOf(entry.getKey()))).append('"').append(':');
                    writeJson(entry.getValue(), sb);
                }
                sb.append('}');
                return;
            }
            if (value instanceof List<?> list) {
                sb.append('[');
                boolean first = true;
                for (Object item : list) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    writeJson(item, sb);
                }
                sb.append(']');
                return;
            }
            sb.append('"').append(escape(String.valueOf(value))).append('"');
        }

        private static String escape(String text) {
            return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        }

        static class Parser {
            private final String s;
            private int i;

            Parser(String s) {
                this.s = s == null ? "" : s;
                this.i = 0;
            }

            Object parseValue() {
                skipWs();
                if (i >= s.length()) {
                    throw new IllegalArgumentException("JSON vacio");
                }
                char c = s.charAt(i);
                if (c == '{') {
                    return parseObject();
                }
                if (c == '[') {
                    return parseArray();
                }
                if (c == '"') {
                    return parseString();
                }
                if (c == 't' || c == 'f') {
                    return parseBoolean();
                }
                if (c == 'n') {
                    return parseNull();
                }
                if (c == '-' || Character.isDigit(c)) {
                    return parseNumber();
                }
                throw new IllegalArgumentException("JSON invalido cerca de: " + preview());
            }

            private Map<String, Object> parseObject() {
                Map<String, Object> map = new LinkedHashMap<>();
                expect('{');
                skipWs();
                if (peek('}')) {
                    expect('}');
                    return map;
                }
                while (true) {
                    skipWs();
                    String key = parseString();
                    skipWs();
                    expect(':');
                    Object value = parseValue();
                    map.put(key, value);
                    skipWs();
                    if (peek('}')) {
                        expect('}');
                        break;
                    }
                    expect(',');
                }
                return map;
            }

            private List<Object> parseArray() {
                List<Object> list = new ArrayList<>();
                expect('[');
                skipWs();
                if (peek(']')) {
                    expect(']');
                    return list;
                }
                while (true) {
                    list.add(parseValue());
                    skipWs();
                    if (peek(']')) {
                        expect(']');
                        break;
                    }
                    expect(',');
                }
                return list;
            }

            private String parseString() {
                expect('"');
                StringBuilder sb = new StringBuilder();
                while (i < s.length()) {
                    char c = s.charAt(i++);
                    if (c == '"') {
                        return sb.toString();
                    }
                    if (c == '\\') {
                        if (i >= s.length()) {
                            throw new IllegalArgumentException("Escape incompleto");
                        }
                        char e = s.charAt(i++);
                        switch (e) {
                            case '"': sb.append('"'); break;
                            case '\\': sb.append('\\'); break;
                            case '/': sb.append('/'); break;
                            case 'b': sb.append('\b'); break;
                            case 'f': sb.append('\f'); break;
                            case 'n': sb.append('\n'); break;
                            case 'r': sb.append('\r'); break;
                            case 't': sb.append('\t'); break;
                            case 'u': sb.append(parseUnicode()); break;
                            default: throw new IllegalArgumentException("Escape invalido: \\" + e);
                        }
                    } else {
                        sb.append(c);
                    }
                }
                throw new IllegalArgumentException("String sin cerrar");
            }

            private char parseUnicode() {
                if (i + 4 > s.length()) {
                    throw new IllegalArgumentException("Unicode incompleto");
                }
                String hex = s.substring(i, i + 4);
                i += 4;
                return (char) Integer.parseInt(hex, 16);
            }

            private Boolean parseBoolean() {
                if (s.startsWith("true", i)) {
                    i += 4;
                    return Boolean.TRUE;
                }
                if (s.startsWith("false", i)) {
                    i += 5;
                    return Boolean.FALSE;
                }
                throw new IllegalArgumentException("Boolean invalido");
            }

            private Object parseNull() {
                if (s.startsWith("null", i)) {
                    i += 4;
                    return null;
                }
                throw new IllegalArgumentException("Token invalido");
            }

            private Number parseNumber() {
                int start = i;
                if (s.charAt(i) == '-') {
                    i++;
                }
                while (i < s.length() && Character.isDigit(s.charAt(i))) {
                    i++;
                }
                if (i < s.length() && s.charAt(i) == '.') {
                    i++;
                    while (i < s.length() && Character.isDigit(s.charAt(i))) {
                        i++;
                    }
                }
                if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                    i++;
                    if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                        i++;
                    }
                    while (i < s.length() && Character.isDigit(s.charAt(i))) {
                        i++;
                    }
                }
                String num = s.substring(start, i);
                if (num.contains(".") || num.contains("e") || num.contains("E")) {
                    return Double.parseDouble(num);
                }
                return Long.parseLong(num);
            }

            private void skipWs() {
                while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                    i++;
                }
            }

            private void expect(char c) {
                skipWs();
                if (i >= s.length() || s.charAt(i) != c) {
                    throw new IllegalArgumentException("Se esperaba '" + c + "' cerca de: " + preview());
                }
                i++;
            }

            private boolean peek(char c) {
                skipWs();
                return i < s.length() && s.charAt(i) == c;
            }

            private String preview() {
                int end = Math.min(s.length(), i + 20);
                return s.substring(i, end);
            }
        }
    }
}
