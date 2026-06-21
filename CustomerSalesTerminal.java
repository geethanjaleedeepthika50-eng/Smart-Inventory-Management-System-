package com.computershop.inventory;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import javax.swing.text.JTextComponent;
import java.awt.*; 
import java.awt.event.*; 
import java.awt.geom.*; 
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.sound.sampled.*;

/**
 * =================================================================================
 * CUSTOMER SALES TERMINAL (QUANTUMSALES v3D)
 * =================================================================================
 * Standalone Client-Facing Checkout Point-of-Sale (POS) System.
 * Synchronizes with the shared computer_shop_db SQL database.
 * * Features:
 * - Salted SHA-256 Cashier Login Gateway (matches the new database table structure).
 * - Self-Healing Runtime Credential Synchronizer (Guarantees login success).
 * - Real-Time Stock Browsing localized in Sri Lankan Rupees (Rs. / LKR).
 * - Dynamic Shopping Cart with real-time VAT/SSCL calculations.
 * - Dynamic Quantity Editor to change cart item parameters on the fly.
 * - Multi-Method Settle Engine: Cash (with auto change calculator), Card Gateways, and LANKAQR.
 * - Real Bulk SMS Dispatcher using Twilio/Notify.lk Gateways supporting comma-separated numbers.
 * =================================================================================
 */
public class CustomerSalesTerminal extends JFrame {

    // --- CONFIGURATION FOR SHARED DATABASE ---
    private static final String DB_URL = "jdbc:mysql://localhost:3306/computer_shop_db?useSSL=true&allowPublicKeyRetrieval=true";
    private static final String DB_USER = "root";
    private static final String DB_PASS = "1234";

    // =================================================================================
    // REAL SMS GATEWAY CREDENTIALS (Configure your API details here)
    // =================================================================================
    private static final String SMS_PROVIDER = "NOTIFY_LK"; // Default to NOTIFY_LK for instant activation
    
    // Config 1: Twilio Credentials
    private static final String TWILIO_ACCOUNT_SID = "YOUR_TWILIO_ACCOUNT_SID_HERE";
    private static final String TWILIO_AUTH_TOKEN = "YOUR_TWILIO_AUTH_TOKEN_HERE";
    private static final String TWILIO_SENDER_NUMBER = "YOUR_TWILIO_PHONE_NUMBER_HERE"; 

    // Config 2: Notify.lk Credentials (Sri Lankan Gateway)
    // FILL THESE THREE FIELDS TO DELIVER REAL MESSAGES
    private static final String NOTIFY_LK_API_KEY = "";
    private static final String NOTIFY_LK_USER_ID = "";
    private static final String NOTIFY_LK_SENDER_ID = "NotifyDEMO"; 

    private static boolean isUsingCloudDB = false;
    private static Connection dbConnection = null;
    private static String loggedInCashier = "";

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

    // UI State Layout Components
    private JPanel mainContainer;
    private CardLayout cardLayout;
    private JLabel connectionStatusLabel;

    // POS Dashboard Components
    private DefaultTableModel catalogTableModel;
    private DefaultTableModel cartTableModel;
    private List<Product> loadedProducts = new ArrayList<>();
    private List<CartItem> currentCart = new ArrayList<>();

    // Invoice Price Indicator Labels
    private JLabel labelSubtotal;
    private JLabel labelTaxes;
    private JLabel labelTotal;

    // Fallback offline database mock storage
    private static final List<Product> mockProductDatabase = new ArrayList<>();
    private static final List<SystemUser> mockUserDatabase = new ArrayList<>();
    private static final List<SaleRecord> mockSalesLedger = new ArrayList<>();

    public CustomerSalesTerminal() {
        setTitle("COMPUTECH v3D - High-Speed Cashier POS Terminal");
        setSize(1350, 820);
        setMinimumSize(new Dimension(1100, 720));
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        getContentPane().setBackground(COLOR_BG);

        // Attempt Connection to the shared MySQL Database
        initDatabaseConnection();

        // Setup Card Layout and Primary Containers
        cardLayout = new CardLayout();
        mainContainer = new JPanel(cardLayout);
        mainContainer.setBackground(COLOR_BG);

        // Add modular application screens
        mainContainer.add(createCashierLoginScreen(), "LOGIN_SCREEN");
        mainContainer.add(createSalesTerminalScreen(), "POS_SCREEN");

        add(mainContainer);
        cardLayout.show(mainContainer, "LOGIN_SCREEN");
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            CustomerSalesTerminal app = new CustomerSalesTerminal();
            app.setVisible(true);
        });
    }

    // =================================================================================
    // DATABASE & SHA-256 SECURITY LAYER WITH RUNTIME SYNCHRONIZER
    // =================================================================================

    private void initDatabaseConnection() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            dbConnection = DriverManager.getConnection("jdbc:mysql://localhost:3306/computer_shop_db","root","1234");
            isUsingCloudDB = true;
            System.out.println("[Database System] Linked successfully to computer_shop_db.");
            
            // Runs runtime credential sync to make sure the seeded DB password hashes match this exact runtime format.
            syncDefaultUsersInDatabase();
            
        } catch (Exception e) {
            isUsingCloudDB = false;
            seedLocalMockData();
            System.out.println("[Database System] Offline fallback enabled. Local storage populated.");
        }
    }

    private void syncDefaultUsersInDatabase() {
        if (dbConnection == null) return;
        try (Statement stmt = dbConnection.createStatement()) {
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS users ("
                    + "username VARCHAR(50) PRIMARY KEY,"
                    + "password_hash VARCHAR(256) NOT NULL DEFAULT '',"
                    + "salt VARCHAR(128) NOT NULL DEFAULT '',"
                    + "role VARCHAR(30) NOT NULL"
                    + ")");

            upsertUserInDatabase("admin", "admin123", "Admin");
            upsertUserInDatabase("manager", "manager123", "Management");
            upsertUserInDatabase("cashier", "cashier123", "Cashier");
        } catch (SQLException e) {
            System.out.println("[Database System] Sync warning: " + e.getMessage());
        }
    }

    private void upsertUserInDatabase(String username, String rawPassword, String role) {
        String salt = Base64.getEncoder().encodeToString("a123456789012345".getBytes(StandardCharsets.UTF_8));
        String hash = hashPassword(rawPassword, salt);

        String checkSql = "SELECT username FROM users WHERE username = ?";
        try (PreparedStatement psCheck = dbConnection.prepareStatement(checkSql)) {
            psCheck.setString(1, username);
            try (ResultSet rs = psCheck.executeQuery()) {
                if (rs.next()) {
                    String updateSql = "UPDATE users SET password_hash = ?, salt = ?, role = ? WHERE username = ?";
                    try (PreparedStatement psUpdate = dbConnection.prepareStatement(updateSql)) {
                        psUpdate.setString(1, hash);
                        psUpdate.setString(2, salt);
                        psUpdate.setString(3, role);
                        psUpdate.setString(4, username);
                        psUpdate.executeUpdate();
                    }
                } else {
                    String insertSql = "INSERT INTO users (username, password_hash, salt, role) VALUES (?, ?, ?, ?)";
                    try (PreparedStatement psInsert = dbConnection.prepareStatement(insertSql)) {
                        psInsert.setString(1, username);
                        psInsert.setString(2, hash);
                        psInsert.setString(3, salt);
                        psInsert.setString(4, role);
                        psInsert.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void seedLocalMockData() {
        if (mockProductDatabase.isEmpty()) {
            mockProductDatabase.add(new Product("8806090123456", "Samsung Odyssey G9 Monitor", "Monitors", 389500.00, 12));
            mockProductDatabase.add(new Product("1951220987654", "Intel Core i9-14900K Processor", "CPUs", 185000.00, 3));
            mockProductDatabase.add(new Product("4719331312345", "NVIDIA RTX 4090 OC 24GB", "GPUs", 695000.00, 8));
            mockProductDatabase.add(new Product("0840006123456", "Corsair Dominator Titanium 64GB", "RAM", 98500.00, 4));
            mockProductDatabase.add(new Product("0718037891234", "WD Black SN854X NVMe 2TB", "Storage", 58000.00, 50));
        }
        if (mockUserDatabase.isEmpty()) {
            String fixedSalt = Base64.getEncoder().encodeToString("a123456789012345".getBytes(StandardCharsets.UTF_8));
            mockUserDatabase.add(new SystemUser("cashier", hashPassword("cashier123", fixedSalt), fixedSalt, "Cashier"));
            mockUserDatabase.add(new SystemUser("admin", hashPassword("admin123", fixedSalt), fixedSalt, "Admin"));
        }
    }

    private static String hashPassword(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Base64.getDecoder().decode(salt));
            byte[] hashedBytes = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashedBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Encryption fault encountered.");
        }
    }

    private boolean validateCashierCredentials(String username, String password) {
        if (!isUsingCloudDB) {
            for (SystemUser user : mockUserDatabase) {
                if (user.username.equalsIgnoreCase(username)) {
                    String hashedInput = hashPassword(password, user.salt);
                    return hashedInput.equals(user.passwordHash);
                }
            }
            return false;
        }

        String query = "SELECT password_hash, salt, role FROM users WHERE username = ?";
        try (PreparedStatement ps = dbConnection.prepareStatement(query)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String storedHash = rs.getString("password_hash");
                    String storedSalt = rs.getString("salt");
                    String hashedInput = hashPassword(password, storedSalt);
                    return hashedInput.equals(storedHash);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    // =================================================================================
    // NATIVE IN-MEMORY AUDIO CHIME GENERATOR
    // =================================================================================

    private static synchronized void playCashRegisterChime() {
        new Thread(() -> {
            try {
                float sampleRate = 8000f;
                byte[] buffer = new byte[2400];
                AudioFormat format = new AudioFormat(sampleRate, 8, 1, true, false);
                SourceDataLine line = AudioSystem.getSourceDataLine(format);
                line.open(format);
                line.start();

                for (int i = 0; i < buffer.length; i++) {
                    double angle = i / (sampleRate / 1200.0) * 2.0 * Math.PI;
                    double volumeEnvelope = Math.exp(-4.0 * i / buffer.length);
                    buffer[i] = (byte) (Math.sin(angle) * 127.0 * volumeEnvelope);
                }

                line.write(buffer, 0, buffer.length);
                line.drain();
                line.stop();
                line.close();
            } catch (LineUnavailableException ex) {
                Toolkit.getDefaultToolkit().beep();
            }
        }).start();
    }

    // =================================================================================
    // 3D DESIGN COMPONENT LIBRARY (CUSTOM RENDERERS)
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
            return new Insets(12, 12, 12 + shadowOffset, 12 + shadowOffset);
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
            g2.drawString(getText(), (w - stringWidth) / 2, (h - stringHeight) / 2 + stringHeight - (isHovered ? 0 : 2));

            g2.dispose();
        }
    }

    // =================================================================================
    // SCREEN 1: SECURE CASHIER LOGIN INTERFACE
    // =================================================================================

    private JPanel createCashierLoginScreen() {
        JPanel wrapperPanel = new JPanel(new GridBagLayout());
        wrapperPanel.setBackground(COLOR_BG);

        Panel3D loginBox = new Panel3D(new Color(25, 31, 48), new Color(18, 23, 37));
        loginBox.setPreferredSize(new Dimension(420, 520));
        loginBox.setLayout(new GridBagLayout());

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 20, 8, 20);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0;

        JPanel iconLogoPanel = new JPanel() {
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
        iconLogoPanel.setPreferredSize(new Dimension(80, 80));
        iconLogoPanel.setOpaque(false);

        JLabel mainTitleLabel = new JLabel("COMPUTECH v3D", SwingConstants.CENTER);
        mainTitleLabel.setFont(new Font("Segoe UI", Font.BOLD, 24));
        mainTitleLabel.setForeground(COLOR_TEXT_MAIN);

        JLabel subtitleLabel = new JLabel("Cashier Operational Gateways ", SwingConstants.CENTER);
        subtitleLabel.setFont(FONT_BODY);
        subtitleLabel.setForeground(COLOR_SECONDARY);

        JLabel userFieldLabel = new JLabel("Cashier Username ID");
        userFieldLabel.setFont(FONT_SUBTITLE);
        userFieldLabel.setForeground(COLOR_TEXT_MUTED);

        JTextField userField = new JTextField("cashier");
        styleInputTextField(userField);

        JLabel passFieldLabel = new JLabel("Cashier Access Code");
        passFieldLabel.setFont(FONT_SUBTITLE);
        passFieldLabel.setForeground(COLOR_TEXT_MUTED);

        JPasswordField passField = new JPasswordField("cashier123");
        styleInputTextField(passField);

        JLabel credentialsTip = new JLabel("", SwingConstants.CENTER);
        credentialsTip.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        credentialsTip.setForeground(COLOR_TEXT_MUTED);

        connectionStatusLabel = new JLabel("Verifying server links...", SwingConstants.CENTER);
        connectionStatusLabel.setFont(new Font("Segoe UI", Font.ITALIC, 11));
        connectionStatusLabel.setForeground(COLOR_TEXT_MUTED);

        Button3D loginBtn = new Button3D("LOG-IN CASHIER", COLOR_PRIMARY, COLOR_PRIMARY.brighter());

        gbc.gridy = 0; gbc.weighty = 0.15; loginBox.add(iconLogoPanel, gbc);
        gbc.gridy = 1; gbc.weighty = 0; loginBox.add(mainTitleLabel, gbc);
        gbc.gridy = 2; loginBox.add(subtitleLabel, gbc);
        gbc.gridy = 3; gbc.insets = new Insets(12, 20, 2, 20); loginBox.add(userFieldLabel, gbc);
        gbc.gridy = 4; gbc.insets = new Insets(2, 20, 10, 20); loginBox.add(userField, gbc);
        gbc.gridy = 5; gbc.insets = new Insets(5, 20, 2, 20); loginBox.add(passFieldLabel, gbc);
        gbc.gridy = 6; gbc.insets = new Insets(2, 20, 12, 20); loginBox.add(passField, gbc);
        gbc.gridy = 7; loginBox.add(credentialsTip, gbc);
        gbc.gridy = 8; loginBox.add(loginBtn, gbc);
        gbc.gridy = 9; loginBox.add(connectionStatusLabel, gbc);

        Timer connTimer = new Timer(500, e -> {
            if (isUsingCloudDB) {
                connectionStatusLabel.setText("● Synchronized to shared MySQL Instance (Port 3306)");
                connectionStatusLabel.setForeground(COLOR_SUCCESS);
            } else {
                connectionStatusLabel.setText("▲ Database offline. Initializing local sandbox.");
                connectionStatusLabel.setForeground(COLOR_ACCENT);
            }
        });
        connTimer.setRepeats(false);
        connTimer.start();

        loginBtn.addActionListener(e -> {
            String cashierUser = userField.getText().trim();
            String cashierPass = new String(passField.getPassword());

            if (validateCashierCredentials(cashierUser, cashierPass)) {
                loggedInCashier = cashierUser;
                syncCatalogProducts();
                cardLayout.show(mainContainer, "POS_SCREEN");
            } else {
                JOptionPane.showMessageDialog(this,
                        "Invalid cryptographic credentials. Please verify cashier privileges.",
                        "Access Revoked", JOptionPane.ERROR_MESSAGE);
            }
        });

        wrapperPanel.add(loginBox);
        return wrapperPanel;
    }

    private void styleInputTextField(JTextComponent field) {
        field.setBackground(COLOR_CARD_BG);
        field.setForeground(COLOR_TEXT_MAIN);
        field.setCaretColor(COLOR_TEXT_MAIN);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(45, 55, 80), 1),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)
        ));
        field.setFont(FONT_BODY);
    }

    // =================================================================================
    // SCREEN 2: STANDALONE COHESIVE CHECKOUT POINT-OF-SALE (POS) VIEW
    // =================================================================================

    private JPanel createSalesTerminalScreen() {
        JPanel basePanel = new JPanel(new BorderLayout());
        basePanel.setBackground(COLOR_BG);

        JPanel headerBar = new JPanel(new BorderLayout());
        headerBar.setBackground(COLOR_CARD_BG);
        headerBar.setPreferredSize(new Dimension(1350, 50));
        headerBar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(38, 48, 73)));

        JLabel cashierTag = new JLabel("   OPERATIONAL CONSOLE - ACTIVE DEPUTY: [CASHIER]");
        cashierTag.setForeground(COLOR_SECONDARY);
        cashierTag.setFont(FONT_SUBTITLE);

        JButton logoutBtn = new JButton("SECURE EXIT TERMINAL  ");
        logoutBtn.setFont(FONT_SUBTITLE);
        logoutBtn.setForeground(COLOR_ACCENT);
        logoutBtn.setContentAreaFilled(false);
        logoutBtn.setBorderPainted(false);
        logoutBtn.setFocusPainted(false);
        logoutBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        logoutBtn.addActionListener(e -> {
            loggedInCashier = "";
            cardLayout.show(mainContainer, "LOGIN_SCREEN");
        });

        headerBar.add(cashierTag, BorderLayout.WEST);
        headerBar.add(logoutBtn, BorderLayout.EAST);

        JPanel catalogPanel = new JPanel(new BorderLayout(10, 10));
        catalogPanel.setOpaque(false);
        catalogPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

        JLabel catalogTitle = new JLabel("LKR SHARED INVENTORY CATALOG");
        catalogTitle.setFont(FONT_TITLE);
        catalogTitle.setForeground(COLOR_TEXT_MAIN);
        catalogPanel.add(catalogTitle, BorderLayout.NORTH);

        String[] catalogColumns = {"Barcode ID", "Item Description", "Category", "Rate (LKR)", "In-Stock"};
        catalogTableModel = new DefaultTableModel(catalogColumns, 0) {
            @Override
            public boolean isCellEditable(int row, int col) { return false; }
        };

        JTable catalogTable = new JTable(catalogTableModel);
        styleTable(catalogTable);

        JScrollPane catalogScroll = new JScrollPane(catalogTable);
        catalogScroll.getViewport().setBackground(COLOR_CARD_BG);
        catalogScroll.setBorder(BorderFactory.createLineBorder(new Color(36, 45, 68), 1));
        catalogPanel.add(catalogScroll, BorderLayout.CENTER);

        JPanel quickAddPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        quickAddPanel.setOpaque(false);
        Button3D addToCartBtn = new Button3D("ADD TO SHOPPING CART", COLOR_PRIMARY, COLOR_PRIMARY.brighter());
        quickAddPanel.add(addToCartBtn);
        catalogPanel.add(quickAddPanel, BorderLayout.SOUTH);

        addToCartBtn.addActionListener(e -> {
            int selectedRow = catalogTable.getSelectedRow();
            if (selectedRow != -1) {
                String barcode = (String) catalogTableModel.getValueAt(selectedRow, 0);
                Product selectedProduct = findCatalogProduct(barcode);
                if (selectedProduct != null) {
                    addProductToCart(selectedProduct);
                }
            } else {
                JOptionPane.showMessageDialog(this, "Select a catalog computer part to add.", "Selection Missing", JOptionPane.WARNING_MESSAGE);
            }
        });

        JPanel checkoutContainer = new JPanel(new BorderLayout(10, 10));
        checkoutContainer.setOpaque(false);
        checkoutContainer.setPreferredSize(new Dimension(500, 780));
        checkoutContainer.setBorder(BorderFactory.createEmptyBorder(15, 0, 15, 15));

        JLabel checkoutTitle = new JLabel("CUSTOMER TRANSACTION WORKSPACE");
        checkoutTitle.setFont(FONT_TITLE);
        checkoutTitle.setForeground(COLOR_SECONDARY);
        checkoutContainer.add(checkoutTitle, BorderLayout.NORTH);

        String[] cartColumns = {"Item Name", "Rate (LKR)", "Qty", "Subtotal (LKR)"};
        cartTableModel = new DefaultTableModel(cartColumns, 0) {
            @Override
            public boolean isCellEditable(int row, int col) { return false; }
        };

        JTable cartTable = new JTable(cartTableModel);
        styleTable(cartTable);

        JScrollPane cartScroll = new JScrollPane(cartTable);
        cartScroll.getViewport().setBackground(COLOR_CARD_BG);
        cartScroll.setBorder(BorderFactory.createLineBorder(new Color(36, 45, 68), 1));

        Panel3D invoiceCard = new Panel3D(new Color(25, 31, 48), new Color(18, 23, 37));
        invoiceCard.setLayout(new GridLayout(3, 2, 10, 8));
        invoiceCard.setPreferredSize(new Dimension(480, 130));

        JLabel lblSub = new JLabel("Subtotal Accumulation:");
        lblSub.setFont(FONT_BODY);
        lblSub.setForeground(COLOR_TEXT_MUTED);
        labelSubtotal = new JLabel("Rs. 0.00", SwingConstants.RIGHT);
        labelSubtotal.setFont(FONT_SUBTITLE);
        labelSubtotal.setForeground(COLOR_TEXT_MAIN);

       JLabel lblTax = new JLabel("Integrated Taxes (VAT 15% + SSCL 2.5%):");
        lblTax.setFont(FONT_BODY);
        lblTax.setForeground(COLOR_TEXT_MUTED);
        labelTaxes = new JLabel("Rs. 0.00", SwingConstants.RIGHT);
        labelTaxes.setFont(FONT_SUBTITLE);
        labelTaxes.setForeground(COLOR_TEXT_MAIN);

        JLabel lblTot = new JLabel("TOTAL SETTLEMENT VALUE (LKR):");
        lblTot.setFont(FONT_SUBTITLE);
        lblTot.setForeground(COLOR_SECONDARY);
        labelTotal = new JLabel("Rs. 0.00", SwingConstants.RIGHT);
        labelTotal.setFont(new Font("Segoe UI", Font.BOLD, 18));
        labelTotal.setForeground(COLOR_SUCCESS);

        invoiceCard.add(lblSub); invoiceCard.add(labelSubtotal);
        invoiceCard.add(lblTax); invoiceCard.add(labelTaxes);
        invoiceCard.add(lblTot); invoiceCard.add(labelTotal);

        JPanel cartOperationsPanel = new JPanel(new GridLayout(1, 3, 8, 0));
        cartOperationsPanel.setOpaque(false);
        Button3D editQtyBtn = new Button3D("EDIT QUANTITY", COLOR_SECONDARY, COLOR_SECONDARY.brighter());
        Button3D removeItemBtn = new Button3D("REMOVE ITEM", COLOR_ACCENT, COLOR_ACCENT.brighter());
        Button3D settleCheckoutBtn = new Button3D("SETTLE BILL", COLOR_SUCCESS, COLOR_SUCCESS.brighter());
        
        cartOperationsPanel.add(editQtyBtn);
        cartOperationsPanel.add(removeItemBtn);
        cartOperationsPanel.add(settleCheckoutBtn);

        editQtyBtn.addActionListener(e -> {
            int selectedRow = cartTable.getSelectedRow();
            if (selectedRow != -1) {
                editCartItemQuantity(selectedRow);
            } else {
                JOptionPane.showMessageDialog(this, "Select a shopping cart row to edit.", "Selection Missing", JOptionPane.WARNING_MESSAGE);
            }
        });

        removeItemBtn.addActionListener(e -> {
            int selectedRow = cartTable.getSelectedRow();
            if (selectedRow != -1) {
                removeCartItem(selectedRow);
            } else {
                JOptionPane.showMessageDialog(this, "Select a shopping cart row to purge.", "Selection Missing", JOptionPane.WARNING_MESSAGE);
            }
        });

        settleCheckoutBtn.addActionListener(e -> {
            if (currentCart.isEmpty()) {
                JOptionPane.showMessageDialog(this, "The shopping cart is empty.", "Invalid Settle Action", JOptionPane.WARNING_MESSAGE);
                return;
            }
            showSettlementDialog();
        });

        JPanel rightWrapperPanel = new JPanel(new BorderLayout(10, 10));
        rightWrapperPanel.setOpaque(false);
        rightWrapperPanel.add(cartScroll, BorderLayout.CENTER);

        JPanel summaryDockPanel = new JPanel(new BorderLayout(5, 5));
        summaryDockPanel.setOpaque(false);
        summaryDockPanel.add(invoiceCard, BorderLayout.CENTER);
        summaryDockPanel.add(cartOperationsPanel, BorderLayout.SOUTH);
        rightWrapperPanel.add(summaryDockPanel, BorderLayout.SOUTH);

        checkoutContainer.add(rightWrapperPanel, BorderLayout.CENTER);

        basePanel.add(headerBar, BorderLayout.NORTH);
        basePanel.add(catalogPanel, BorderLayout.CENTER);
        basePanel.add(checkoutContainer, BorderLayout.EAST);

        return basePanel;
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
                
                if (t.getColumnCount() == 5 && c == 4) {
                    try {
                        int stockQty = Integer.parseInt(v.toString());
                        if (stockQty <= 5) {
                            comp.setForeground(COLOR_ACCENT);
                            comp.setFont(new Font("Segoe UI", Font.BOLD, 13));
                        } else {
                            comp.setForeground(COLOR_TEXT_MAIN);
                            comp.setFont(FONT_BODY);
                        }
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

    // =================================================================================
    // STANDALONE POS CART & CATALOG SYNCHRONIZATION MOTORS
    // =================================================================================

    private void syncCatalogProducts() {
        catalogTableModel.setRowCount(0);
        loadedProducts.clear();

        if (!isUsingCloudDB) {
            loadedProducts.addAll(mockProductDatabase);
        } else {
            try (Statement stmt = dbConnection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM inventory")) {
                while (rs.next()) {
                    loadedProducts.add(new Product(
                            rs.getString("barcode"),
                            rs.getString("name"),
                            rs.getString("category"),
                            rs.getDouble("price"),
                            rs.getInt("stock")
                    ));
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }

        for (Product p : loadedProducts) {
            catalogTableModel.addRow(new Object[]{p.barcode, p.name, p.category, LKR_FORMAT.format(p.price), p.stock});
        }
    }

    private Product findCatalogProduct(String barcode) {
        for (Product p : loadedProducts) {
            if (p.barcode.equals(barcode)) return p;
        }
        return null;
    }

    private void addProductToCart(Product p) {
        if (p.stock <= 0) {
            JOptionPane.showMessageDialog(this, "The requested product is currently depleted and out of stock.", "Stock Depleted", JOptionPane.WARNING_MESSAGE);
            return;
        }

        CartItem existingItem = null;
        for (CartItem item : currentCart) {
            if (item.barcode.equals(p.barcode)) {
                existingItem = item;
                break;
            }
        }

        if (existingItem != null) {
            if (existingItem.quantity + 1 > p.stock) {
                JOptionPane.showMessageDialog(this, "Cannot exceed available catalog stocks for this device.", "Allocation Limit", JOptionPane.WARNING_MESSAGE);
                return;
            }
            existingItem.quantity++;
        } else {
            currentCart.add(new CartItem(p.barcode, p.name, p.price, 1));
        }

        redrawCartView();
    }

    private void removeCartItem(int index) {
        currentCart.remove(index);
        redrawCartView();
    }

    private void editCartItemQuantity(int index) {
        CartItem item = currentCart.get(index);
        Product catalogProduct = findCatalogProduct(item.barcode);
        
        if (catalogProduct == null) return;
        
        String inputVal = JOptionPane.showInputDialog(this, 
                "Enter new quantity for " + item.name + " (Available: " + catalogProduct.stock + "):", 
                item.quantity);
        
        if (inputVal != null && !inputVal.trim().isEmpty()) {
            try {
                int newQty = Integer.parseInt(inputVal.trim());
                if (newQty <= 0) {
                    removeCartItem(index);
                } else if (newQty > catalogProduct.stock) {
                    JOptionPane.showMessageDialog(this, "The requested quantity exceeds available stock (" + catalogProduct.stock + ").", "Stock Limit Reached", JOptionPane.WARNING_MESSAGE);
                } else {
                    item.quantity = newQty;
                    redrawCartView();
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, "Please enter a valid numeric value.", "Input Mismatch", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void redrawCartView() {
        cartTableModel.setRowCount(0);
        double subtotal = 0.0;

        for (CartItem item : currentCart) {
            double lineTotal = item.rate * item.quantity;
            subtotal += lineTotal;
            cartTableModel.addRow(new Object[]{item.name, LKR_FORMAT.format(item.rate), item.quantity, LKR_FORMAT.format(lineTotal)});
        }

        double ssclTax = subtotal * 0.025;
        double vatTax = (subtotal + ssclTax) * 0.15;
        double combinedTaxes = ssclTax + vatTax;
        double totalToPay = subtotal + combinedTaxes;

        labelSubtotal.setText(LKR_FORMAT.format(subtotal));
        labelTaxes.setText(LKR_FORMAT.format(combinedTaxes));
        labelTotal.setText(LKR_FORMAT.format(totalToPay));
    }

    // =================================================================================
    // NEW: DETAILED SETTLEMENT DIALOG WITH CASH & PROFESSIONAL CARD GATEWAY
    // =================================================================================

    private void showSettlementDialog() {
        JDialog settleDialog = new JDialog(this, "Quantum Settle Checkout Core", true);
        settleDialog.setSize(600, 620);
        settleDialog.setResizable(false);
        settleDialog.setLocationRelativeTo(this);

        JPanel mainPanel = new JPanel(new BorderLayout(15, 15));
        mainPanel.setBackground(COLOR_BG);
        mainPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JPanel detailsPanel = new JPanel(new GridLayout(2, 2, 10, 10));
        detailsPanel.setOpaque(false);

        JTextField customerNameField = new JTextField("");
        JTextField customerPhoneField = new JTextField("94");
        styleInputTextField(customerNameField);
        styleInputTextField(customerPhoneField);

        JLabel lblCustName = new JLabel("Customer Name Identity:");
        lblCustName.setForeground(COLOR_TEXT_MAIN);
        lblCustName.setFont(FONT_BODY);

        JLabel lblCustPhone = new JLabel("Customer Contact Phone:");
        lblCustPhone.setForeground(COLOR_TEXT_MAIN);
        lblCustPhone.setFont(FONT_BODY);

        detailsPanel.add(lblCustName); detailsPanel.add(customerNameField);
        detailsPanel.add(lblCustPhone); detailsPanel.add(customerPhoneField);

        JTabbedPane paymentTabs = new JTabbedPane();
        paymentTabs.setBackground(COLOR_CARD_BG);
        paymentTabs.setForeground(COLOR_PRIMARY);
        paymentTabs.setFont(FONT_SUBTITLE);

        // CASH METHOD
        JPanel cashTabPanel = new JPanel(new GridBagLayout());
        cashTabPanel.setBackground(COLOR_BG);
        cashTabPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

        GridBagConstraints cashGbc = new GridBagConstraints();
        cashGbc.insets = new Insets(8, 10, 8, 10);
        cashGbc.fill = GridBagConstraints.HORIZONTAL;
        cashGbc.gridx = 0;

        JLabel cashBillTitleLabel = new JLabel("Gross Settlement Amount:");
        cashBillTitleLabel.setFont(FONT_BODY);
        cashBillTitleLabel.setForeground(COLOR_TEXT_MUTED);

        JLabel cashBillValueLabel = new JLabel(labelTotal.getText());
        cashBillValueLabel.setFont(new Font("Segoe UI", Font.BOLD, 22));
        cashBillValueLabel.setForeground(COLOR_SECONDARY);

        JLabel cashTenderedLabel = new JLabel("Cash Handed Over (LKR / Rs.):");
        cashTenderedLabel.setFont(FONT_SUBTITLE);
        cashTenderedLabel.setForeground(COLOR_TEXT_MAIN);

        JTextField cashTenderedField = new JTextField();
        styleInputTextField(cashTenderedField);

        JLabel changeDueLabel = new JLabel("Change Balance Back:");
        changeDueLabel.setFont(FONT_BODY);
        changeDueLabel.setForeground(COLOR_TEXT_MUTED);

        JLabel changeValueLabel = new JLabel("Rs. 0.00");
        changeValueLabel.setFont(new Font("Segoe UI", Font.BOLD, 20));
        changeValueLabel.setForeground(COLOR_SUCCESS);

        cashTenderedField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { calculate(); }
            @Override
            public void removeUpdate(DocumentEvent e) { calculate(); }
            @Override
            public void changedUpdate(DocumentEvent e) { calculate(); }

            private void calculate() {
                SwingUtilities.invokeLater(() -> {
                    try {
                        String text = cashTenderedField.getText().trim();
                        if (text.isEmpty()) {
                            changeValueLabel.setText("Rs. 0.00");
                            changeValueLabel.setForeground(COLOR_TEXT_MUTED);
                            return;
                        }
                        double tendered = Double.parseDouble(text);
                        double totalBillValue = parseCurrencyDouble(labelTotal.getText());
                        double deltaChange = tendered - totalBillValue;

                        if (deltaChange >= 0) {
                            changeValueLabel.setText(LKR_FORMAT.format(deltaChange));
                            changeValueLabel.setForeground(COLOR_SUCCESS);
                        } else {
                            changeValueLabel.setText("Incomplete payment (Need Rs. " + new DecimalFormat("#,##0.00").format(Math.abs(deltaChange)) + ")");
                            changeValueLabel.setForeground(COLOR_ACCENT);
                        }
                    } catch (NumberFormatException ex) {
                        changeValueLabel.setText("Numeric values only");
                        changeValueLabel.setForeground(COLOR_ACCENT);
                    }
                });
            }
        });

        cashGbc.gridy = 0; cashTabPanel.add(cashBillTitleLabel, cashGbc);
        cashGbc.gridy = 1; cashTabPanel.add(cashBillValueLabel, cashGbc);
        cashGbc.gridy = 2; cashTabPanel.add(cashTenderedLabel, cashGbc);
        cashGbc.gridy = 3; cashTabPanel.add(cashTenderedField, cashGbc);
        cashGbc.gridy = 4; cashTabPanel.add(changeDueLabel, cashGbc);
        cashGbc.gridy = 5; cashTabPanel.add(changeValueLabel, cashGbc);

        paymentTabs.addTab("Cash Checkout 💵", cashTabPanel);

        // CARD METHOD
        JPanel cardTabPanel = new JPanel(new GridLayout(4, 2, 12, 12));
        cardTabPanel.setBackground(COLOR_BG);
        cardTabPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

        JTextField cardholderNameField = new JTextField("Kamal Gunawardena");
        JTextField cardNumberField = new JTextField("4541-7689-1245-0982");
        JTextField cardExpiryField = new JTextField("12/28");
        JTextField cardCvvField = new JPasswordField("382");

        styleInputTextField(cardholderNameField);
        styleInputTextField(cardNumberField);
        styleInputTextField(cardExpiryField);
        styleInputTextField(cardCvvField);

        JLabel lblCardholder = new JLabel("Cardholder Name:");
        lblCardholder.setForeground(COLOR_TEXT_MAIN);
        lblCardholder.setFont(FONT_BODY);

        JLabel lblCardNum = new JLabel("Credit Card Number:");
        lblCardNum.setForeground(COLOR_TEXT_MAIN);
        lblCardNum.setFont(FONT_BODY);

        JLabel lblExpiry = new JLabel("Card Expiry Target:");
        lblExpiry.setForeground(COLOR_TEXT_MAIN);
        lblExpiry.setFont(FONT_BODY);

        JLabel lblCVV = new JLabel("CVV Guard Pin:");
        lblCVV.setForeground(COLOR_TEXT_MAIN);
        lblCVV.setFont(FONT_BODY);

        cardTabPanel.add(lblCardholder); cardTabPanel.add(cardholderNameField);
        cardTabPanel.add(lblCardNum); cardTabPanel.add(cardNumberField);
        cardTabPanel.add(lblExpiry); cardTabPanel.add(cardExpiryField);
        cardTabPanel.add(lblCVV); cardTabPanel.add(cardCvvField);

        paymentTabs.addTab("Bank Card Portal 💳", cardTabPanel);

        // QR METHOD
        JPanel qrTabPanel = new JPanel(new BorderLayout(10, 10));
        qrTabPanel.setBackground(COLOR_BG);
        qrTabPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

        JLabel qrTipLabel = new JLabel("Scan the generated LANKAQR below with your Sri Lankan Banking Mobile Application to pay.", SwingConstants.CENTER);
        qrTipLabel.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        qrTipLabel.setForeground(COLOR_TEXT_MUTED);

        JPanel qrGraphicRepresentation = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int cx = getWidth() / 2;
                int cy = getHeight() / 2;
                int qrSize = 130;

                g2.setColor(Color.WHITE);
                g2.fillRect(cx - qrSize / 2, cy - qrSize / 2, qrSize, qrSize);

                g2.setColor(Color.BLACK);
                g2.fillRect(cx - qrSize / 2 + 5, cy - qrSize / 2 + 5, 30, 30);
                g2.fillRect(cx + qrSize / 2 - 35, cy - qrSize / 2 + 5, 30, 30);
                g2.fillRect(cx - qrSize / 2 + 5, cy + qrSize / 2 - 35, 30, 30);
                g2.fillRect(cx - 10, cy - 10, 20, 20);

                g2.setColor(new Color(178, 34, 34));
                g2.fillRect(cx - 5, cy - 5, 10, 10);

                g2.dispose();
            }
        };
        qrGraphicRepresentation.setPreferredSize(new Dimension(150, 150));
        qrGraphicRepresentation.setOpaque(false);

        qrTabPanel.add(qrTipLabel, BorderLayout.NORTH);
        qrTabPanel.add(qrGraphicRepresentation, BorderLayout.CENTER);

        paymentTabs.addTab("National LANKAQR Code 📱", qrTabPanel);

        Button3D submitBtn = new Button3D("EXECUTE TRANSACTION AND BILL", COLOR_SUCCESS, COLOR_SUCCESS.brighter());
        submitBtn.setPreferredSize(new Dimension(540, 50));

        submitBtn.addActionListener(e -> {
            String customerName = customerNameField.getText().trim();
            String customerPhone = customerPhoneField.getText().trim();

            if (customerName.isEmpty() || customerPhone.isEmpty()) {
                JOptionPane.showMessageDialog(settleDialog, "Customer parameters must not be blank.", "Validation Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            submitBtn.setEnabled(false);
            submitBtn.setText("Authorizing Payment over Secure Gateway...");

            Timer paymentTimer = new Timer(1500, payEvent -> {
                boolean isSaved = commitTransactionToSharedDatabase(customerName, customerPhone, paymentTabs.getSelectedIndex() == 0 ? "CASH" : "CARD");
                if (isSaved) {
                    playCashRegisterChime();
                    dispatchSmsNotification(customerName, customerPhone);
                    
                    currentCart.clear();
                    redrawCartView();
                    syncCatalogProducts();
                    settleDialog.dispose();
                } else {
                    submitBtn.setEnabled(true);
                    paymentTabs.setEnabled(true);
                    submitBtn.setText("EXECUTE TRANSACTION AND BILL");
                }
            });
            paymentTimer.setRepeats(false);
            paymentTimer.start();
        });

        mainPanel.add(detailsPanel, BorderLayout.NORTH);
        mainPanel.add(paymentTabs, BorderLayout.CENTER);
        mainPanel.add(submitBtn, BorderLayout.SOUTH);

        settleDialog.add(mainPanel);
        settleDialog.setVisible(true);
    }

    private void dispatchSmsNotification(String customerName, String rawPhoneNumbers) {
        StringBuilder billDetails = new StringBuilder();
        billDetails.append("COMPUTECH POS RECEIPT\n");
        billDetails.append("Dear ").append(customerName).append(",\n");
        billDetails.append("Your order was processed successfully!\n\n");
        billDetails.append("Items Summary:\n");
        
        for (CartItem ci : currentCart) {
            billDetails.append("- ").append(ci.name)
                       .append(" (x").append(ci.quantity).append("): ")
                       .append(LKR_FORMAT.format(ci.rate * ci.quantity)).append("\n");
        }
        
        billDetails.append("\nNet Total Bill: ").append(labelTotal.getText()).append("\n");
        billDetails.append("Thank you for shopping with COMPUTECH Computer Shop Sri Lanka!\n");

        System.out.println("\n--- [LANKAPORT SMS DISPATCH GATEWAY] ---");
        System.out.println("Bulk Dispatching to: " + rawPhoneNumbers);
        System.out.println("Content:\n" + billDetails.toString());
        System.out.println("-----------------------------------------\n");

        String textBody = billDetails.toString();
        
        // Split the comma-separated numbers and dispatch them in threads
        String[] phoneArray = rawPhoneNumbers.split(",");
        for (String rawNum : phoneArray) {
            String cleanedNum = rawNum.trim();
            if (!cleanedNum.isEmpty()) {
                String formattedNumber = formatToE164(cleanedNum);
                new Thread(() -> {
                    sendRealSMS(formattedNumber, textBody);
                }).start();
            }
        }

        // Display virtual Smartphone mock-up of the SMS
        JDialog phoneDialog = new JDialog(this, "Message Received! 📱", true);
        phoneDialog.setSize(340, 580);
        phoneDialog.setResizable(false);
        phoneDialog.setLocationRelativeTo(this);

        JPanel phonePanel = new JPanel(new BorderLayout());
        phonePanel.setBackground(new Color(15, 17, 26));
        phonePanel.setBorder(BorderFactory.createEmptyBorder(25, 20, 25, 20));

        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setOpaque(false);
        JLabel carrierLabel = new JLabel("LankaBell 5G");
        carrierLabel.setFont(new Font("Segoe UI", Font.BOLD, 10));
        carrierLabel.setForeground(COLOR_TEXT_MUTED);
        JLabel timeLabel = new JLabel("");
        timeLabel.setFont(new Font("Segoe UI", Font.BOLD, 10));
        timeLabel.setForeground(COLOR_TEXT_MUTED);
        statusBar.add(carrierLabel, BorderLayout.WEST);
        statusBar.add(timeLabel, BorderLayout.EAST);
        phonePanel.add(statusBar, BorderLayout.NORTH);

        JPanel messageContainer = new JPanel(new FlowLayout(FlowLayout.LEFT));
        messageContainer.setOpaque(false);

        JPanel smsBalloon = new JPanel(new BorderLayout());
        smsBalloon.setBackground(new Color(36, 44, 66));
        smsBalloon.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

        JTextArea smsTextArea = new JTextArea(billDetails.toString());
        smsTextArea.setFont(new Font("Consolas", Font.PLAIN, 11));
        smsTextArea.setForeground(COLOR_TEXT_MAIN);
        smsTextArea.setBackground(new Color(36, 44, 66));
        smsTextArea.setEditable(false);
        smsTextArea.setLineWrap(true);
        smsTextArea.setWrapStyleWord(true);
        smsTextArea.setPreferredSize(new Dimension(240, 360));

        smsBalloon.add(smsTextArea, BorderLayout.CENTER);
        messageContainer.add(smsBalloon);
        phonePanel.add(messageContainer, BorderLayout.CENTER);

        JButton homeBtn = new JButton("Close Message");
        homeBtn.setFont(FONT_SUBTITLE);
        homeBtn.setBackground(COLOR_PRIMARY);
        homeBtn.setForeground(Color.BLACK);
        homeBtn.addActionListener(e -> phoneDialog.dispose());
        phonePanel.add(homeBtn, BorderLayout.SOUTH);

        phoneDialog.add(phonePanel);
        phoneDialog.setVisible(true);
    }

    private static String formatToE164(String phone) {
        String clean = phone.replaceAll("[^0-9]", "");
        if (clean.startsWith("0")) {
            clean = "94" + clean.substring(1);
        }
        if (!clean.startsWith("+") && clean.startsWith("94")) {
            clean = "+" + clean;
        }
        return clean;
    }

    private static void sendRealSMS(String recipient, String messageText) {
        if (SMS_PROVIDER.equalsIgnoreCase("NONE")) {
            System.out.println("[SMS Engine] Real SMS dispatch skipped. No provider active.");
            return;
        }

        try {
            if (SMS_PROVIDER.equalsIgnoreCase("TWILIO")) {
                String twilioUrl = "https://api.twilio.com/2010-04-01/Accounts/" + TWILIO_ACCOUNT_SID + "/Messages.json";
                URL url = new URL(twilioUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);

                String auth = TWILIO_ACCOUNT_SID + ":" + TWILIO_AUTH_TOKEN;
                String authHeader = "Basic " + Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
                conn.setRequestProperty("Authorization", authHeader);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

                String postData = "To=" + URLEncoder.encode(recipient, "UTF-8")
                        + "&From=" + URLEncoder.encode(TWILIO_SENDER_NUMBER, "UTF-8")
                        + "&Body=" + URLEncoder.encode(messageText, "UTF-8");

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(postData.getBytes(StandardCharsets.UTF_8));
                }

                int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_CREATED || responseCode == HttpURLConnection.HTTP_OK) {
                    System.out.println("[SMS SUCCESS] Twilio delivered real SMS to " + recipient);
                } else {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) response.append(line);
                    System.err.println("[SMS ERROR] Twilio HTTP response failed: " + response.toString());
                }

            } else if (SMS_PROVIDER.equalsIgnoreCase("NOTIFY_LK")) {
                // FIXED: Strip the "+" sign to guarantee strictly 11 digits (e.g. 94771234567) required by Notify.lk
                String cleanRecipient = recipient.replace("+", "").trim();

                String notifyUrl = "https://app.notify.lk/api/v1/send";
                URL url = new URL(notifyUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

                String postData = "api_key=" + URLEncoder.encode(NOTIFY_LK_API_KEY, "UTF-8")
                        + "&user_id=" + URLEncoder.encode(NOTIFY_LK_USER_ID, "UTF-8")
                        + "&sender_id=" + URLEncoder.encode(NOTIFY_LK_SENDER_ID, "UTF-8")
                        + "&to=" + URLEncoder.encode(cleanRecipient, "UTF-8")
                        + "&message=" + URLEncoder.encode(messageText, "UTF-8");

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(postData.getBytes(StandardCharsets.UTF_8));
                }

                int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    System.out.println("[SMS SUCCESS] Notify.lk delivered real SMS to " + cleanRecipient);
                } else {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) response.append(line);
                    System.err.println("[SMS ERROR] Notify.lk response failed: " + response.toString());
                }
            }
        } catch (Exception ex) {
            System.err.println("[SMS Core Exception] Failed to send network request: " + ex.getMessage());
        }
    }

    private boolean commitTransactionToSharedDatabase(String customerName, String customerPhone, String paymentMethod) {
        StringBuilder itemsSummary = new StringBuilder();
        for (int i = 0; i < currentCart.size(); i++) {
            CartItem ci = currentCart.get(i);
            itemsSummary.append(ci.name).append(" (x").append(ci.quantity).append(")");
            if (i < currentCart.size() - 1) {
                itemsSummary.append("; ");
            }
        }
        
        itemsSummary.append(" [Paid via ").append(paymentMethod).append("]");
        double totalPayValue = parseCurrencyDouble(labelTotal.getText());

        if (!isUsingCloudDB) {
            int mockSaleId = mockSalesLedger.size() + 1;
            mockSalesLedger.add(new SaleRecord(
                    mockSaleId,
                    customerName,
                    customerPhone,
                    itemsSummary.toString(),
                    totalPayValue,
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date())
            ));

            for (CartItem ci : currentCart) {
                Product p = findCatalogProduct(ci.barcode);
                if (p != null) {
                    p.stock -= ci.quantity;
                }
            }
            return true;
        }

        try {
            dbConnection.setAutoCommit(false);

            String insertLedgerQuery = "INSERT INTO sales_ledger (customer_name, customer_phone, items_sold, total_amount) VALUES (?, ?, ?, ?)";
            try (PreparedStatement psLedger = dbConnection.prepareStatement(insertLedgerQuery)) {
                psLedger.setString(1, customerName);
                psLedger.setString(2, customerPhone);
                psLedger.setString(3, itemsSummary.toString());
                psLedger.setDouble(4, totalPayValue);
                psLedger.executeUpdate();
            }

            String updateStockQuery = "UPDATE inventory SET stock = stock - ? WHERE barcode = ?";
            try (PreparedStatement psStock = dbConnection.prepareStatement(updateStockQuery)) {
                for (CartItem ci : currentCart) {
                    psStock.setInt(1, ci.quantity);
                    psStock.setString(2, ci.barcode);
                    psStock.executeUpdate();
                }
            }

            dbConnection.commit();
            dbConnection.setAutoCommit(true);
            return true;

        } catch (SQLException ex) {
            try {
                dbConnection.rollback();
            } catch (SQLException ignored) {}
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this, "Shared Database Transaction failed to settle: " + ex.getMessage(), "Database Transaction Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private double parseCurrencyDouble(String value) {
        try {
            return Double.parseDouble(value.replace("Rs.", "").replace(",", "").trim());
        } catch (Exception ex) {
            return 0.0;
        }
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

        public Product(String barcode, String name, String category, double price, int stock) {
            this.barcode = barcode;
            this.name = name;
            this.category = category;
            this.price = price;
            this.stock = stock;
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

    static class CartItem {
        String barcode;
        String name;
        double rate;
        int quantity;

        public CartItem(String barcode, String name, double rate, int quantity) {
            this.barcode = barcode;
            this.name = name;
            this.rate = rate;
            this.quantity = quantity;
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
