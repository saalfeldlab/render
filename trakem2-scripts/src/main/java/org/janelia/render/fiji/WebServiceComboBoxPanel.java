package org.janelia.render.fiji;

import java.awt.Container;
import java.awt.Dimension;
import java.awt.event.ItemListener;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingWorker;

import mpicbg.util.Timer;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.stack.StackId;

import ini.trakem2.utils.Utils;

/**
 * TODO: add javadoc
 *
 * @author Eric Trautman
 */
public class WebServiceComboBoxPanel
        extends JPanel {

    private final JComboBox<String> comboBox;
    private final JLabel loadingLabel;
    private final String defaultValue;

    public WebServiceComboBoxPanel(final String defaultValue) {

        this.setLayout(new BoxLayout(this, BoxLayout.X_AXIS));

        this.comboBox = new JComboBox<>();
        this.loadingLabel = new JLabel();
        this.defaultValue = defaultValue;

        this.add(this.comboBox);
        this.add(Box.createRigidArea(new Dimension(5, 0)));
        this.add(this.loadingLabel);

    }

    public String getSelectedValue() {
        String selectedValue = null;
        if (comboBox.isEnabled()) {
            selectedValue = (String) comboBox.getSelectedItem();
        }
        return selectedValue;
    }

    public void addItemListener(final ItemListener itemListener) {
        comboBox.addItemListener(itemListener);
    }

    public void clearValues() {
        comboBox.setModel(new DefaultComboBoxModel<>());
        comboBox.setSelectedIndex(-1);
    }

    public void loadValues(final URL url,
                           final JsonToStringArrayParser parser) {

        final SwingWorker<String[], Void> worker = new SwingWorker<String[], Void>() {
            @Override
            protected String[] doInBackground()
                    throws Exception {
                final Timer timer = new Timer();
                timer.start();
                final String[] parsedValues = parser.getAndParse(url);
                Utils.logStamped("loaded " + parsedValues.length + " values from " + url +
                          " in " + timer.stop() + " ms");
                return parsedValues;
            }

            @Override
            protected void done() {
                try {
                    String[] parsedValues = this.get();
                    final boolean isEmptyValueList = parsedValues.length == 0;
                    if (parsedValues.length == 0) {
                        parsedValues = new String[] { "?" };
                    }
                    final DefaultComboBoxModel<String> updatedModel = new DefaultComboBoxModel<>(parsedValues);
                    final String currentlySelectedItem = (String) comboBox.getSelectedItem();
                    comboBox.setModel(updatedModel);
                    comboBox.setEnabled(! isEmptyValueList);

                    if ((currentlySelectedItem != null) && (updatedModel.getIndexOf(currentlySelectedItem) > -1)) {
                        comboBox.setSelectedItem(currentlySelectedItem);
                    } else if ((defaultValue != null) && (updatedModel.getIndexOf(defaultValue) > -1)) {
                        comboBox.setSelectedItem(defaultValue);
                    } else {
                        // force ItemEvent to be sent
                        comboBox.setSelectedItem(null);
                        comboBox.setSelectedItem(parsedValues[0]);
                    }

                    loadingLabel.setVisible(false);
                } catch (final Exception e) {
                    loadingLabel.setText("load failed");
                    clearValues();
                    Utils.logStamped("failed to load values from " + url);
                    e.printStackTrace();
                }
            }
        };

        loadingLabel.setText("loading");
        loadingLabel.setVisible(true);
        worker.execute();
    }

    public interface JsonToStringArrayParser {
        String[] getAndParse(final URL url) throws Exception;
    }

    public static final JsonToStringArrayParser STRING_PARSER = url -> {
        final String[] parsedValues;
        try (final InputStreamReader reader = new InputStreamReader(url.openStream())) {
            parsedValues = JsonUtils.FAST_MAPPER.readValue(reader, String[].class);
        } catch (final IOException e) {
            throw new Exception("failed to load values from " + url, e);
        }
        return parsedValues;
    };

    public static final JsonToStringArrayParser STACK_ID_PARSER = url -> {
        final StackId[] parsedStackIds;
        try (final InputStreamReader reader = new InputStreamReader(url.openStream())) {
            parsedStackIds = JsonUtils.FAST_MAPPER.readValue(reader, StackId[].class);
        } catch (final IOException e) {
            throw new Exception("failed to load values from " + url, e);
        }
        final String[] parsedValues = new String[parsedStackIds.length];
        Arrays.stream(parsedStackIds).map(StackId::getStack).collect(Collectors.toList()).toArray(parsedValues);
        return parsedValues;
    };

    public static void main(final String[] args)
            throws MalformedURLException {
        final JFrame frame = new JFrame("WebServiceComboBoxPanel");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        final WebServiceComboBoxPanel panel = new WebServiceComboBoxPanel(null);
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10 , 10, 10));

        final Container pane = frame.getContentPane();
        pane.setLayout(new BoxLayout(pane, BoxLayout.X_AXIS));
        pane.add(panel);
        final Dimension size = new Dimension(400, 100);
        pane.add(new Box.Filler(size, size, size));

        frame.pack();
        frame.setVisible(true);

        panel.loadValues(new URL("http://renderer-dev:8080/render-ws/v1/owners"), STRING_PARSER);
    }


}
