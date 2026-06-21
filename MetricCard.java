package com.computershop.inventory;

import javax.swing.*;
import java.awt.*;

/**
 * Simple dashboard card for showing metrics like Total Items, Sales, etc
 */
public class MetricCard extends JPanel {
    
    private JLabel titleLabel;
    private JLabel valueLabel;
    private JLabel iconLabel;
    
    public MetricCard(String title, String value, Color bgColor) {
        setLayout(new BorderLayout(10, 5));
        setBackground(bgColor);
        setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        setPreferredSize(new Dimension(200, 100));
        String icon = null;
        
        // Icon on left
        iconLabel = new JLabel(icon);
        iconLabel.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 36));
        iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
        add(iconLabel, BorderLayout.WEST);
        
        // Text panel on right
        JPanel textPanel = new JPanel();
        textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
        textPanel.setOpaque(false);
        
        titleLabel = new JLabel(title);
        titleLabel.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        titleLabel.setForeground(Color.DARK_GRAY);
        
        valueLabel = new JLabel(value);
        valueLabel.setFont(new Font("Segoe UI", Font.BOLD, 24));
        valueLabel.setForeground(Color.BLACK);
        
        textPanel.add(titleLabel);
        textPanel.add(Box.createVerticalStrut(5));
        textPanel.add(valueLabel);
        
        add(textPanel, BorderLayout.CENTER);
        
        // Rounded corners effect
        setOpaque(false);
    }
    
    // Value update කරන method එක
    public void setValue(String newValue) {
        valueLabel.setText(newValue);
    }
    
    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(getBackground());
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);
        g2.dispose();
        super.paintComponent(g);
    }
}