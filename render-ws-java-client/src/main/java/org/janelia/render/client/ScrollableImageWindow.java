package org.janelia.render.client;

import javax.swing.*;
import java.awt.*;

/**
 * Simple scrollable window for viewing an image.
 *
 * @author Eric Trautman
 */
public class ScrollableImageWindow extends JFrame {

    public ScrollableImageWindow(String title,
                                 Image image) {
        super(title);

        final JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.LINE_AXIS));
        final JLabel imageLabel = new JLabel(new ImageIcon(image));
        final JScrollPane imageScrollPane = new JScrollPane(imageLabel);
        contentPanel.add(imageScrollPane);

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        getContentPane().add(contentPanel, BorderLayout.CENTER);
        pack();

        sizeWindow(contentPanel.getSize());
    }

    private void sizeWindow(Dimension preferredSize) {
        final Dimension windowSize = new Dimension(preferredSize);
        final Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        final double screenSizeFactor = 0.8;
        final int scaledScreenWidth = (int) (screenSize.width * screenSizeFactor);
        final int scaledScreenHeight = (int) (screenSize.height * screenSizeFactor);

        final int scrollBarMargin = 30;
        if (windowSize.width > scaledScreenWidth) {
            windowSize.width = scaledScreenWidth;
        } else {
            windowSize.width = windowSize.width + scrollBarMargin; // add margin to get rid of scroll bars
        }

        if (windowSize.height > scaledScreenHeight) {
            windowSize.height = scaledScreenHeight;
        } else {
            windowSize.height = windowSize.height + scrollBarMargin; // add margin to get rid of scroll bars
        }

        setSize(windowSize.width, windowSize.height);
        setPreferredSize(windowSize);
    }

}
