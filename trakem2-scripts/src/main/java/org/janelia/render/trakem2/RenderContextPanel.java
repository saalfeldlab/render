package org.janelia.render.trakem2;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Panel;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

import ini.trakem2.utils.Utils;

import static java.awt.GridBagConstraints.LINE_END;
import static java.awt.GridBagConstraints.LINE_START;

/**
 * TODO: add javadoc
 *
 * @author Eric Trautman
 */
public class RenderContextPanel extends Panel {

    public static final String SAMPLE_PROTOCOL_HOST_AND_PORT = "http://renderer-dev.int.janelia.org:8080";

    public static class DefaultValues {
        final String protocolHostAndPort;
        final String owner;
        final String project;
        final String stack;
        final String tilePattern;
        final String tileId;

        public DefaultValues() {
            this(null, null, null, null, null, null);
        }

        public DefaultValues(final String protocolHostAndPort,
                             final String owner,
                             final String project,
                             final String stack,
                             final String tilePattern,
                             final String tileId) {
            this.protocolHostAndPort = initTextValue(protocolHostAndPort);
            this.owner = initTextValue(owner);
            this.project = initTextValue(project);
            this.stack = initTextValue(stack);
            this.tilePattern = initTextValue(tilePattern);
            this.tileId = initTextValue(tileId);
        }

        private String initTextValue(final String value) {
            String trimmedValue = null;
            if (value != null) {
                trimmedValue = value.trim();
                if (trimmedValue.length() == 0) {
                    trimmedValue = null;
                }
            }
            return trimmedValue;
        }

        public static DefaultValues fromUrlString(final String urlString) {
            final String G_NO_SLASH = "([^/]+)";
            final String G_TILE = "(?:/tile/" + G_NO_SLASH + ")?";
            final String G_STACK = "(?:/stack/" + G_NO_SLASH + G_TILE + ")?";
            final String G_PROJECT = "(?:/project/" + G_NO_SLASH + G_STACK + ")?";
            final String G_OWNER = "(?:/render-ws/v1/owner/" + G_NO_SLASH + G_PROJECT + ")?";
            final Pattern URL_PATTERN = Pattern.compile("(https?://[^/]+)" + G_OWNER);
            final Matcher m = URL_PATTERN.matcher(urlString);
            final int groupCount = m.matches() ? m.groupCount() : -1;
            return new DefaultValues(groupCount >= 1 ?  m.group(1) : null,
                                     groupCount >= 2 ?  m.group(2) : null,
                                     groupCount >= 3 ?  m.group(3) : null,
                                     groupCount >= 4 ?  m.group(4) : null,
                                     groupCount >= 5 ?  m.group(5) : null,
                                     groupCount >= 5 ?  m.group(5) : null);
        }
    }

    private final JTextField protocolHostAndPortTextField;
    private final WebServiceComboBoxPanel ownerComboBoxPanel;
    private final WebServiceComboBoxPanel projectComboBoxPanel;
    private final WebServiceComboBoxPanel stackComboBoxPanel;
    private final JTextField tilePatternTextField;
    private final WebServiceComboBoxPanel tileIdComboBoxPanel;

    public RenderContextPanel(final boolean includeTileIdComboxBox,
                              final DefaultValues defaultValues) {

        final int textFieldColumns = 30;
        this.protocolHostAndPortTextField = new JTextField(textFieldColumns);

        final JButton loadStackDataButton = new JButton(new AbstractAction("Load") {
            @Override
            public void actionPerformed(final ActionEvent e) {
                loadOwnersComboBoxValues();
            }
        });

        this.ownerComboBoxPanel = new WebServiceComboBoxPanel(defaultValues.owner);
        this.projectComboBoxPanel = new WebServiceComboBoxPanel(defaultValues.project);
        this.stackComboBoxPanel = new WebServiceComboBoxPanel(defaultValues.stack);
        this.tilePatternTextField = new JTextField(textFieldColumns);
        this.tileIdComboBoxPanel = new WebServiceComboBoxPanel(defaultValues.tileId);

        this.ownerComboBoxPanel.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) {
                loadComboxBoxValues(getOwnerUrlString() + "/projects",
                                    projectComboBoxPanel,
                                    WebServiceComboBoxPanel.STRING_PARSER);
            } else {
                projectComboBoxPanel.clearValues();
            }
        });

        this.projectComboBoxPanel.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) {
                final String selectedOwner = ownerComboBoxPanel.getSelectedValue();
                final String selectedProject = projectComboBoxPanel.getSelectedValue();
                if ((selectedOwner != null) && (selectedProject != null)) {
                    loadComboxBoxValues(getProjectUrlString() + "/stackIds",
                                        stackComboBoxPanel,
                                        WebServiceComboBoxPanel.STACK_ID_PARSER);
                }
            } else {
                stackComboBoxPanel.clearValues();
                tileIdComboBoxPanel.clearValues();
            }
        });

        this.stackComboBoxPanel.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) {
                loadTileIdComboBoxValues();
            } else {
                tileIdComboBoxPanel.clearValues();
            }
        });

        final GridBagLayout layout = new GridBagLayout();
        final GridBagConstraints c = new GridBagConstraints();
        this.setLayout(layout);

        c.insets = new Insets(0, 0, 5, 5);
        c.anchor = LINE_END;

        c.gridx = 0;
        c.gridy = 0;
        final JLabel protocolHostAndPortLabel = new JLabel("Service Base URL:");
        protocolHostAndPortLabel.setToolTipText("http[s]://<host>:<port>");
        this.add(protocolHostAndPortLabel, c);

        c.gridy = 1;
        this.add(new JLabel("Owner:"), c);

        c.gridy = 2;
        this.add(new JLabel("Project:"), c);

        c.gridy = 3;
        this.add(new JLabel("Stack:"), c);

        if (includeTileIdComboxBox) {
            c.gridy = 4;
            this.add(new JLabel("Tile Pattern:"), c);

            c.gridy = 5;
            this.add(new JLabel("Tile ID:"), c);
        }

        c.insets = new Insets(0, 0, 5, 0);
        c.anchor = LINE_START;

        c.gridx = 1;
        c.gridy = 0;
        this.add(this.protocolHostAndPortTextField, c);

        c.gridx = 2;
        this.add(loadStackDataButton, c);

        c.gridx = 1;
        c.gridy = 1;
        this.add(this.ownerComboBoxPanel, c);

        c.gridy = 2;
        this.add(this.projectComboBoxPanel, c);

        c.gridy = 3;
        this.add(this.stackComboBoxPanel, c);

        if (includeTileIdComboxBox) {
            c.gridy = 4;
            this.add(this.tilePatternTextField, c);

            final JButton findTileIdsButton = new JButton(new AbstractAction("Find Tiles") {
                @Override
                public void actionPerformed(final ActionEvent e) {
                    loadTileIdComboBoxValues();
                }
            });

            c.gridx = 2;
            this.add(findTileIdsButton, c);

            c.gridx = 1;
            c.gridy = 5;
            this.add(this.tileIdComboBoxPanel, c);
        }

        if (defaultValues.tilePattern != null) {
            this.tilePatternTextField.setText(defaultValues.tilePattern.trim());
        }

        if (defaultValues.protocolHostAndPort != null) {
            final String trimmedValue = defaultValues.protocolHostAndPort.trim();
            if (trimmedValue.length() > 0) {
                this.protocolHostAndPortTextField.setText(trimmedValue);
                loadOwnersComboBoxValues();
            }
        } else {
            // if no default, provide sample text to highlight expected pattern but don't load
            this.protocolHostAndPortTextField.setText(SAMPLE_PROTOCOL_HOST_AND_PORT);
        }
    }

    public String getBaseUrlString() {
        final String protocolHostAndPort = protocolHostAndPortTextField.getText().trim();
        return protocolHostAndPort.length() > 0 ? protocolHostAndPortTextField.getText() + "/render-ws/v1" : null;
    }

    public String getOwnerUrlString() {
        final String baseUrl = getBaseUrlString();
        final String selectedOwner = ownerComboBoxPanel.getSelectedValue();
        return (baseUrl == null || selectedOwner == null) ? null : baseUrl + "/owner/" + selectedOwner;
    }

    public String getProjectUrlString() {
        final String ownerUrl = getOwnerUrlString();
        final String selectedProject = projectComboBoxPanel.getSelectedValue();
        return (ownerUrl == null || selectedProject == null) ? null : ownerUrl + "/project/" + selectedProject;
    }

    public String getStackUrlString() {
        final String projectUrl = getProjectUrlString();
        final String selectedStack = stackComboBoxPanel.getSelectedValue();
        return (projectUrl == null || selectedStack == null) ? null : projectUrl + "/stack/" + selectedStack;
    }

    public String getSelectedTileId() {
        return tileIdComboBoxPanel.getSelectedValue();
    }

    public String getTileUrlString() {
        String tileUrlString = null;
        final String stackUrlString = getStackUrlString();
        final String tileId = getSelectedTileId();
        if ((stackUrlString != null) && (tileId != null)) {
            tileUrlString = stackUrlString + "/tile/" + tileId;
        }
        return tileUrlString;
    }

    public DefaultValues buildDefaultValuesFromCurrentSelections() {
        return new DefaultValues(protocolHostAndPortTextField.getText(),
                                 ownerComboBoxPanel.getSelectedValue(),
                                 projectComboBoxPanel.getSelectedValue(),
                                 stackComboBoxPanel.getSelectedValue(),
                                 tilePatternTextField.getText(),
                                 tileIdComboBoxPanel.getSelectedValue());
    }

    private void loadComboxBoxValues(final String urlString,
                                     final WebServiceComboBoxPanel comboBoxPanel,
                                     final WebServiceComboBoxPanel.JsonToStringArrayParser parser) {
        try {
            final URL url = new URL(urlString);
            comboBoxPanel.loadValues(url, parser);
        } catch (final MalformedURLException e) {
            Utils.logStamped("failed to construct URL " + urlString);
            e.printStackTrace();
        }
    }

    private void loadOwnersComboBoxValues() {
        final String baseUrl = getBaseUrlString();
        if (baseUrl != null) {
            loadComboxBoxValues(baseUrl + "/owners",
                                ownerComboBoxPanel,
                                WebServiceComboBoxPanel.STRING_PARSER);
        }
    }

    private void loadTileIdComboBoxValues() {
        final String stackUrl = getStackUrlString();
        final String tilePattern = tilePatternTextField.getText().trim();
        if ((stackUrl != null) && (tilePattern.length() > 0)) {
            loadComboxBoxValues(stackUrl + "/tileIds?matchPattern=" + tilePattern,
                                tileIdComboBoxPanel,
                                WebServiceComboBoxPanel.STRING_PARSER);
        } else {
            tileIdComboBoxPanel.clearValues();
        }
    }

    public static void main(final String[] args) {
        final DefaultValues defaultValues = DefaultValues.fromUrlString(SAMPLE_PROTOCOL_HOST_AND_PORT);
        final Panel renderContextPanel = new RenderContextPanel(true, defaultValues);

        final JFrame frame = new JFrame("RenderContextPanel");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        final JPanel wrapperPanel = new JPanel(new BorderLayout());
        wrapperPanel.setBorder(BorderFactory.createEmptyBorder(10, 10 , 10, 10));
        wrapperPanel.add(renderContextPanel, BorderLayout.CENTER);
        
        frame.getContentPane().add(wrapperPanel, BorderLayout.CENTER);
        frame.pack();
        frame.setVisible(true);
    }

}
