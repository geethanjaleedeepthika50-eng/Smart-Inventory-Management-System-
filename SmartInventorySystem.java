package com.computershop.inventory;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.*;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sound.sampled.*;

/**
 * =================================================================================
 * SMART COMPUTER SHOP INVENTORY MANAGEMENT SYSTEM (QUANTUMSCAN v3D)
 * =================================================================================
 * Upgraded Features:
 * - Dynamic Synthesized Audio Security Alerts for Low Stock Warning states.
 * - Custom Pure Java PDF Export Engine (Produces real binary %PDF-1.4 files).
 * - Navigation menu styled with high-contrast Black text fonts.
 * - Entire Pricing System localized to Sri Lankan Rupees (Rs. / LKR).
 * - Multi-mode Scanner viewport toggle (Adaptive Barcode & QR Code simulation).
 * - Dedicated Purchase/Sales History Ledger Tab with dynamic visual trend diagrams.
 * - SELF-HEALING DATABASE MIGRATION ENGINE (Fixes missing password_hash and salt columns)
 * =================================================================================
 */
public class SmartInventorySystem extends JFrame {

    private static final String CLOUD_DB_URL = "jdbc:mysql://localhost:3306/computer_shop_db?useSSL=true&allowPublicKeyRetrieval=true";
    private static final String CLOUD_DB_USER = "root";
    private static final String CLOUD_DB_PASS = "1234";

    // Application state
    private static boolean isUsingCloudDB = false;
    private static Connection dbConnection = null;
    private static String loggedInUser = "";
    private static String loggedInRole = ""; // "Admin" or "Management"
    
    // UI Theme Constants (Midnight Dark Premium Theme)
    private static final Color COLOR_BG = new Color(18, 22, 33);
    private static final Color COLOR_CARD_BG = new Color(28, 34, 52);
    private static final Color COLOR_PRIMARY = new Color(81, 92, 230);
    private static final Color COLOR_SECONDARY = new Color(0, 210, 255);
    private static final Color COLOR_ACCENT = new Color(255, 46, 99);
    private static final Color COLOR_TEXT_MAIN = new Color(240, 243, 250);
    private static final Color COLOR_TEXT_MUTED = new Color(140, 153, 185);
    private static final Color COLOR_SUCCESS = new Color(46, 213, 115);
    private static final Font FONT_TITLE = new Font("Segoe UI", Font.BOLD, 22);
    private static final Font FONT_SUBTITLE = new Font("Segoe UI", Font.BOLD, 14);
    private static final Font FONT_BODY = new Font("Segoe UI", Font.PLAIN, 13);

    // Formatters for Sri Lankan Rupees
    private static final DecimalFormat LKR_FORMAT = new DecimalFormat("'Rs.' #,##0.00");

    // Core layout container and views
    private JPanel mainContainer;
    private CardLayout cardLayout;
    private JLabel dbStatusLabel;
    private JLabel userDisplayLabel;
    private Button3D deleteBtn; // Restricted to Admins only

    // Fallback database mock storage
    private static final List<Product> mockProductDatabase = new ArrayList<>();
    private static final List<SystemUser> mockUserDatabase = new ArrayList<>();
    private static final List<SaleRecord> mockSalesLedger = new ArrayList<>();

    // Real-time audio cooling-down flag to prevent sound spam
    private static long lastAlertSoundTime = 0;

    public SmartInventorySystem() {
        setTitle("QuantumScan v3D - Smart Computer Shop Inventory Suite");
        setSize(1380, 880);
        setMinimumSize(new Dimension(1150, 780));
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        getContentPane().setBackground(COLOR_BG);

        // Initialize connections & seeding
        initDatabaseConnection();

        // Setup layouts
        cardLayout = new CardLayout();
        mainContainer = new JPanel(cardLayout);
        mainContainer.setBackground(COLOR_BG);

        // Add Screens
        mainContainer.add(createLoginScreen(), "LOGIN");
        mainContainer.add(createMainDashboardView(), "DASHBOARD");

        add(mainContainer);
        cardLayout.show(mainContainer, "LOGIN");
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            SmartInventorySystem app = new SmartInventorySystem();
            app.setVisible(true);
        });
    }

    // =================================================================================
    // DATABASE & SECURITY LAYER (ROLES SEEDING & SELF-HEALING SCHEMAS)
    // =================================================================================
    
    private void initDatabaseConnection() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            dbConnection = DriverManager.getConnection("jdbc:mysql://localhost:3306/computer_shop_db?useSSL=true&allowPublicKeyRetrieval=true", "root", "1234");
            isUsingCloudDB = true;
            createDatabaseTablesIfNotExist();
        } catch (Exception e) {
            isUsingCloudDB = false;
            loadMockData();
            System.out.println("[DB System] Cloud Database offline. Securely switched to in-memory local state.");
        }
    }

    private void createDatabaseTablesIfNotExist() {
        if (!isUsingCloudDB || dbConnection == null) return;
        try (Statement stmt = dbConnection.createStatement()) {
            
            // 1. Create Users Table safely
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS users ("
                    + "username VARCHAR(50) PRIMARY KEY,"
                    + "role VARCHAR(30) NOT NULL"
                    + ")");

            // SELF-HEALING STEP: Check if 'password_hash' and 'salt' exist. If not, add them automatically.
            DatabaseMetaData metaData = dbConnection.getMetaData();
            
            boolean hasPasswordHashColumn = false;
            boolean hasSaltColumn = false;
            
            try (ResultSet columns = metaData.getColumns(null, null, "users", null)) {
                while (columns.next()) {
                    String columnName = columns.getString("COLUMN_NAME");
                    if ("password_hash".equalsIgnoreCase(columnName)) {
                        hasPasswordHashColumn = true;
                    }
                    if ("salt".equalsIgnoreCase(columnName)) {
                        hasSaltColumn = true;
                    }
                }
            }

            if (!hasPasswordHashColumn) {
                System.out.println("[DB Migration] Adding missing 'password_hash' column to 'users' table.");
                stmt.executeUpdate("ALTER TABLE users ADD COLUMN password_hash VARCHAR(256) NOT NULL DEFAULT ''");
            }
            if (!hasSaltColumn) {
                System.out.println("[DB Migration] Adding missing 'salt' column to 'users' table.");
                stmt.executeUpdate("ALTER TABLE users ADD COLUMN salt VARCHAR(128) NOT NULL DEFAULT ''");
            }

            // 2. Create Inventory Table (Prices configured in LKR)
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS inventory ("
                    + "barcode VARCHAR(100) PRIMARY KEY,"
                    + "name VARCHAR(255) NOT NULL,"
                    + "category VARCHAR(100) NOT NULL,"
                    + "price DOUBLE NOT NULL,"
                    + "stock INT NOT NULL,"
                    + "location VARCHAR(100) DEFAULT 'A1'"
                    + ")");

            // 3. Create Shared Purchase / Sales Ledger table linked to Customer Terminal Checkouts
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS sales_ledger ("
                    + "sale_id INT AUTO_INCREMENT PRIMARY KEY,"
                    + "customer_name VARCHAR(100) NOT NULL,"
                    + "customer_phone VARCHAR(50) NOT NULL,"
                    + "items_sold TEXT NOT NULL,"
                    + "total_amount DOUBLE NOT NULL,"
                    + "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                    + ")");

            // Seed Admin User (admin / admin123)
            ResultSet rsAdmin = stmt.executeQuery("SELECT * FROM users WHERE username = 'admin'");
            if (!rsAdmin.next()) {
                String salt = generateSalt();
                String hash = hashPassword("admin123", salt);
                try (PreparedStatement ps = dbConnection.prepareStatement("INSERT INTO users (username, password_hash, salt, role) VALUES (?, ?, ?, ?)")) {
                    ps.setString(1, "admin");
                    ps.setString(2, hash);
                    ps.setString(3, salt);
                    ps.setString(4, "Admin");
                    ps.executeUpdate();
                }
            } else {
                // Update older plain text passwords to secure salted hashes if found empty
                String checkHash = rsAdmin.getString("password_hash");
                if (checkHash == null || checkHash.trim().isEmpty()) {
                    String salt = generateSalt();
                    String hash = hashPassword("admin123", salt);
                    try (PreparedStatement ps = dbConnection.prepareStatement("UPDATE users SET password_hash = ?, salt = ?, role = ? WHERE username = 'admin'")) {
                        ps.setString(1, hash);
                        ps.setString(2, salt);
                        ps.setString(3, "Admin");
                        ps.executeUpdate();
                    }
                }
            }

            // Seed Management User (manager / manager123)
            ResultSet rsManager = stmt.executeQuery("SELECT * FROM users WHERE username = 'manager'");
            if (!rsManager.next()) {
                String salt = generateSalt();
                String hash = hashPassword("manager123", salt);
                try (PreparedStatement ps = dbConnection.prepareStatement("INSERT INTO users (username, password_hash, salt, role) VALUES (?, ?, ?, ?)")) {
                    ps.setString(1, "manager");
                    ps.setString(2, hash);
                    ps.setString(3, salt);
                    ps.setString(4, "Management");
                    ps.executeUpdate();
                }
            }

            // Seed a Cashier account so that your terminal can log in right away (cashier / cashier123)
            ResultSet rsCashier = stmt.executeQuery("SELECT * FROM users WHERE username = 'cashier'");
            if (!rsCashier.next()) {
                String salt = generateSalt();
                String hash = hashPassword("cashier123", salt);
                try (PreparedStatement ps = dbConnection.prepareStatement("INSERT INTO users (username, password_hash, salt, role) VALUES (?, ?, ?, ?)")) {
                    ps.setString(1, "cashier");
                    ps.setString(2, hash);
                    ps.setString(3, salt);
                    ps.setString(4, "Cashier");
                    ps.executeUpdate();
                }
            }
            
            System.out.println("[DB System] Database Tables and Columns verified & auto-repaired successfully.");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void loadMockData() {
        // Expanded Local database with more computer parts (Localized to LKR / Rs.)
        if (mockProductDatabase.isEmpty()) {
            mockProductDatabase.add(new Product("8806090123456", "Samsung Odyssey G9 Monitor", "Monitors", 389500.00, 12, "Bay-A5"));
            mockProductDatabase.add(new Product("1951220987654", "Intel Core i9-14900K Processor", "CPUs", 185000.00, 3, "Cabinet-C1")); // Low Stock
            mockProductDatabase.add(new Product("4719331312345", "NVIDIA RTX 4090 OC 24GB", "GPUs", 695000.00, 8, "Safe-01"));
            mockProductDatabase.add(new Product("0840006123456", "Corsair Dominator Titanium 64GB", "RAM", 98500.00, 4, "Bay-B3"));  // Low Stock
            mockProductDatabase.add(new Product("0718037891234", "WD Black SN854X NVMe 2TB", "Storage", 58000.00, 50, "Bay-B4"));
            mockProductDatabase.add(new Product("5012345678900", "ASUS ROG Maximus Hero Board", "Motherboards", 165000.00, 2, "Bay-A1")); // Low Stock
            mockProductDatabase.add(new Product("6971596214567", "Razer BlackWidow V4 Pro", "Peripherals", 75000.00, 18, "Bay-D2"));
            mockProductDatabase.add(new Product("0195122114256", "ASUS ROG Strix SCAR 18 Laptop", "Laptops", 1125000.00, 5, "Safe-02")); // Low Stock
            mockProductDatabase.add(new Product("8400066225431", "Lian Li O11 Dynamic EVO Case", "Cases", 48500.00, 15, "Bay-F1"));
            mockProductDatabase.add(new Product("7358585442104", "Corsair iCUE Link H150i AIO", "Coolers", 79000.00, 1, "Bay-C3"));  // Low Stock
            mockProductDatabase.add(new Product("0887276533215", "Synology DiskStation DS923+", "Networking", 225000.00, 6, "Bay-E4"));
        }

        // Mock Users
        if (mockUserDatabase.isEmpty()) {
            String adminSalt = generateSalt();
            String adminHash = hashPassword("admin123", adminSalt);
            mockUserDatabase.add(new SystemUser("admin", adminHash, adminSalt, "Admin"));

            String mgtSalt = generateSalt();
            String mgtHash = hashPassword("manager123", mgtSalt);
            mockUserDatabase.add(new SystemUser("manager", mgtHash, mgtSalt, "Management"));

            String cashierSalt = generateSalt();
            String cashierHash = hashPassword("cashier123", cashierSalt);
            mockUserDatabase.add(new SystemUser("cashier", cashierHash, cashierSalt, "Cashier"));
        }

        // Seeding Mock Sales History
        if (mockSalesLedger.isEmpty()) {
            mockSalesLedger.add(new SaleRecord(1, "Kamal Gunawardena", "0771234567", "Intel Core i9-14900K Processor (x1)", 185000.00, "2026-06-14 10:30:15"));
            mockSalesLedger.add(new SaleRecord(2, "Nisha Perera", "0717654321", "ASUS ROG Maximus Hero Board (x1); Corsair Dominator 64GB (x2)", 362000.00, "2026-06-15 14:12:45"));
            mockSalesLedger.add(new SaleRecord(3, "Shehan Silva", "0759876543", "Samsung Odyssey G9 Monitor (x1)", 389500.00, "2026-06-16 09:05:00"));
            mockSalesLedger.add(new SaleRecord(4, "Fathima Rizwan", "0721112223", "NVIDIA RTX 4090 OC 24GB (x1)", 695000.00, "2026-06-17 17:45:30"));
        }
    }

    private static String generateSalt() {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    private static String hashPassword(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Base64.getDecoder().decode(salt));
            byte[] hashedBytes = digest.digest(password.getBytes());
            return Base64.getEncoder().encodeToString(hashedBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 process mismatch.");
        }
    }

    private boolean authenticateUser(String username, String password) {
        if (!isUsingCloudDB) {
            for (SystemUser su : mockUserDatabase) {
                if (su.username.equalsIgnoreCase(username)) {
                    if (hashPassword(password, su.salt).equals(su.passwordHash)) {
                        loggedInRole = su.role;
                        return true;
                    }
                }
            }
            return false;
        }

        try (PreparedStatement ps = dbConnection.prepareStatement("SELECT password_hash, salt, role FROM users WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String storedHash = rs.getString("password_hash");
                    String storedSalt = rs.getString("salt");
                    if (hashPassword(password, storedSalt).equals(storedHash)) {
                        loggedInRole = rs.getString("role");
                        return true;
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    // =================================================================================
    // NATIVE SYNTHESIZED AUDIO SECURITY ALERTS
    // =================================================================================
    
    /**
     * Synthesizes and plays a dual-tone military-grade warning buzzer.
     */
    private static synchronized void playSecurityBuzzerSound() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastAlertSoundTime < 2000) {
            return;
        }
        lastAlertSoundTime = currentTime;

        new Thread(() -> {
            try {
                float sampleRate = 8000f;
                byte[] buffer = new byte[1600]; // 0.2 second blocks
                AudioFormat format = new AudioFormat(sampleRate, 8, 1, true, false);
                SourceDataLine line = AudioSystem.getSourceDataLine(format);
                line.open(format);
                line.start();

                for (int loop = 0; loop < 3; loop++) {
                    double freq = (loop % 2 == 0) ? 880.0 : 660.0;
                    for (int i = 0; i < buffer.length; i++) {
                        double angle = i / (sampleRate / freq) * 2.0 * Math.PI;
                        buffer[i] = (byte) (Math.sin(angle) * 120.0);
                    }
                    line.write(buffer, 0, buffer.length);
                }

                line.drain();
                line.stop();
                line.close();
            } catch (LineUnavailableException ex) {
                Toolkit.getDefaultToolkit().beep();
            }
        }).start();
    }

    // =================================================================================
    // 3D GRAPHICAL ELEMENT IMPLEMENTATIONS
    // =================================================================================

    static class Panel3D extends JPanel {
        private final int shadowOffset = 6;
        private final int cornerRadius = 16;
        private Color gradientStart = COLOR_CARD_BG;
        private Color gradientEnd = COLOR_CARD_BG.brighter();

        public Panel3D() {
            setOpaque(false);
        }

        public Panel3D(Color start, Color end) {
            setOpaque(false);
            this.gradientStart = start;
            this.gradientEnd = end;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int width = getWidth();
            int height = getHeight();

            for (int i = shadowOffset; i > 0; i--) {
                g2.setColor(new Color(0, 0, 0, (int) (18 * (1.0 - (double) i / shadowOffset))));
                g2.fill(new RoundRectangle2D.Double(2 + (double) i / 2, 2 + i, width - 4 - i, height - 4 - i, cornerRadius, cornerRadius));
            }

            g2.setColor(new Color(36, 45, 68));
            g2.fill(new RoundRectangle2D.Double(1, 1, width - 2 - shadowOffset, height - 2 - shadowOffset, cornerRadius, cornerRadius));

            GradientPaint gradient = new GradientPaint(0, 0, gradientStart, 0, height - shadowOffset, gradientEnd);
            g2.setPaint(gradient);
            g2.fill(new RoundRectangle2D.Double(1, 1, width - 2 - shadowOffset, height - 4 - shadowOffset, cornerRadius, cornerRadius));

            g2.setStroke(new BasicStroke(1.2f));
            g2.setColor(new Color(255, 255, 255, 25));
            g2.draw(new RoundRectangle2D.Double(2, 2, width - 4 - shadowOffset, height - 6 - shadowOffset, cornerRadius - 1, cornerRadius - 1));

            g2.dispose();
        }

        @Override
        public Insets getInsets() {
            return new Insets(14, 14, 14 + shadowOffset, 14 + shadowOffset);
        }
    }

    static class Button3D extends JButton {
        private final Color baseColor;
        private final Color hoverColor;
        private boolean isHovered = false;

        public Button3D(String text, Color base, Color hover) {
            super(text);
            this.baseColor = base;
            this.hoverColor = hover;
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setForeground(Color.BLACK);
            setFont(FONT_SUBTITLE);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) { isHovered = true; repaint(); }
                @Override
                public void mouseExited(MouseEvent e) { isHovered = false; repaint(); }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            int offset = 4;

            Color currentBg = isHovered ? hoverColor : baseColor;

            g2.setColor(currentBg.darker().darker());
            g2.fillRoundRect(0, offset, w, h - offset, 10, 10);

            int topOffset = isHovered ? offset / 2 : 0;
            g2.setColor(currentBg);
            g2.fillRoundRect(0, topOffset, w, h - offset, 10, 10);

            g2.setColor(new Color(255, 255, 255, 80));
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawRoundRect(0, topOffset, w - 1, h - offset - 1, 10, 10);

            FontMetrics fm = g2.getFontMetrics();
            int stringWidth = fm.stringWidth(getText());
            int stringHeight = fm.getAscent();
            g2.setColor(Color.BLACK);
            g2.setFont(getFont());
            g2.drawString(getText(), (w - stringWidth) / 2, (h - stringHeight) / 2 + stringHeight - (isHovered ? 0 : 2));

            g2.dispose();
        }
    }

    // =================================================================================
    // DYNAMIC SWING CHARTS ENGINES (LOCALIZED IN SRI LANKAN RUPEES)
    // =================================================================================

    class BarChart3D extends JPanel {
        public BarChart3D() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int width = getWidth();
            int height = getHeight();
            int padding = 40;

            Map<String, Integer> categoryStockMap = new HashMap<>();
            for (Product p : fetchCurrentInventory()) {
                categoryStockMap.put(p.category, categoryStockMap.getOrDefault(p.category, 0) + p.stock);
            }

            g2.setColor(COLOR_TEXT_MAIN);
            g2.setFont(FONT_SUBTITLE);
            g2.drawString("STOCK QUANTITIES BY SYSTEM TYPE (3D Bar Chart)", padding, 25);

            if (categoryStockMap.isEmpty()) {
                g2.drawString("No inventory data found.", width / 2 - 50, height / 2);
                g2.dispose();
                return;
            }

            g2.setColor(new Color(55, 68, 99));
            g2.setStroke(new BasicStroke(2));
            g2.drawLine(padding + 10, height - padding, width - padding, height - padding);
            g2.drawLine(padding + 10, padding + 10, padding + 10, height - padding);

            int maxStock = 1;
            for (int qty : categoryStockMap.values()) {
                if (qty > maxStock) maxStock = qty;
            }

            int numBars = categoryStockMap.size();
            int barWidth = (width - (padding * 2) - 30) / numBars - 20;
            int currentX = padding + 30;

            int index = 0;
            Color[] barColors = {COLOR_PRIMARY, COLOR_SECONDARY, COLOR_SUCCESS, new Color(254, 202, 87), COLOR_ACCENT};

            for (Map.Entry<String, Integer> entry : categoryStockMap.entrySet()) {
                String category = entry.getKey();
                int qty = entry.getValue();

                int barHeight = (int) (((double) qty / maxStock) * (height - (padding * 2) - 40));
                int barY = height - padding - barHeight;

                Color c = barColors[index % barColors.length];
                int depth = 10;

                g2.setColor(c.darker());
                int[] sideX = {currentX + barWidth, currentX + barWidth + depth, currentX + barWidth + depth, currentX + barWidth};
                int[] sideY = {barY, barY - depth, height - padding - depth, height - padding};
                g2.fillPolygon(sideX, sideY, 4);

                g2.setColor(c.brighter());
                int[] topX = {currentX, currentX + depth, currentX + barWidth + depth, currentX + barWidth};
                int[] topY = {barY, barY - depth, barY - depth, barY};
                g2.fillPolygon(topX, topY, 4);

                g2.setColor(c);
                g2.fillRect(currentX, barY, barWidth, barHeight);

                g2.setColor(COLOR_TEXT_MAIN);
                g2.setFont(new Font("Segoe UI", Font.BOLD, 11));
                g2.drawString(String.valueOf(qty), currentX + (barWidth / 2) - 5, barY - depth - 4);

                g2.setColor(COLOR_TEXT_MUTED);
                g2.setFont(new Font("Segoe UI", Font.PLAIN, 10));
                String truncatedLabel = category.length() > 8 ? category.substring(0, 6) + ".." : category;
                g2.drawString(truncatedLabel, currentX, height - padding + 18);

                currentX += barWidth + 20 + depth;
                index++;
            }

            g2.dispose();
        }
    }

    class LineChart3D extends JPanel {
        public LineChart3D() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int width = getWidth();
            int height = getHeight();
            int padding = 40;

            Map<String, Double> categoryValueMap = new HashMap<>();
            for (Product p : fetchCurrentInventory()) {
                categoryValueMap.put(p.category, categoryValueMap.getOrDefault(p.category, 0.0) + (p.price * p.stock));
            }

            g2.setColor(COLOR_TEXT_MAIN);
            g2.setFont(FONT_SUBTITLE);
            g2.drawString("VALUATION SPREAD (LKR / Rs.) (Neon Line Chart)", padding, 25);

            if (categoryValueMap.isEmpty()) {
                g2.drawString("No inventory data found.", width / 2 - 50, height / 2);
                g2.dispose();
                return;
            }

            g2.setColor(new Color(38, 48, 73, 80));
            g2.setStroke(new BasicStroke(1));
            for (int i = 1; i <= 4; i++) {
                int lineY = padding + i * (height - padding * 2) / 4;
                g2.drawLine(padding + 10, lineY, width - padding, lineY);
            }

            double maxValue = 1.0;
            for (double val : categoryValueMap.values()) {
                if (val > maxValue) maxValue = val;
            }

            int numPoints = categoryValueMap.size();
            int xInterval = (width - padding * 2 - 20) / Math.max(1, numPoints - 1);
            int currentX = padding + 10;

            int[] pointsX = new int[numPoints];
            int[] pointsY = new int[numPoints];
            String[] labels = new String[numPoints];

            int idx = 0;
            for (Map.Entry<String, Double> entry : categoryValueMap.entrySet()) {
                double val = entry.getValue();
                pointsX[idx] = currentX;
                pointsY[idx] = (height - padding) - (int) ((val / maxValue) * (height - padding * 2 - 40));
                labels[idx] = entry.getKey();

                currentX += xInterval;
                idx++;
            }

            g2.setPaint(new GradientPaint(0, padding, new Color(0, 210, 255, 60), 0, height - padding, new Color(0, 210, 255, 0)));
            Polygon area = new Polygon();
            area.addPoint(pointsX[0], height - padding);
            for (int i = 0; i < numPoints; i++) {
                area.addPoint(pointsX[i], pointsY[i]);
            }
            area.addPoint(pointsX[numPoints - 1], height - padding);
            g2.fillPolygon(area);

            g2.setColor(COLOR_SECONDARY);
            g2.setStroke(new BasicStroke(3.0f));
            for (int i = 0; i < numPoints - 1; i++) {
                g2.drawLine(pointsX[i], pointsY[i], pointsX[i + 1], pointsY[i + 1]);
            }

            for (int i = 0; i < numPoints; i++) {
                g2.setColor(COLOR_ACCENT);
                g2.fillOval(pointsX[i] - 5, pointsY[i] - 5, 10, 10);
                g2.setColor(Color.WHITE);
                g2.drawOval(pointsX[i] - 5, pointsY[i] - 5, 10, 10);

                g2.setFont(new Font("Segoe UI", Font.BOLD, 9));
                double lkrValue = categoryValueMap.get(labels[i]);
                String valStr = lkrValue >= 1000000 ? String.format("Rs. %.1fM", lkrValue / 1000000.0) : String.format("Rs. %.0fk", lkrValue / 1000.0);
                g2.drawString(valStr, pointsX[i] - 18, pointsY[i] - 10);

                g2.setColor(COLOR_TEXT_MUTED);
                g2.setFont(new Font("Segoe UI", Font.PLAIN, 10));
                String labelTrunc = labels[i].length() > 8 ? labels[i].substring(0, 6) + ".." : labels[i];
                g2.drawString(labelTrunc, pointsX[i] - 12, height - padding + 18);
            }

            g2.dispose();
        }
    }

    class CumulativeSalesTrendChart extends JPanel {
        public CumulativeSalesTrendChart() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int width = getWidth();
            int height = getHeight();
            int padding = 45;

            List<SaleRecord> history = fetchSalesHistory();

            g2.setColor(COLOR_TEXT_MAIN);
            g2.setFont(FONT_SUBTITLE);
            g2.drawString("LKR CUMULATIVE REVENUE TREND (Sales Ledger)", padding, 25);

            if (history.isEmpty()) {
                g2.setFont(FONT_BODY);
                g2.setColor(COLOR_TEXT_MUTED);
                g2.drawString("No sales transactions synchronized yet.", width / 2 - 120, height / 2);
                g2.dispose();
                return;
            }

            g2.setColor(new Color(38, 48, 73, 60));
            for (int i = 1; i <= 3; i++) {
                int lineY = padding + i * (height - padding * 2) / 3;
                g2.drawLine(padding, lineY, width - padding, lineY);
            }

            double totalRevenue = 0.0;
            double[] runningTotal = new double[history.size()];
            for (int i = 0; i < history.size(); i++) {
                totalRevenue += history.get(i).totalAmount;
                runningTotal[i] = totalRevenue;
            }

            double maxVal = totalRevenue > 0 ? totalRevenue : 1.0;
            int numPoints = history.size();
            int xInterval = (width - padding * 2) / Math.max(1, numPoints - 1);
            int currentX = padding;

            int[] px = new int[numPoints];
            int[] py = new int[numPoints];

            for (int i = 0; i < numPoints; i++) {
                px[i] = currentX;
                py[i] = (height - padding) - (int) ((runningTotal[i] / maxVal) * (height - padding * 2 - 20));
                currentX += xInterval;
            }

            g2.setPaint(new GradientPaint(0, padding, new Color(46, 213, 115, 60), 0, height - padding, new Color(46, 213, 115, 0)));
            Polygon area = new Polygon();
            area.addPoint(px[0], height - padding);
            for (int i = 0; i < numPoints; i++) {
                area.addPoint(px[i], py[i]);
            }
            area.addPoint(px[numPoints - 1], height - padding);
            g2.fillPolygon(area);

            g2.setColor(COLOR_SUCCESS);
            g2.setStroke(new BasicStroke(3.0f));
            for (int i = 0; i < numPoints - 1; i++) {
                g2.drawLine(px[i], py[i], px[i + 1], py[i + 1]);
            }

            for (int i = 0; i < numPoints; i++) {
                g2.setColor(COLOR_ACCENT);
                g2.fillOval(px[i] - 4, py[i] - 4, 8, 8);
                g2.setColor(Color.WHITE);
                g2.drawOval(px[i] - 4, py[i] - 4, 8, 8);

                g2.setFont(new Font("Segoe UI", Font.BOLD, 9));
                g2.setColor(COLOR_TEXT_MAIN);
                g2.drawString("Rs." + new DecimalFormat("#,##0").format(runningTotal[i] / 1000) + "k", px[i] - 15, py[i] - 10);
            }

            g2.dispose();
        }
    }

    // =================================================================================
    // SCREEN 1: SECURE & ANIMATED LOGIN VIEW
    // =================================================================================
    
    private JPanel createLoginScreen() {
        JPanel container = new JPanel(new GridBagLayout());
        container.setBackground(COLOR_BG);

        Panel3D loginBox = new Panel3D(new Color(25, 31, 48), new Color(18, 23, 37));
        loginBox.setPreferredSize(new Dimension(440, 520));
        loginBox.setLayout(new GridBagLayout());

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 20, 6, 20);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0;

        JPanel logoPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(COLOR_PRIMARY);
                g2.fillRoundRect(15, 5, 50, 60, 15, 15);
                g2.setColor(COLOR_SECONDARY);
                g2.setStroke(new BasicStroke(3));
                g2.drawRoundRect(10, 0, 60, 70, 15, 15);
                g2.setColor(COLOR_ACCENT);
                g2.fillRect(12, 35, 56, 3);
                g2.dispose();
            }
        };
        logoPanel.setPreferredSize(new Dimension(80, 80));
        logoPanel.setOpaque(false);

        JLabel titleLabel = new JLabel("QUANTUMSCAN v3D", SwingConstants.CENTER);
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 24));
        titleLabel.setForeground(COLOR_TEXT_MAIN);

        JLabel subtitleLabel = new JLabel("Privileged Access Gateways (LKR Hub)", SwingConstants.CENTER);
        subtitleLabel.setFont(FONT_BODY);
        subtitleLabel.setForeground(COLOR_SECONDARY);

        JLabel userLabel = new JLabel("System Operator Username");
        userLabel.setFont(FONT_SUBTITLE);
        userLabel.setForeground(COLOR_TEXT_MUTED);

        JTextField userField = new JTextField("admin");
        styleInputTextField(userField);

        JLabel passLabel = new JLabel("Access Key Code");
        passLabel.setFont(FONT_SUBTITLE);
        passLabel.setForeground(COLOR_TEXT_MUTED);

        JPasswordField passField = new JPasswordField("admin123");
        styleInputTextField(passField);

        JLabel securityNoteLabel = new JLabel("Roles: Admin [admin123] | Management [manager123]", SwingConstants.CENTER);
        securityNoteLabel.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        securityNoteLabel.setForeground(COLOR_TEXT_MUTED);

        JLabel statusDiagLabel = new JLabel("Connecting secure workspace...", SwingConstants.CENTER);
        statusDiagLabel.setFont(new Font("Segoe UI", Font.ITALIC, 11));
        statusDiagLabel.setForeground(COLOR_TEXT_MUTED);

        Button3D loginBtn = new Button3D("AUTHORIZE & ACCESS SYSTEM", COLOR_PRIMARY, COLOR_PRIMARY.brighter());

        gbc.gridy = 0; gbc.weighty = 0.15; loginBox.add(logoPanel, gbc);
        gbc.gridy = 1; gbc.weighty = 0; loginBox.add(titleLabel, gbc);
        gbc.gridy = 2; loginBox.add(subtitleLabel, gbc);
        gbc.gridy = 3; gbc.insets = new Insets(10, 20, 2, 20); loginBox.add(userLabel, gbc);
        gbc.gridy = 4; gbc.insets = new Insets(2, 20, 10, 20); loginBox.add(userField, gbc);
        gbc.gridy = 5; gbc.insets = new Insets(5, 20, 2, 20); loginBox.add(passLabel, gbc);
        gbc.gridy = 6; gbc.insets = new Insets(2, 20, 12, 20); loginBox.add(passField, gbc);
        gbc.gridy = 7; loginBox.add(securityNoteLabel, gbc);
        gbc.gridy = 8; loginBox.add(loginBtn, gbc);
        gbc.gridy = 9; loginBox.add(statusDiagLabel, gbc);

        Timer diagTimer = new Timer(500, e -> {
            if (isUsingCloudDB) {
                statusDiagLabel.setText("● Connected to Cloud DB Instance (" + CLOUD_DB_URL.split("/")[2].split("\\?")[0] + ")");
                statusDiagLabel.setForeground(COLOR_SUCCESS);
            } else {
                statusDiagLabel.setText("▲ Cloud Offline. Running Secure Local Fallback Mode.");
                statusDiagLabel.setForeground(COLOR_ACCENT);
            }
        });
        diagTimer.setRepeats(false);
        diagTimer.start();

        loginBtn.addActionListener(e -> {
            String u = userField.getText().trim();
            String p = new String(passField.getPassword());

            if (authenticateUser(u, p)) {
                loggedInUser = u;
                userDisplayLabel.setText("  Authorized Operator: " + u + " [" + loggedInRole + "]");
                dbStatusLabel.setText(isUsingCloudDB ? "CLOUD SECURE" : "OFFLINE FALLBACK DEMO");
                dbStatusLabel.setForeground(isUsingCloudDB ? COLOR_SUCCESS : COLOR_ACCENT);

                if (loggedInRole.equals("Management")) {
                    deleteBtn.setEnabled(false);
                    deleteBtn.setToolTipText("Wipe actions restricted to System Admin Role.");
                } else {
                    deleteBtn.setEnabled(true);
                }

                syncTableData();
                recalculateDashboardStats();
                cardLayout.show(mainContainer, "DASHBOARD");
            } else {
                JOptionPane.showMessageDialog(this,
                        "Invalid cryptographic credentials. Please verify and try again.",
                        "Access Revoked", JOptionPane.ERROR_MESSAGE);
            }
        });

        container.add(loginBox);
        return container;
    }

    private void styleInputTextField(JTextField field) {
        field.setBackground(new Color(36, 44, 66));
        field.setForeground(COLOR_TEXT_MAIN);
        field.setCaretColor(COLOR_SECONDARY);
        field.setFont(FONT_SUBTITLE);
        field.setMargin(new Insets(6, 12, 6, 12));
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(55, 68, 99), 1),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)
        ));
    }

    // =================================================================================
    // SCREEN 2: MAIN DASHBOARD VIEW
    // =================================================================================
    
    private DefaultTableModel inventoryTableModel;
    private DefaultTableModel historyTableModel;
    private JLabel totalProductsLabel;
    private JLabel lowStockLabel;
    private JLabel netWorthLabel;
    private JTextArea reportPreviewArea;

    private JCheckBox cbIncludeHeader;
    private JCheckBox cbIncludeTable;
    private JCheckBox cbOnlyLowStock;
    private JCheckBox cbIncludeMetrics;
    private JComboBox<String> comboCategoryFilter;

    private BarChart3D liveBarChart;
    private LineChart3D liveLineChart;
    private CumulativeSalesTrendChart liveSalesTrendChart;

    private JRadioButton radioBarcodeMode;
    private JRadioButton radioQrCodeMode;
    private JPanel scannerGraphicViewport;

    private JPanel createMainDashboardView() {
        JPanel mainLayout = new JPanel(new BorderLayout());
        mainLayout.setBackground(COLOR_BG);

        JPanel sidebar = new JPanel();
        sidebar.setPreferredSize(new Dimension(240, 780));
        sidebar.setBackground(COLOR_CARD_BG);
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(38, 48, 73)));

        JLabel sidebarLogo = new JLabel("QUANTUM SCAN");
        sidebarLogo.setFont(new Font("Segoe UI", Font.BOLD, 18));
        sidebarLogo.setForeground(COLOR_SECONDARY);
        sidebarLogo.setAlignmentX(Component.CENTER_ALIGNMENT);
        sidebarLogo.setBorder(BorderFactory.createEmptyBorder(20, 10, 4, 10));

        JLabel sidebarSub = new JLabel("Computer Inventory Manager");
        sidebarSub.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        sidebarSub.setForeground(COLOR_TEXT_MUTED);
        sidebarSub.setAlignmentX(Component.CENTER_ALIGNMENT);
        sidebarSub.setBorder(BorderFactory.createEmptyBorder(0, 10, 30, 10));

        JPanel contentCardPanel = new JPanel(new CardLayout());
        contentCardPanel.setBackground(COLOR_BG);

        contentCardPanel.add(buildDashboardTab(), "TAB_DASH");
        contentCardPanel.add(buildInventoryTab(), "TAB_INV");
        contentCardPanel.add(buildScannerTab(), "TAB_SCAN");
        contentCardPanel.add(buildReportsTab(), "TAB_REP");
        contentCardPanel.add(buildSalesHistoryTab(), "TAB_HISTORY");

        CardLayout tabSwitcher = (CardLayout) contentCardPanel.getLayout();

        sidebar.add(sidebarLogo);
        sidebar.add(sidebarSub);
        sidebar.add(createNavButton("DASHBOARD CONTROL", "TAB_DASH", tabSwitcher, contentCardPanel));
        sidebar.add(Box.createVerticalStrut(10));
        sidebar.add(createNavButton("INVENTORY VAULT", "TAB_INV", tabSwitcher, contentCardPanel));
        sidebar.add(Box.createVerticalStrut(10));
        sidebar.add(createNavButton("BARCODE & QR SCAN", "TAB_SCAN", tabSwitcher, contentCardPanel));
        sidebar.add(Box.createVerticalStrut(10));
        sidebar.add(createNavButton("PURCHASE HISTORY", "TAB_HISTORY", tabSwitcher, contentCardPanel));
        sidebar.add(Box.createVerticalStrut(10));
        sidebar.add(createNavButton("SOFT ANALYTIC COPIES", "TAB_REP", tabSwitcher, contentCardPanel));

        sidebar.add(Box.createVerticalGlue());

        JButton logoutBtn = new JButton("SECURE EXIT");
        logoutBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        logoutBtn.setBackground(COLOR_ACCENT);
        logoutBtn.setForeground(COLOR_TEXT_MAIN);
        logoutBtn.setFont(new Font("Segoe UI", Font.BOLD, 12));
        logoutBtn.setFocusPainted(false);
        logoutBtn.addActionListener(e -> {
            loggedInUser = "";
            loggedInRole = "";
            cardLayout.show(mainContainer, "LOGIN");
        });
        sidebar.add(logoutBtn);
        sidebar.add(Box.createVerticalStrut(30));

        JPanel headerBar = new JPanel(new BorderLayout());
        headerBar.setBackground(COLOR_CARD_BG);
        headerBar.setPreferredSize(new Dimension(1280, 50));
        headerBar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(38, 48, 73)));

        userDisplayLabel = new JLabel("  Authorized Operator: admin (System Overseer)");
        userDisplayLabel.setForeground(COLOR_TEXT_MAIN);
        userDisplayLabel.setFont(FONT_BODY);

        dbStatusLabel = new JLabel("CLOUD ONLINE  ");
        dbStatusLabel.setFont(FONT_SUBTITLE);

        headerBar.add(userDisplayLabel, BorderLayout.WEST);
        headerBar.add(dbStatusLabel, BorderLayout.EAST);

        mainLayout.add(sidebar, BorderLayout.WEST);
        mainLayout.add(headerBar, BorderLayout.NORTH);
        mainLayout.add(contentCardPanel, BorderLayout.CENTER);

        return mainLayout;
    }

    private JButton createNavButton(String text, String targetCard, CardLayout cl, JPanel container) {
        JButton btn = new JButton(text);
        btn.setMaximumSize(new Dimension(200, 45));
        btn.setPreferredSize(new Dimension(200, 45));
        btn.setAlignmentX(Component.CENTER_ALIGNMENT);
        btn.setBackground(new Color(130, 140, 170));
        btn.setForeground(Color.BLACK);
        btn.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btn.setFocusPainted(false);
        btn.setBorder(BorderFactory.createLineBorder(new Color(25, 31, 48), 1));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        btn.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                btn.setBackground(COLOR_SECONDARY);
                btn.setForeground(Color.BLACK);
            }
            @Override
            public void mouseExited(MouseEvent e) {
                btn.setBackground(new Color(130, 140, 170));
                btn.setForeground(Color.BLACK);
            }
        });

        btn.addActionListener(e -> {
            cl.show(container, targetCard);
            syncTableData();
            syncHistoryData();
            recalculateDashboardStats();
        });
        return btn;
    }

    // =================================================================================
    // TAB: INTERACTIVE DASHBOARD VIEW (WITH GRAPH ENGINE INTEGRATION)
    // =================================================================================
    
    private JPanel buildDashboardTab() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(COLOR_BG);
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel welcomeTitle = new JLabel("Quantum Control Command Suite");
        welcomeTitle.setFont(FONT_TITLE);
        welcomeTitle.setForeground(COLOR_TEXT_MAIN);

        JPanel statsGrid = new JPanel(new GridLayout(1, 3, 20, 0));
        statsGrid.setOpaque(false);

        Panel3D card1 = new Panel3D(COLOR_CARD_BG, COLOR_CARD_BG.brighter());
        card1.setLayout(new BorderLayout());
        totalProductsLabel = new JLabel("0", SwingConstants.CENTER);
        totalProductsLabel.setFont(new Font("Segoe UI", Font.BOLD, 42));
        totalProductsLabel.setForeground(COLOR_SECONDARY);
        JLabel l1 = new JLabel("TOTAL ASSETS REGISTERED", SwingConstants.CENTER);
        l1.setForeground(COLOR_TEXT_MUTED);
        card1.add(totalProductsLabel, BorderLayout.CENTER);
        card1.add(l1, BorderLayout.SOUTH);

        Panel3D card2 = new Panel3D(COLOR_CARD_BG, COLOR_CARD_BG.brighter());
        card2.setLayout(new BorderLayout());
        lowStockLabel = new JLabel("0", SwingConstants.CENTER);
        lowStockLabel.setFont(new Font("Segoe UI", Font.BOLD, 42));
        lowStockLabel.setForeground(COLOR_ACCENT);
        JLabel l2 = new JLabel("CRITICAL RUN-OUT WARNINGS", SwingConstants.CENTER);
        l2.setForeground(COLOR_TEXT_MUTED);
        card2.add(lowStockLabel, BorderLayout.CENTER);
        card2.add(l2, BorderLayout.SOUTH);

        Panel3D card3 = new Panel3D(COLOR_CARD_BG, COLOR_CARD_BG.brighter());
        card3.setLayout(new BorderLayout());
        netWorthLabel = new JLabel("Rs. 0.00", SwingConstants.CENTER);
        netWorthLabel.setFont(new Font("Segoe UI", Font.BOLD, 32));
        netWorthLabel.setForeground(COLOR_SUCCESS);
        JLabel l3 = new JLabel("TOTAL INVENTORY LKR NET VALUATION", SwingConstants.CENTER);
        l3.setForeground(COLOR_TEXT_MUTED);
        card3.add(netWorthLabel, BorderLayout.CENTER);
        card3.add(l3, BorderLayout.SOUTH);

        statsGrid.add(card1);
        statsGrid.add(card2);
        statsGrid.add(card3);

        JPanel chartsContainer = new JPanel(new GridLayout(1, 2, 20, 0));
        chartsContainer.setOpaque(false);
        chartsContainer.setBorder(BorderFactory.createEmptyBorder(15, 0, 15, 0));

        Panel3D barPanel = new Panel3D(new Color(22, 28, 44), new Color(15, 20, 31));
        barPanel.setLayout(new BorderLayout());
        liveBarChart = new BarChart3D();
        barPanel.add(liveBarChart, BorderLayout.CENTER);

        Panel3D linePanel = new Panel3D(new Color(22, 28, 44), new Color(15, 20, 31));
        linePanel.setLayout(new BorderLayout());
        liveLineChart = new LineChart3D();
        linePanel.add(liveLineChart, BorderLayout.CENTER);

        chartsContainer.add(barPanel);
        chartsContainer.add(linePanel);

        panel.add(welcomeTitle, BorderLayout.NORTH);
        panel.add(chartsContainer, BorderLayout.CENTER);
        panel.add(statsGrid, BorderLayout.SOUTH);

        return panel;
    }

    private void recalculateDashboardStats() {
        List<Product> products = fetchCurrentInventory();
        int total = products.size();
        int lowStock = 0;
        double netVal = 0.0;

        for (Product p : products) {
            netVal += (p.price * p.stock);
            if (p.stock <= 5) {
                lowStock++;
            }
        }

        totalProductsLabel.setText(String.valueOf(total));
        lowStockLabel.setText(String.valueOf(lowStock));
        netWorthLabel.setText(LKR_FORMAT.format(netVal));

        if (lowStock > 0) {
            playSecurityBuzzerSound();
        }

        if (liveBarChart != null) liveBarChart.repaint();
        if (liveLineChart != null) liveLineChart.repaint();
        if (liveSalesTrendChart != null) liveSalesTrendChart.repaint();
    }

    // =================================================================================
    // TAB: MODERN INVENTORY CRUD VAULT (WITH PRIVILEGE ENFORCEMENT)
    // =================================================================================
    
    private JPanel buildInventoryTab() {
        JPanel panel = new JPanel(new BorderLayout(15, 15));
        panel.setBackground(COLOR_BG);
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        String[] columns = {"Product Barcode / QR ID", "Product Identity Label", "Category System", "Trading Value (LKR / Rs.)", "Current Quantities", "Location Shelf"};
        inventoryTableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };

        JTable inventoryTable = new JTable(inventoryTableModel);
        styleTable(inventoryTable);

        JScrollPane scrollPane = new JScrollPane(inventoryTable);
        scrollPane.getViewport().setBackground(COLOR_CARD_BG);
        scrollPane.setBorder(BorderFactory.createLineBorder(new Color(36, 45, 68), 1));

        JPanel operationsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        operationsPanel.setOpaque(false);

        Button3D addBtn = new Button3D("ADD HARDWARE", COLOR_SUCCESS, COLOR_SUCCESS.brighter());
        Button3D editBtn = new Button3D("EDIT RECORD", COLOR_PRIMARY, COLOR_PRIMARY.brighter());
        deleteBtn = new Button3D("WIPE RECORD", COLOR_ACCENT, COLOR_ACCENT.brighter());

        operationsPanel.add(addBtn);
        operationsPanel.add(editBtn);
        operationsPanel.add(deleteBtn);

        addBtn.addActionListener(e -> showProductDialog(null));
        editBtn.addActionListener(e -> {
            int selectedRow = inventoryTable.getSelectedRow();
            if (selectedRow != -1) {
                String barcode = (String) inventoryTableModel.getValueAt(selectedRow, 0);
                Product p = findProductByBarcode(barcode);
                if (p != null) showProductDialog(p);
            } else {
                JOptionPane.showMessageDialog(this, "Select a target device row first.", "Operation Denied", JOptionPane.WARNING_MESSAGE);
            }
        });
        deleteBtn.addActionListener(e -> {
            int selectedRow = inventoryTable.getSelectedRow();
            if (selectedRow != -1) {
                String barcode = (String) inventoryTableModel.getValueAt(selectedRow, 0);
                int choice = JOptionPane.showConfirmDialog(this, "Are you sure you want to permanently delete: " + barcode + "?", "Verify Deletion", JOptionPane.YES_NO_OPTION);
                if (choice == JOptionPane.YES_OPTION) {
                    removeProduct(barcode);
                    syncTableData();
                    recalculateDashboardStats();
                }
            } else {
                JOptionPane.showMessageDialog(this, "Select a target device row first to erase.", "Operation Denied", JOptionPane.WARNING_MESSAGE);
            }
        });

        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(operationsPanel, BorderLayout.SOUTH);

        return panel;
    }

    private void styleTable(JTable table) {
        table.setBackground(COLOR_CARD_BG);
        table.setForeground(COLOR_TEXT_MAIN);
        table.setGridColor(new Color(38, 48, 73));
        table.setFont(FONT_BODY);
        table.setRowHeight(36);
        table.setSelectionBackground(COLOR_PRIMARY);
        table.setSelectionForeground(COLOR_TEXT_MAIN);

        JTableHeader header = table.getTableHeader();
        header.setBackground(new Color(36, 45, 68));
        header.setForeground(COLOR_SECONDARY);
        header.setFont(FONT_SUBTITLE);
        header.setBorder(BorderFactory.createLineBorder(new Color(38, 48, 73)));

        DefaultTableCellRenderer cellRenderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
                Component comp = super.getTableCellRendererComponent(t, v, s, f, r, c);
                setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
                
                if (c == 4) {
                    try {
                        int val = Integer.parseInt(v.toString());
                        if (val <= 5) {
                            comp.setForeground(COLOR_ACCENT);
                            comp.setFont(new Font("Segoe UI", Font.BOLD, 13));
                        } else {
                            comp.setForeground(COLOR_TEXT_MAIN);
                            comp.setFont(FONT_BODY);
                        }
                    } catch (Exception ignored) {}
                } else if (c == 3) {
                    try {
                        double amt = Double.parseDouble(v.toString());
                        setText(LKR_FORMAT.format(amt));
                    } catch (Exception ignored) {}
                } else {
                    comp.setForeground(COLOR_TEXT_MAIN);
                    comp.setFont(FONT_BODY);
                }

                if (!s) {
                    comp.setBackground(r % 2 == 0 ? COLOR_CARD_BG : new Color(32, 38, 58));
                }
                return comp;
            }
        };
        table.setDefaultRenderer(Object.class, cellRenderer);
    }

    private void syncTableData() {
        if (inventoryTableModel == null) return;
        inventoryTableModel.setRowCount(0);
        List<Product> products = fetchCurrentInventory();
        for (Product p : products) {
            inventoryTableModel.addRow(new Object[]{p.barcode, p.name, p.category, p.price, p.stock, p.location});
        }
    }

    private List<Product> fetchCurrentInventory() {
        if (!isUsingCloudDB) return mockProductDatabase;

        List<Product> list = new ArrayList<>();
        try (Statement stmt = dbConnection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM inventory")) {
            while (rs.next()) {
                list.add(new Product(
                        rs.getString("barcode"),
                        rs.getString("name"),
                        rs.getString("category"),
                        rs.getDouble("price"),
                        rs.getInt("stock"),
                        rs.getString("location")
                ));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    private Product findProductByBarcode(String barcode) {
        for (Product p : fetchCurrentInventory()) {
            if (p.barcode.equals(barcode)) return p;
        }
        return null;
    }

    private void saveOrUpdateProduct(Product p, boolean isNew) {
        if (!isUsingCloudDB) {
            if (isNew) {
                mockProductDatabase.add(p);
            } else {
                Product existing = findProductByBarcode(p.barcode);
                if (existing != null) {
                    existing.name = p.name;
                    existing.category = p.category;
                    existing.price = p.price;
                    existing.stock = p.stock;
                    existing.location = p.location;
                }
            }
            return;
        }

        String sql = isNew ? "INSERT INTO inventory VALUES (?, ?, ?, ?, ?, ?)"
                            : "UPDATE inventory SET name = ?, category = ?, price = ?, stock = ?, location = ? WHERE barcode = ?";

        try (PreparedStatement ps = dbConnection.prepareStatement(sql)) {
            if (isNew) {
                ps.setString(1, p.barcode);
                ps.setString(2, p.name);
                ps.setString(3, p.category);
                ps.setDouble(4, p.price);
                ps.setInt(5, p.stock);
                ps.setString(6, p.location);
            } else {
                ps.setString(1, p.name);
                ps.setString(2, p.category);
                ps.setDouble(3, p.price);
                ps.setInt(4, p.stock);
                ps.setString(5, p.location);
                ps.setString(6, p.barcode);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void removeProduct(String barcode) {
        if (!isUsingCloudDB) {
            mockProductDatabase.removeIf(p -> p.barcode.equals(barcode));
            return;
        }

        try (PreparedStatement ps = dbConnection.prepareStatement("DELETE FROM inventory WHERE barcode = ?")) {
            ps.setString(1, barcode);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void showProductDialog(Product target) {
        JDialog dialog = new JDialog(this, target == null ? "Register LKR Computer Asset" : "Modify LKR Asset Settings", true);
        dialog.setSize(420, 520);
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(this);

        JPanel mainPanel = new JPanel(new GridBagLayout());
        mainPanel.setBackground(COLOR_BG);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 12, 6, 12);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0;

        JTextField barcodeField = new JTextField();
        JTextField nameField = new JTextField();
        JTextField catField = new JTextField();
        JTextField priceField = new JTextField();
        JTextField stockField = new JTextField();
        JTextField locField = new JTextField();

        styleInputTextField(barcodeField);
        styleInputTextField(nameField);
        styleInputTextField(catField);
        styleInputTextField(priceField);
        styleInputTextField(stockField);
        styleInputTextField(locField);

        if (target != null) {
            barcodeField.setText(target.barcode);
            barcodeField.setEditable(false);
            nameField.setText(target.name);
            catField.setText(target.category);
            priceField.setText(String.valueOf(target.price));
            stockField.setText(String.valueOf(target.stock));
            locField.setText(target.location);
        }

        addFormField(mainPanel, "Device System Barcode / Identity ID", barcodeField, gbc, 0);
        addFormField(mainPanel, "Product Descriptive Label", nameField, gbc, 2);
        addFormField(mainPanel, "General Device Category Grouping", catField, gbc, 4);
        addFormField(mainPanel, "Individual Sale Price Unit (LKR / Rs.)", priceField, gbc, 6);
        addFormField(mainPanel, "Registered Store Quantity", stockField, gbc, 8);
        addFormField(mainPanel, "Inventory Location Code ID", locField, gbc, 10);

        Button3D saveBtn = new Button3D("COMMIT SYSTEM CHANGE", COLOR_PRIMARY, COLOR_PRIMARY.brighter());
        gbc.gridy = 12;
        gbc.insets = new Insets(15, 12, 10, 12);
        mainPanel.add(saveBtn, gbc);

        saveBtn.addActionListener(e -> {
            try {
                String barcode = barcodeField.getText().trim();
                String name = nameField.getText().trim();
                String cat = catField.getText().trim();
                double price = Double.parseDouble(priceField.getText().trim());
                int stock = Integer.parseInt(stockField.getText().trim());
                String loc = locField.getText().trim();

                if (barcode.isEmpty() || name.isEmpty() || cat.isEmpty() || loc.isEmpty()) {
                    throw new IllegalArgumentException("Fields cannot be empty.");
                }

                Product p = new Product(barcode, name, cat, price, stock, loc);
                saveOrUpdateProduct(p, target == null);
                syncTableData();
                recalculateDashboardStats();
                dialog.dispose();

            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dialog, "Input validation failed. Re-verify numeric configurations.\n" + ex.getMessage(),
                        "Validation Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        dialog.add(mainPanel);
        dialog.setVisible(true);
    }

    private void addFormField(JPanel p, String title, JTextField field, GridBagConstraints gbc, int row) {
        JLabel l = new JLabel(title);
        l.setFont(FONT_BODY);
        l.setForeground(COLOR_TEXT_MUTED);
        gbc.gridy = row;
        p.add(l, gbc);
        gbc.gridy = row + 1;
        p.add(field, gbc);
    }

    // =================================================================================
    // TAB: HOLOGRAPHIC BARCODE & QR SCANNER TOGGLE SIMULATION
    // =================================================================================
    
    private JPanel buildScannerTab() {
        JPanel panel = new JPanel(new BorderLayout(20, 20));
        panel.setBackground(COLOR_BG);
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        Panel3D scanPanel3D = new Panel3D(new Color(15, 20, 31), new Color(10, 14, 22));
        scanPanel3D.setLayout(new BorderLayout());

        scannerGraphicViewport = new JPanel() {
            private int laserY = 50;
            private int direction = 3;
            {
                Timer scanAnim = new Timer(16, e -> {
                    laserY += direction;
                    if (laserY > getHeight() - 50 || laserY < 50) {
                        direction = -direction;
                    }
                    repaint();
                });
                scanAnim.start();
            }

            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int w = getWidth();
                int h = getHeight();

                if (radioQrCodeMode.isSelected()) {
                    int qrSize = 200;
                    int qx = (w - qrSize) / 2;
                    int qy = (h - qrSize) / 2;

                    g2.setColor(COLOR_PRIMARY);
                    g2.setStroke(new BasicStroke(4));
                    g2.drawRoundRect(qx - 10, qy - 10, qrSize + 20, qrSize + 20, 20, 20);

                    g2.setColor(COLOR_TEXT_MUTED);
                    g2.fillRect(qx + 10, qy + 10, 50, 50);
                    g2.fillRect(qx + qrSize - 60, qy + 10, 50, 50);
                    g2.fillRect(qx + 10, qy + qrSize - 60, 50, 50);
                    g2.fillRect(qx + 80, qy + 80, 40, 40);

                    g2.setStroke(new BasicStroke(1));
                    for (int x = qx + 70; x < qx + qrSize - 70; x += 15) {
                        for (int y = qy + 10; y < qy + qrSize - 10; y += 15) {
                            if ((x + y) % 3 == 0) {
                                g2.fillRect(x, y, 8, 8);
                            }
                        }
                    }

                    g2.setColor(COLOR_ACCENT);
                    g2.setStroke(new BasicStroke(3));
                    g2.drawLine(qx - 20, laserY, qx + qrSize + 20, laserY);

                    GradientPaint laserGlow = new GradientPaint(0, laserY - 15, new Color(255, 46, 99, 0), 0, laserY, new Color(255, 46, 99, 70));
                    g2.setPaint(laserGlow);
                    g2.fillRect(qx - 20, laserY - 15, qrSize + 40, 15);
                } else {
                    int barW = 280;
                    int barH = 120;
                    int bx = (w - barW) / 2;
                    int by = (h - barH) / 2;

                    g2.setColor(COLOR_PRIMARY);
                    g2.setStroke(new BasicStroke(3));
                    g2.drawRoundRect(bx - 15, by - 15, barW + 30, barH + 30, 15, 15);

                    g2.setColor(COLOR_TEXT_MAIN);
                    int currentBarX = bx + 10;
                    int idx = 0;
                    while (currentBarX < bx + barW - 10) {
                        int weight = (idx % 3 == 0) ? 6 : (idx % 2 == 0) ? 2 : 4;
                        g2.fillRect(currentBarX, by + 10, weight, barH - 20);
                        currentBarX += weight + 4;
                        idx++;
                    }

                    g2.setFont(new Font("Consolas", Font.PLAIN, 12));
                    g2.drawString("8806090123456", bx + 90, by + barH - 2);

                    g2.setColor(COLOR_ACCENT);
                    g2.setStroke(new BasicStroke(3));
                    g2.drawLine(bx - 10, laserY, bx + barW + 10, laserY);

                    GradientPaint laserGlow = new GradientPaint(0, laserY - 15, new Color(255, 46, 99, 0), 0, laserY, new Color(255, 46, 99, 70));
                    g2.setPaint(laserGlow);
                    g2.fillRect(bx - 10, laserY - 15, barW + 20, 15);
                }

                g2.dispose();
            }
        };
        scannerGraphicViewport.setOpaque(false);
        scanPanel3D.add(scannerGraphicViewport, BorderLayout.CENTER);

        JPanel controlPanel = new JPanel(new GridBagLayout());
        controlPanel.setOpaque(false);
        controlPanel.setPreferredSize(new Dimension(340, 600));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(10, 15, 10, 15);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0;

        JLabel scannerInputHeaderLabel = new JLabel("DEVICE HARDWARE INPUT TARGET", SwingConstants.CENTER);
        scannerInputHeaderLabel.setFont(FONT_SUBTITLE);
        scannerInputHeaderLabel.setForeground(COLOR_SECONDARY);

        JLabel modeLabel = new JLabel("CHOOSE SCAN MODE INTERFACE:");
        modeLabel.setFont(FONT_BODY);
        modeLabel.setForeground(COLOR_TEXT_MAIN);

        radioBarcodeMode = new JRadioButton("Traditional 1D Barcode Scanner", true);
        radioBarcodeMode.setFont(FONT_BODY);
        radioBarcodeMode.setForeground(COLOR_TEXT_MUTED);
        radioBarcodeMode.setOpaque(false);

        radioQrCodeMode = new JRadioButton("Modern 2D QR Code Matrix", false);
        radioQrCodeMode.setFont(FONT_BODY);
        radioQrCodeMode.setForeground(COLOR_TEXT_MUTED);
        radioQrCodeMode.setOpaque(false);

        ButtonGroup scanModeGroup = new ButtonGroup();
        scanModeGroup.add(radioBarcodeMode);
        scanModeGroup.add(radioQrCodeMode);

        ActionListener modeToggleAction = e -> scannerGraphicViewport.repaint();
        radioBarcodeMode.addActionListener(modeToggleAction);
        radioQrCodeMode.addActionListener(modeToggleAction);

        JLabel infoTextLabel = new JLabel("<html><center>Connecting a USB hardware scanner feeds codes directly here. Or simulate instantly with the selector below.</center></html>", SwingConstants.CENTER);
        infoTextLabel.setFont(FONT_BODY);
        infoTextLabel.setForeground(COLOR_TEXT_MUTED);

        JTextField scanInputField = new JTextField();
        styleInputTextField(scanInputField);

        scanInputField.addActionListener(e -> processScannedPayload(scanInputField.getText().trim(), scanInputField));

        String[] mockOptions = {
                "--- SIMULATE CODES ---",
                "8806090123456",
                "1951220987654",
                "4719331312345",
                "--- UNREGISTERED ---",
                "1234567890123"
        };
        JComboBox<String> mockSelector = new JComboBox<>(mockOptions);
        mockSelector.setBackground(COLOR_CARD_BG);
        mockSelector.setForeground(COLOR_TEXT_MAIN);
        mockSelector.setFont(FONT_BODY);
        mockSelector.addActionListener(e -> {
            String selection = (String) mockSelector.getSelectedItem();
            if (selection != null && !selection.startsWith("---")) {
                scanInputField.setText(selection);
                processScannedPayload(selection, scanInputField);
            }
        });

        gbc.gridy = 0; controlPanel.add(scannerInputHeaderLabel, gbc);
        gbc.gridy = 1; controlPanel.add(modeLabel, gbc);
        gbc.gridy = 2; controlPanel.add(radioBarcodeMode, gbc);
        gbc.gridy = 3; controlPanel.add(radioQrCodeMode, gbc);
        gbc.gridy = 4; controlPanel.add(infoTextLabel, gbc);
        gbc.gridy = 5; controlPanel.add(scanInputField, gbc);
        gbc.gridy = 6; controlPanel.add(mockSelector, gbc);

        panel.add(scanPanel3D, BorderLayout.CENTER);
        panel.add(controlPanel, BorderLayout.EAST);

        return panel;
    }

    private void processScannedPayload(String payload, JTextField inputComponent) {
        if (payload.isEmpty()) return;

        Product product = findProductByBarcode(payload);
        if (product != null) {
            JOptionPane.showMessageDialog(this,
                    "◆ QUANTUMSCAN REPORT ◆\n\n" +
                            "Product Code: " + product.barcode + "\n" +
                            "Name Label: " + product.name + "\n" +
                            "Category: " + product.category + "\n" +
                            "Current Stock: " + product.stock + " units\n" +
                            "Individual Valuation: " + LKR_FORMAT.format(product.price) + "\n" +
                            "Location: " + product.location + "\n\n" +
                            "Asset Verification: Verified OK",
                    "Device ID Found", JOptionPane.INFORMATION_MESSAGE);
        } else {
            int action = JOptionPane.showConfirmDialog(this,
                    "Detected Unregistered Code: " + payload + "\nWould you like to register this LKR asset device now?",
                    "Catalog Alert", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (action == JOptionPane.YES_OPTION) {
                Product freshProduct = new Product(payload, "New Unnamed Item", "General Hardware", 0.0, 0, "A1");
                showProductDialog(freshProduct);
            }
        }
        inputComponent.setText("");
    }

    // =================================================================================
    // NEW TAB: DEDICATED SALES HISTORY MONITOR (FOR ADMINS & MANAGERS WITH DIAGRAMS)
    // =================================================================================
    
    private JPanel buildSalesHistoryTab() {
        JPanel panel = new JPanel(new BorderLayout(15, 15));
        panel.setBackground(COLOR_BG);
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel title = new JLabel("Central Sales Ledger & Diagnostic Diagrams");
        title.setFont(FONT_TITLE);
        title.setForeground(COLOR_TEXT_MAIN);

        String[] columns = {"Sale ID", "Customer Name", "Customer Contact", "Synchronized Items", "Total Paid (LKR)", "Timestamp"};
        historyTableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };

        JTable historyTable = new JTable(historyTableModel);
        styleTable(historyTable);

        JScrollPane scrollPane = new JScrollPane(historyTable);
        scrollPane.getViewport().setBackground(COLOR_CARD_BG);
        scrollPane.setBorder(BorderFactory.createLineBorder(new Color(36, 45, 68), 1));

        Panel3D chartCard = new Panel3D(new Color(22, 28, 44), new Color(15, 20, 31));
        chartCard.setPreferredSize(new Dimension(520, 600));
        chartCard.setLayout(new BorderLayout());
        liveSalesTrendChart = new CumulativeSalesTrendChart();
        chartCard.add(liveSalesTrendChart, BorderLayout.CENTER);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scrollPane, chartCard);
        splitPane.setOpaque(false);
        splitPane.setDividerLocation(680);
        splitPane.setBorder(null);

        JPanel bottomControls = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottomControls.setOpaque(false);
        Button3D refreshHistoryBtn = new Button3D("REFRESH LEDGER & DIAGRAMS", COLOR_PRIMARY, COLOR_PRIMARY.brighter());
        bottomControls.add(refreshHistoryBtn);

        refreshHistoryBtn.addActionListener(e -> {
            syncHistoryData();
            recalculateDashboardStats();
        });

        panel.add(title, BorderLayout.NORTH);
        panel.add(splitPane, BorderLayout.CENTER);
        panel.add(bottomControls, BorderLayout.SOUTH);

        syncHistoryData();

        return panel;
    }

    private List<SaleRecord> fetchSalesHistory() {
        if (!isUsingCloudDB) return mockSalesLedger;

        List<SaleRecord> list = new ArrayList<>();
        try (Statement stmt = dbConnection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM sales_ledger ORDER BY sale_id ASC")) {
            while (rs.next()) {
                list.add(new SaleRecord(
                        rs.getInt("sale_id"),
                        rs.getString("customer_name"),
                        rs.getString("customer_phone"),
                        rs.getString("items_sold"),
                        rs.getDouble("total_amount"),
                        rs.getString("timestamp")
                ));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    private void syncHistoryData() {
        if (historyTableModel == null) return;
        historyTableModel.setRowCount(0);
        List<SaleRecord> history = fetchSalesHistory();
        for (SaleRecord sr : history) {
            historyTableModel.addRow(new Object[]{
                    sr.saleId,
                    sr.customerName,
                    sr.customerPhone,
                    sr.itemsSold,
                    LKR_FORMAT.format(sr.totalAmount),
                    sr.timestamp
            });
        }
        if (liveSalesTrendChart != null) {
            liveSalesTrendChart.repaint();
        }
    }

    // =================================================================================
    // TAB: ANALYTICAL REPORT WRITER & VIEW (WITH ZERO-DEPENDENCY PDF EXPORTER)
    // =================================================================================
    
    private JPanel buildReportsTab() {
        JPanel panel = new JPanel(new BorderLayout(15, 15));
        panel.setBackground(COLOR_BG);
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel title = new JLabel("Soft-Copy Customizer & PDF Exporter");
        title.setFont(FONT_TITLE);
        title.setForeground(COLOR_TEXT_MAIN);

        Panel3D customizerPanel = new Panel3D(COLOR_CARD_BG, COLOR_CARD_BG.brighter());
        customizerPanel.setPreferredSize(new Dimension(300, 600));
        customizerPanel.setLayout(new GridBagLayout());

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(10, 10, 10, 10);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0;

        JLabel customizerHeader = new JLabel("REPORT SELECTION FILTERS");
        customizerHeader.setFont(FONT_SUBTITLE);
        customizerHeader.setForeground(COLOR_SECONDARY);

        cbIncludeHeader = new JCheckBox("Include Control Banner", true);
        cbIncludeHeader.setBackground(COLOR_CARD_BG);
        cbIncludeHeader.setForeground(COLOR_TEXT_MAIN);
        cbIncludeHeader.setFont(FONT_BODY);

        cbIncludeTable = new JCheckBox("Include Inventory Data Table", true);
        cbIncludeTable.setBackground(COLOR_CARD_BG);
        cbIncludeTable.setForeground(COLOR_TEXT_MAIN);
        cbIncludeTable.setFont(FONT_BODY);

        cbOnlyLowStock = new JCheckBox("Filter: Low Stock Warning Only", false);
        cbOnlyLowStock.setBackground(COLOR_CARD_BG);
        cbOnlyLowStock.setForeground(COLOR_TEXT_MAIN);
        cbOnlyLowStock.setFont(FONT_BODY);

        cbIncludeMetrics = new JCheckBox("Include LKR Summary Metrics", true);
        cbIncludeMetrics.setBackground(COLOR_CARD_BG);
        cbIncludeMetrics.setForeground(COLOR_TEXT_MAIN);
        cbIncludeMetrics.setFont(FONT_BODY);

        JLabel catFilterLabel = new JLabel("Specific Hardware Category Filter:");
        catFilterLabel.setFont(FONT_BODY);
        catFilterLabel.setForeground(COLOR_TEXT_MUTED);

        comboCategoryFilter = new JComboBox<>();
        comboCategoryFilter.setBackground(COLOR_BG);
        comboCategoryFilter.setForeground(COLOR_TEXT_MAIN);
        comboCategoryFilter.setFont(FONT_BODY);

        updateCategoryOptions();

        Button3D refreshReportBtn = new Button3D("COMPILE REPORT", COLOR_PRIMARY, COLOR_PRIMARY.brighter());

        gbc.gridy = 0; customizerPanel.add(customizerHeader, gbc);
        gbc.gridy = 1; customizerPanel.add(cbIncludeHeader, gbc);
        gbc.gridy = 2; customizerPanel.add(cbIncludeTable, gbc);
        gbc.gridy = 3; customizerPanel.add(cbOnlyLowStock, gbc);
        gbc.gridy = 4; customizerPanel.add(cbIncludeMetrics, gbc);
        gbc.gridy = 5; customizerPanel.add(catFilterLabel, gbc);
        gbc.gridy = 6; customizerPanel.add(comboCategoryFilter, gbc);
        gbc.gridy = 7; gbc.weighty = 1.0; gbc.anchor = GridBagConstraints.SOUTH; customizerPanel.add(refreshReportBtn, gbc);

        reportPreviewArea = new JTextArea();
        reportPreviewArea.setBackground(new Color(13, 17, 26));
        reportPreviewArea.setForeground(COLOR_SUCCESS);
        reportPreviewArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        reportPreviewArea.setEditable(false);
        reportPreviewArea.setMargin(new Insets(15, 15, 15, 15));

        JScrollPane scroll = new JScrollPane(reportPreviewArea);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(36, 45, 68), 1));

        JPanel controlBar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        controlBar.setOpaque(false);

        Button3D copyClipboardBtn = new Button3D("COPY TO CLIPBOARD", COLOR_SECONDARY, COLOR_SECONDARY.brighter());
        Button3D saveReportBtn = new Button3D("EXPORT AS TEXT (.txt)", COLOR_PRIMARY, COLOR_PRIMARY.brighter());
        Button3D exportPdfBtn = new Button3D("EXPORT AS PDF (.pdf)", COLOR_SUCCESS, COLOR_SUCCESS.brighter());

        controlBar.add(copyClipboardBtn);
        controlBar.add(saveReportBtn);
        controlBar.add(exportPdfBtn);

        refreshReportBtn.addActionListener(e -> generateSoftReportPreview());
        copyClipboardBtn.addActionListener(e -> copyReportToSystemClipboard());
        saveReportBtn.addActionListener(e -> saveSoftReportToFile());
        exportPdfBtn.addActionListener(e -> saveSoftReportToPdf());

        ActionListener liveUpdate = e -> generateSoftReportPreview();
        cbIncludeHeader.addActionListener(liveUpdate);
        cbIncludeTable.addActionListener(liveUpdate);
        cbOnlyLowStock.addActionListener(liveUpdate);
        cbIncludeMetrics.addActionListener(liveUpdate);
        comboCategoryFilter.addActionListener(liveUpdate);

        JPanel mainContentPanel = new JPanel(new BorderLayout(15, 15));
        mainContentPanel.setOpaque(false);
        mainContentPanel.add(scroll, BorderLayout.CENTER);
        mainContentPanel.add(controlBar, BorderLayout.SOUTH);

        panel.add(title, BorderLayout.NORTH);
        panel.add(customizerPanel, BorderLayout.WEST);
        panel.add(mainContentPanel, BorderLayout.CENTER);

        generateSoftReportPreview();

        return panel;
    }

    private void updateCategoryOptions() {
        if (comboCategoryFilter == null) return;
        comboCategoryFilter.removeAllItems();
        comboCategoryFilter.addItem("ALL CATEGORIES");
        List<Product> products = fetchCurrentInventory();
        java.util.Set<String> categories = new java.util.HashSet<>();
        for (Product p : products) {
            categories.add(p.category);
        }
        for (String cat : categories) {
            comboCategoryFilter.addItem(cat);
        }
    }

    private String buildReportContent() {
        List<Product> products = fetchCurrentInventory();
        StringBuilder sb = new StringBuilder();

        if (cbIncludeHeader.isSelected()) {
            sb.append("=================================================================================\n");
            sb.append("            QUANTUMSCAN SMART LKR INVENTORY REPORT (SRI LANKA HUB)               \n");
            sb.append("=================================================================================\n");
            sb.append("File Generation Timestamp: ").append(new java.util.Date()).append("\n");
            sb.append("Authorized Workspace Operator: ").append(loggedInUser).append(" [").append(loggedInRole).append("]\n");
            sb.append("Target DB System: ").append(isUsingCloudDB ? "SECURE CLOUD ENGINE" : "IN-MEMORY SECURE STORAGE").append("\n");
            sb.append("=================================================================================\n\n");
        }

        String filterCategory = (String) comboCategoryFilter.getSelectedItem();
        boolean isAllCategories = filterCategory == null || filterCategory.equals("ALL CATEGORIES");

        if (cbIncludeTable.isSelected()) {
            sb.append(String.format("%-18s | %-32s | %-12s | %-18s | %-8s | %-8s\n",
                    "BARCODE", "PRODUCT NAME", "CATEGORY", "PRICE (LKR)", "STOCK", "LOCATION"));
            sb.append("-----------------------------------------------------------------------------------------------------------------\n");

            for (Product p : products) {
                if (!isAllCategories && !p.category.equalsIgnoreCase(filterCategory)) {
                    continue;
                }
                if (cbOnlyLowStock.isSelected() && p.stock > 5) {
                    continue;
                }

                sb.append(String.format("%-18s | %-32s | %-12s | %-18.2f | %-8d | %-8s\n",
                        p.barcode,
                        p.name.length() > 30 ? p.name.substring(0, 27) + "..." : p.name,
                        p.category,
                        p.price,
                        p.stock,
                        p.location));
            }
            sb.append("-----------------------------------------------------------------------------------------------------------------\n\n");
        }

        if (cbIncludeMetrics.isSelected()) {
            double totalValue = 0;
            int totalItems = 0;
            int lowStockCount = 0;

            for (Product p : products) {
                if (!isAllCategories && !p.category.equalsIgnoreCase(filterCategory)) continue;

                totalValue += (p.price * p.stock);
                totalItems += p.stock;
                if (p.stock <= 5) {
                    lowStockCount++;
                }
            }

            sb.append("=================================== LKR METRICS ===================================\n");
            sb.append(String.format("Target Scope Focus:               %s\n", filterCategory));
            sb.append(String.format("Aggregated Scope Asset Value:     %s\n", LKR_FORMAT.format(totalValue)));
            sb.append(String.format("Aggregated Scope Stock Quantities: %d units\n", totalItems));
            sb.append(String.format("Active Stock Depletion Warnings:  %d instances\n", lowStockCount));
            sb.append("=================================== METRICS END ===================================\n");
        }

        return sb.toString();
    }

    private void generateSoftReportPreview() {
        if (reportPreviewArea != null) {
            reportPreviewArea.setText(buildReportContent());
        }
    }

    private void copyReportToSystemClipboard() {
        String reportText = buildReportContent();
        if (reportText.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "The compiled report is currently empty.", "Empty Copy Operation", JOptionPane.WARNING_MESSAGE);
            return;
        }

        try {
            StringSelection stringSelection = new StringSelection(reportText);
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(stringSelection, null);

            JOptionPane.showMessageDialog(this,
                    "✔ Soft-copy successfully written to System Clipboard.\nYou can now paste it anywhere (Word, Excel, Chat, Email, etc.)",
                    "Soft-Copy Extraction Successful", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not acquire system clipboard access: " + ex.getMessage(),
                    "Clipboard Exception", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void saveSoftReportToFile() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Export Soft Copy Destination");
        fileChooser.setSelectedFile(new java.io.File("QuantumScan_Custom_Report.txt"));
        int userSelection = fileChooser.showSaveDialog(this);

        if (userSelection == JFileChooser.APPROVE_OPTION) {
            try (FileWriter fw = new FileWriter(fileChooser.getSelectedFile())) {
                fw.write(buildReportContent());
                JOptionPane.showMessageDialog(this, "Customized report exported successfully.",
                        "Data Export Confirmed", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Could not compile report to file. Error: " + ex.getMessage(),
                        "System Core Fault", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // =================================================================================
    // PURE NATIVE ZERO-DEPENDENCY PDF EXPORT ENGINE
    // =================================================================================

    private void saveSoftReportToPdf() {
        String reportText = buildReportContent();
        if (reportText.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "The compiled report is empty. Please compile first.", "Export Blocked", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Select PDF Export Destination");
        fileChooser.setSelectedFile(new java.io.File("QuantumScan_Inventory_Report.pdf"));
        int userSelection = fileChooser.showSaveDialog(this);

        if (userSelection == JFileChooser.APPROVE_OPTION) {
            File pdfFile = fileChooser.getSelectedFile();
            try {
                writeNativePdfFile(pdfFile, reportText);
                JOptionPane.showMessageDialog(this, "✔ Soft-copy PDF exported successfully!",
                        "PDF Generation Complete", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Could not generate PDF. Error: " + ex.getMessage(),
                        "PDF Engine Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void writeNativePdfFile(File file, String content) throws IOException {
        String[] lines = content.split("\n");
        List<String> escapedLines = new ArrayList<>();
        for (String line : lines) {
            String escaped = line.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
            escapedLines.add(escaped);
        }

        try (FileOutputStream fos = new FileOutputStream(file)) {
            List<Long> offsets = new ArrayList<>();

            writePdfLine(fos, "%PDF-1.4", offsets, false);

            long catalogOffset = fos.getChannel().position();
            offsets.add(catalogOffset);
            writePdfLine(fos, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj", offsets, true);

            long pagesOffset = fos.getChannel().position();
            offsets.add(pagesOffset);
            writePdfLine(fos, "2 0 obj\n<< /Type /Pages /Kids [ 3 0 R ] /Count 1 >>\nendobj", offsets, true);

            long pageOffset = fos.getChannel().position();
            offsets.add(pageOffset);
            writePdfLine(fos, "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [ 0 0 612 792 ] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>\nendobj", offsets, true);

            long fontOffset = fos.getChannel().position();
            offsets.add(fontOffset);
            writePdfLine(fos, "4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Courier >>\nendobj", offsets, true);

            StringBuilder streamContent = new StringBuilder();
            streamContent.append("BT\n/F1 9 Tf\n11 TL\n35 745 Td\n");

            int lineLimitPerPage = 62;
            int currentLine = 0;

            for (String line : escapedLines) {
                streamContent.append("(").append(line).append(") Tj T*\n");
                currentLine++;
                if (currentLine >= lineLimitPerPage) {
                    break;
                }
            }
            streamContent.append("ET\n");

            byte[] streamBytes = streamContent.toString().getBytes(StandardCharsets.ISO_8859_1);
            long streamObjOffset = fos.getChannel().position();
            offsets.add(streamObjOffset);
            
            writePdfLine(fos, "5 0 obj\n<< /Length " + streamBytes.length + " >>\nstream", offsets, true);
            fos.write(streamBytes);
            fos.write("\n".getBytes(StandardCharsets.ISO_8859_1));
            writePdfLine(fos, "endstream\nendobj", offsets, true);

            long xrefOffset = fos.getChannel().position();
            writePdfLine(fos, "xref\n0 " + (offsets.size() + 1) + "\n0000000000 65535 f ", offsets, true);
            for (Long offset : offsets) {
                String formattedOffset = String.format("%010d 00000 n ", offset);
                writePdfLine(fos, formattedOffset, offsets, true);
            }

            writePdfLine(fos, "trailer\n<< /Size " + (offsets.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xrefOffset + "\n%%EOF", offsets, true);
        }
    }

    private void writePdfLine(FileOutputStream fos, String data, List<Long> offsets, boolean skipOffset) throws IOException {
        byte[] bytes = (data + "\n").getBytes(StandardCharsets.ISO_8859_1);
        fos.write(bytes);
    }

    // =================================================================================
    // SUPPORT STRUCTS & SCHEMAS
    // =================================================================================
    
    static class Product {
        String barcode;
        String name;
        String category;
        double price;
        int stock;
        String location;

        public Product(String barcode, String name, String category, double price, int stock, String location) {
            this.barcode = barcode;
            this.name = name;
            this.category = category;
            this.price = price;
            this.stock = stock;
            this.location = location;
        }
    }

    static class SystemUser {
        String username;
        String passwordHash;
        String salt;
        String role;

        public SystemUser(String username, String passwordHash, String salt, String role) {
            this.username = username;
            this.passwordHash = passwordHash;
            this.salt = salt;
            this.role = role;
        }
    }

    static class SaleRecord {
        int saleId;
        String customerName;
        String customerPhone;
        String itemsSold;
        double totalAmount;
        String timestamp;

        public SaleRecord(int saleId, String customerName, String customerPhone, String itemsSold, double totalAmount, String timestamp) {
            this.saleId = saleId;
            this.customerName = customerName;
            this.customerPhone = customerPhone;
            this.itemsSold = itemsSold;
            this.totalAmount = totalAmount;
            this.timestamp = timestamp;
        }
        
    }
    
}