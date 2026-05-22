package com.bdic.admin;

import com.bdic.crypto.ClientKeyManager;
import com.bdic.crypto.DESUtil;
import com.bdic.crypto.PEKSUtil;
import com.bdic.model.DocumentSummary;
import com.bdic.model.EncryptedData;
import com.bdic.model.ServerResponse;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Search page controller for the keyword-search UI and asynchronous search workflow.
 */
public class SearchPanelController {
    /** Maximum image thumbnail width in search results. */
    private static final int PREVIEW_THUMB_MAX_WIDTH = 180;
    /** Maximum image thumbnail height in search results. */
    private static final int PREVIEW_THUMB_MAX_HEIGHT = 130;
    /** Maximum number of PDF preview pages to render to avoid exhausting memory on very large files. */
    private static final int MAX_PDF_PREVIEW_PAGES = 20;
    /** Maximum number of rows shown per sheet in Excel previews. */
    private static final int MAX_SPREADSHEET_PREVIEW_ROWS = 200;
    /** Maximum number of columns shown per sheet in Excel previews. */
    private static final int MAX_SPREADSHEET_PREVIEW_COLUMNS = 50;


    /** Main window used for dialog positioning. */
    private final JFrame owner;
    /** Client used to communicate with the server. */
    private final DocumentServiceClient serviceClient;
    /** Service for local decryption and file-type detection. */
    private final DocumentOperationService operationService;
    /** Local keys for the current user. */
    private final ClientKeyManager.KeyBundle keyBundle;
    /** Global busy-state manager. */
    private UiBusyStateManager busyStateManager;

    /** Search keyword input field. */
    private JTextField searchField;
    /** Container for search result cards. */
    private JPanel resultListPanel;
    /** Button that triggers search. */
    private JButton searchButton;
    /** Scroll area for results. */
    private JScrollPane imagePreviewScrollPane;
    /** Search page status text. */
    private JLabel searchStatusLabel;
    /** Search page progress bar. */
    private JProgressBar searchProgressBar;
    /** Image previews from the current search results that were decrypted and scaled in the background. */
    private final Map<String, ImagePreview> imagePreviewCache = new HashMap<>();

    /**
     * Creates the search page controller.
     */
    public SearchPanelController(
            JFrame owner,
            DocumentServiceClient serviceClient,
            DocumentOperationService operationService,
            ClientKeyManager.KeyBundle keyBundle
    ) {
        this.owner = owner;
        this.serviceClient = serviceClient;
        this.operationService = operationService;
        this.keyBundle = keyBundle;
    }

    /**
     * Creates the full search page UI.
     */
    public JPanel createPanel() {
        JPanel searchPanel = new JPanel(new BorderLayout(10, 10));
        searchPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // The top search bar supports both button search and pressing Enter in the input field.
        JPanel searchFormPanel = new JPanel(new BorderLayout(8, 8));
        searchFormPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        searchFormPanel.add(new JLabel("Search Keyword"), BorderLayout.WEST);
        searchField = new JTextField();
        searchField.addActionListener(e -> handleSearch());
        searchFormPanel.add(searchField, BorderLayout.CENTER);

        searchButton = new JButton("Search");
        searchButton.addActionListener(e -> handleSearch());
        UiComponentFactory.stylePrimaryButton(searchButton);
        searchFormPanel.add(searchButton, BorderLayout.EAST);
        searchPanel.add(UiComponentFactory.createSectionPanel("Keyword Search", "Search by keyword prefix, file name, or extracted document text.", searchFormPanel), BorderLayout.NORTH);

        // The center result area shows vertical cards, each of which may include an image thumbnail or text preview.
        resultListPanel = new JPanel();
        resultListPanel.setLayout(new BoxLayout(resultListPanel, BoxLayout.Y_AXIS));
        resultListPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        imagePreviewScrollPane = new JScrollPane(resultListPanel);
        imagePreviewScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        imagePreviewScrollPane.getVerticalScrollBar().setUnitIncrement(18);

        JPanel resultContentPanel = new JPanel(new BorderLayout(8, 8));
        resultContentPanel.add(imagePreviewScrollPane, BorderLayout.CENTER);
        searchPanel.add(UiComponentFactory.createSectionPanel("Search Results", "Matched documents and text previews are shown here.", resultContentPanel), BorderLayout.CENTER);

        searchStatusLabel = new JLabel(" ");
        searchStatusLabel.setForeground(new Color(75, 85, 99));
        searchProgressBar = new JProgressBar();
        searchProgressBar.setIndeterminate(true);
        searchProgressBar.setVisible(false);
        searchProgressBar.setPreferredSize(new Dimension(160, 18));

        JPanel searchStatusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        searchStatusPanel.add(searchProgressBar);
        searchStatusPanel.add(Box.createHorizontalStrut(8));
        searchStatusPanel.add(searchStatusLabel);
        searchPanel.add(searchStatusPanel, BorderLayout.SOUTH);
        return searchPanel;
    }

    /** Injects the busy-state manager. */
    public void setBusyStateManager(UiBusyStateManager busyStateManager) {
        this.busyStateManager = busyStateManager;
    }

    /** Returns the search page status label. */
    public JLabel getStatusLabel() {
        return searchStatusLabel;
    }

    /** Returns the search page progress bar. */
    public JProgressBar getProgressBar() {
        return searchProgressBar;
    }

    /** Returns controls that must be disabled during background search. */
    public List<JComponent> getBusySensitiveComponents() {
        List<JComponent> components = new ArrayList<>();
        components.add(searchButton);
        components.add(searchField);
        return components;
    }

    /**
     * Handles search actions: generates a trapdoor, calls server search, and renders results when complete.
     */
    private void handleSearch() {
        if (busyStateManager == null || busyStateManager.isBusy()) {
            return;
        }

        final String keyword = searchField.getText().trim().toLowerCase(Locale.ROOT);
        if (keyword.isEmpty()) {
            JOptionPane.showMessageDialog(owner, "Please enter a keyword.", "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }

        busyStateManager.setSearchBusy(true, "Searching for \"" + keyword + "\"...");
        // Search and any required preview downloads run in a background thread to avoid blocking the Swing event thread.
        new SwingWorker<SearchTaskResult, Void>() {
            @Override
            protected SearchTaskResult doInBackground() {
                try {
                    // The client first converts the keyword into a trapdoor with the search private key; the server only uses the trapdoor for ciphertext matching.
                    byte[] trapdoor = PEKSUtil.getTrapdoor(keyBundle.peksPrivateKey(), keyword);
                    ServerResponse response = serviceClient.search(trapdoor);
                    if (!response.isSuccess()) {
                        return SearchTaskResult.failure(formatFailedResponse("Search", response));
                    }

                    if (response.getData() instanceof List<?> rawResults) {
                        List<EncryptedData> parsedResults = castSearchResults(rawResults);
                        // Besides encrypted-keyword matching, use docId/file-name fuzzy matching as a usability fallback.
                        List<EncryptedData> fallbackResults = searchByDocIdOrFileName(keyword);
                        List<EncryptedData> mergedResults = mergeByDocId(parsedResults, fallbackResults);
                        return SearchTaskResult.success(mergedResults, buildImagePreviews(mergedResults));
                    }
                    return SearchTaskResult.failure("Search failed: unexpected response from server.");
                } catch (Exception ex) {
                    ex.printStackTrace();
                    return SearchTaskResult.failure("Search failed: " + DocumentOperationService.describeException(ex));
                }
            }

            @Override
            protected void done() {
                SearchTaskResult result;
                try {
                    result = get();
                } catch (Exception ex) {
                    ex.printStackTrace();
                    result = SearchTaskResult.failure("Search failed: " + DocumentOperationService.describeException(ex));
                }

                if (busyStateManager != null) {
                    busyStateManager.setSearchBusy(false, " ");
                }
                if (result.errorMessage() != null) {
                    imagePreviewCache.clear();
                    JOptionPane.showMessageDialog(owner, result.errorMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                imagePreviewCache.clear();
                imagePreviewCache.putAll(result.imagePreviews());
                renderSearchResults(result.results());
            }
        }.execute();
    }

    /**
     * Converts the raw list returned by the server into an EncryptedData list.
     */
    private List<EncryptedData> castSearchResults(List<?> rawResults) {
        List<EncryptedData> results = new ArrayList<>();
        for (Object rawResult : rawResults) {
            results.add((EncryptedData) rawResult);
        }
        return results;
    }

    /**
     * Converts a failed server response into a user-readable error message.
     */
    private String formatFailedResponse(String action, ServerResponse response) {
        String message = response == null ? null : response.getMessage();
        if (message == null || message.isBlank()) {
            return action + " failed.";
        }
        return action + " failed: " + message;
    }

    /**
     * Uses the document list for docId/file-name fuzzy matching, then downloads full documents for matched items.
     */
    private List<EncryptedData> searchByDocIdOrFileName(String keyword) throws Exception {
        ServerResponse listResponse = serviceClient.listDocuments();
        if (!listResponse.isSuccess() || !(listResponse.getData() instanceof List<?> rawSummaries)) {
            return Collections.emptyList();
        }

        List<EncryptedData> matched = new ArrayList<>();
        String normalizedKeyword = keyword.toLowerCase(Locale.ROOT);
        for (Object rawSummary : rawSummaries) {
            if (!(rawSummary instanceof DocumentSummary summary)) {
                continue;
            }
            String docId = summary.getDocId() == null ? "" : summary.getDocId().toLowerCase(Locale.ROOT);
            String fileName = summary.getFileName() == null ? "" : summary.getFileName().toLowerCase(Locale.ROOT);
            if (!docId.contains(normalizedKeyword) && !fileName.contains(normalizedKeyword)) {
                continue;
            }
            // Summaries do not include encrypted content, so a full document object is downloaded after a match.
            ServerResponse downloadResponse = serviceClient.downloadDocument(summary.getDocId());
            if (downloadResponse.isSuccess() && downloadResponse.getData() instanceof EncryptedData data) {
                matched.add(data);
            }
        }
        return matched;
    }

    /**
     * Merges two result sets and deduplicates by docId.
     */
    private List<EncryptedData> mergeByDocId(List<EncryptedData> primary, List<EncryptedData> secondary) {
        List<EncryptedData> merged = new ArrayList<>(primary);
        for (EncryptedData candidate : secondary) {
            if (candidate == null || candidate.getDocId() == null) {
                continue;
            }
            boolean exists = false;
            for (EncryptedData existing : merged) {
                if (existing != null && candidate.getDocId().equals(existing.getDocId())) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                merged.add(candidate);
            }
        }
        return merged;
    }

    /**
     * Ensures encrypted content needed for preview exists; downloads the full document in the background for lightweight search results.
     */
    private EncryptedData downloadPreviewContent(EncryptedData data) throws Exception {
        if (data.getEncryptedContent() != null && data.getEncryptedContent().length > 0) {
            return data;
        }
        ServerResponse response = serviceClient.downloadDocument(data.getDocId());
        if (!response.isSuccess() || !(response.getData() instanceof EncryptedData downloaded)) {
            return data;
        }
        return downloaded;
    }

    /**
     * Downloads and decrypts image previews inside the background search task to avoid blocking the EDT during result rendering.
     */
    private Map<String, ImagePreview> buildImagePreviews(List<EncryptedData> results) {
        Map<String, ImagePreview> previews = new HashMap<>();
        for (EncryptedData data : results) {
            if (!isPreviewableImage(data) || data.getDocId() == null) {
                continue;
            }

            try {
                EncryptedData previewSource = downloadPreviewContent(data);
                if (previewSource.getEncryptedContent() == null) {
                    continue;
                }
                byte[] decryptedBytes = DESUtil.decrypt(previewSource.getEncryptedContent(), keyBundle.desKey());
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(decryptedBytes));
                if (image != null) {
                    previews.put(data.getDocId(), new ImagePreview(
                            image,
                            createThumbnailIcon(image, PREVIEW_THUMB_MAX_WIDTH, PREVIEW_THUMB_MAX_HEIGHT)
                    ));
                }
            } catch (Exception ignored) {
                // Image preview failures do not affect display of the search result itself.
            }
        }
        return previews;
    }

    /**
     * Checks whether a search result can display an image preview.
     */
    private boolean isPreviewableImage(EncryptedData data) {
        if (data == null) {
            return false;
        }
        String mimeType = data.getMimeType() == null ? "" : data.getMimeType().toLowerCase(Locale.ROOT);
        String mediaType = data.getMediaType() == null ? "" : data.getMediaType().toLowerCase(Locale.ROOT);
        return mimeType.startsWith("image/") || mediaType.contains("image");
    }

    /**
     * Clears and rerenders the search result list.
     */
    private void renderSearchResults(List<EncryptedData> results) {
        resultListPanel.removeAll();

        JLabel summaryLabel = new JLabel("Found " + results.size() + " documents.");
        summaryLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        resultListPanel.add(summaryLabel);

        if (results.isEmpty()) {
            resultListPanel.add(new JLabel("No matched documents."));
            refreshResultList();
            return;
        }

        // Each result is shown as a separate card with fixed vertical spacing between cards.
        for (EncryptedData result : results) {
            resultListPanel.add(buildResultCard(result));
            resultListPanel.add(Box.createVerticalStrut(8));
        }
        refreshResultList();
    }

    private void refreshResultList() {
        UiScaleManager.reapplyCurrentScale(owner, resultListPanel);
        resultListPanel.revalidate();
        resultListPanel.repaint();
    }

    /**
     * Builds a single search result card.
     */
    private JComponent buildResultCard(EncryptedData data) {
        JPanel card = new JPanel(new BorderLayout(6, 6));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(229, 231, 235)),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)
        ));

        JComponent previewComponent = buildPreviewTriggerComponent(data);
        if (previewComponent != null) {
            // The file preview entry sits at the top of the card, with text information below it.
            JPanel previewRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            previewRow.setOpaque(false);
            previewRow.add(previewComponent);
            card.add(previewRow, BorderLayout.NORTH);
        }

        JTextArea infoArea = new JTextArea();
        Font resultFont = UIManager.getFont("Label.font");
        if (resultFont != null) {
            infoArea.setFont(resultFont);
        }
        infoArea.setEditable(false);
        infoArea.setOpaque(false);
        infoArea.setLineWrap(true);
        infoArea.setWrapStyleWord(true);
        infoArea.setBorder(null);
        infoArea.setText(buildResultInfoText(data));
        card.add(infoArea, BorderLayout.CENTER);
        return card;
    }

    /**
     * Generates the text description in a result card, including metadata, description, and optional content preview.
     */
    private String buildResultInfoText(EncryptedData result) {
        StringBuilder sb = new StringBuilder();
        sb.append("DocID: ").append(result.getDocId()).append('\n');
        sb.append("File: ").append(result.getFileName()).append('\n');
        sb.append("Type: ").append(result.getMediaType()).append(" / ").append(result.getMimeType()).append('\n');
        sb.append("Size: ").append(result.getFileSize()).append(" bytes\n");
        String description = extractDescription(result);
        if (!description.isBlank()) {
            sb.append("Description: ").append(description).append('\n');
        }

        if (operationService.isTextDocument(result)) {
            if (result.getEncryptedContent() == null) {
                sb.append("Content: Preview unavailable. Use the Download action to inspect the original file.");
                return sb.toString();
            }
            try {
                // Text previews are decrypted locally on the client; the server still never sees plaintext content.
                byte[] decryptedBytes = DESUtil.decrypt(result.getEncryptedContent(), keyBundle.desKey());
                String content = new String(decryptedBytes, StandardCharsets.UTF_8);
                sb.append("Content: ").append(truncate(content, 600));
            } catch (Exception exception) {
                sb.append("Content: Preview failed - ").append(DocumentOperationService.describeException(exception));
            }
        } else {
            sb.append("Content: Click the file icon to preview when supported.");
        }
        return sb.toString();
    }

    /**
     * Restores the user-entered description from encrypted keyword metadata.
     */
    private String extractDescription(EncryptedData data) {
        if (data.getEncryptedKeywordMetadata() == null || data.getEncryptedKeywordMetadata().length == 0) {
            return "";
        }
        try {
            byte[] decryptedMetadata = DESUtil.decrypt(data.getEncryptedKeywordMetadata(), keyBundle.desKey());
            String metadataText = new String(decryptedMetadata, StandardCharsets.UTF_8);
            String prefix = DocumentOperationService.DESCRIPTION_METADATA_PREFIX;
            // Description lines use a fixed prefix; subsequent normal keyword lines are skipped.
            for (String line : metadataText.split("\\R")) {
                String value = line == null ? "" : line.trim();
                if (!value.startsWith(prefix)) {
                    continue;
                }
                String encoded = value.substring(prefix.length()).trim();
                if (encoded.isEmpty()) {
                    return "";
                }
                return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            return "";
        }
        return "";
    }

    /**
     * Truncates long text to prevent large documents from making result cards too tall.
     */
    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    /**
     * Creates a clickable preview entry for a search result. Images use thumbnails; other files use type icons.
     */
    private JComponent buildPreviewTriggerComponent(EncryptedData data) {
        if (data == null) {
            return null;
        }

        if (!isPreviewableImage(data)) {
            return buildFileIconComponent(data);
        }

        ImagePreview imagePreview = imagePreviewCache.get(data.getDocId());
        if (imagePreview == null) {
            return buildFileIconComponent(data);
        }

        JLabel iconLabel = new JLabel(imagePreview.thumbnailIcon());
        iconLabel.setBorder(BorderFactory.createLineBorder(new Color(209, 213, 219)));
        iconLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        iconLabel.setToolTipText("Click to open full preview");
        iconLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                // Clicking a thumbnail opens a modal full-image preview.
                showImagePreviewDialog(data.getFileName(), imagePreview.fullImage());
            }
        });
        return iconLabel;
    }

    /**
     * Creates a type icon for non-image files; clicking it loads the preview in the background.
     */
    private JComponent buildFileIconComponent(EncryptedData data) {
        JLabel iconLabel = new JLabel(new FileTypeIcon(fileTypeLabel(data), fileTypeColor(data)));
        iconLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        iconLabel.setToolTipText("Click to preview");
        iconLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                openDocumentPreview(data);
            }
        });
        return iconLabel;
    }

    /**
     * Downloads and decrypts the file in the background, then opens the preview window based on file type.
     */
    private void openDocumentPreview(EncryptedData data) {
        if (data == null || busyStateManager == null || busyStateManager.isBusy()) {
            return;
        }

        busyStateManager.setSearchBusy(true, "Opening preview for " + displayFileName(data) + "...");
        new SwingWorker<DocumentPreview, Void>() {
            @Override
            protected DocumentPreview doInBackground() {
                try {
                    return loadDocumentPreview(data);
                } catch (Exception exception) {
                    return DocumentPreview.message(
                            "Preview - " + displayFileName(data),
                            "Preview failed: " + DocumentOperationService.describeException(exception)
                    );
                }
            }

            @Override
            protected void done() {
                DocumentPreview preview;
                try {
                    preview = get();
                } catch (Exception exception) {
                    preview = DocumentPreview.message(
                            "Preview - " + displayFileName(data),
                            "Preview failed: " + DocumentOperationService.describeException(exception)
                    );
                }

                if (busyStateManager != null) {
                    busyStateManager.setSearchBusy(false, " ");
                }
                showDocumentPreviewDialog(preview);
            }
        }.execute();
    }

    /**
     * Builds preview data. This method runs in a background thread to avoid blocking the UI with decryption, PDF rendering, or Excel parsing.
     */
    private DocumentPreview loadDocumentPreview(EncryptedData data) throws Exception {
        EncryptedData previewSource = downloadPreviewContent(data);
        if (previewSource.getEncryptedContent() == null || previewSource.getEncryptedContent().length == 0) {
            return DocumentPreview.message("Preview - " + displayFileName(data), "No preview content is available.");
        }

        byte[] decryptedBytes = DESUtil.decrypt(previewSource.getEncryptedContent(), keyBundle.desKey());
        String title = "Preview - " + displayFileName(previewSource);
        if (isPreviewableImage(previewSource)) {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(decryptedBytes));
            if (image == null) {
                return DocumentPreview.message(title, "This image could not be decoded.");
            }
            return DocumentPreview.image(title, image);
        }
        if (isPdfDocument(previewSource)) {
            return DocumentPreview.pdf(title, renderPdfPages(decryptedBytes));
        }
        if (isSpreadsheetDocument(previewSource)) {
            return DocumentPreview.spreadsheet(title, readSpreadsheetSheets(decryptedBytes));
        }
        if (isWordDocument(previewSource)) {
            return DocumentPreview.text(title, extractWordPreviewText(previewSource, decryptedBytes));
        }
        if (operationService.isTextDocument(previewSource)) {
            return DocumentPreview.text(title, new String(decryptedBytes, StandardCharsets.UTF_8));
        }
        return DocumentPreview.message(title, "Preview is not available for this file type. Use Download to open the original file.");
    }

    /**
     * Shows a preview dialog for any supported file.
     */
    private void showDocumentPreviewDialog(DocumentPreview preview) {
        JDialog dialog = new JDialog(owner, preview.title(), true);
        dialog.setLayout(new BorderLayout());
        dialog.add(buildPreviewContent(preview), BorderLayout.CENTER);
        dialog.pack();
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        int width = Math.min(Math.max(900, (int) Math.round(screenSize.width * 0.76d)), screenSize.width - 100);
        int height = Math.min(Math.max(640, (int) Math.round(screenSize.height * 0.78d)), screenSize.height - 100);
        dialog.setSize(width, height);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    /**
     * Creates a Swing content component based on preview type.
     */
    private JComponent buildPreviewContent(DocumentPreview preview) {
        return switch (preview.kind()) {
            case IMAGE -> buildImagePreviewContent(preview.image());
            case PDF -> buildPdfPreviewContent(preview.pdfPages());
            case SPREADSHEET -> buildSpreadsheetPreviewContent(preview.sheets());
            case TEXT -> buildTextPreviewContent(preview.text());
            case MESSAGE -> buildMessagePreviewContent(preview.text());
        };
    }

    private JComponent buildImagePreviewContent(BufferedImage image) {
        return new ImagePreviewComponent(image);
    }

    private JComponent buildPdfPreviewContent(List<BufferedImage> pages) {
        return new PdfPreviewComponent(pages);
    }

    private JComponent buildSpreadsheetPreviewContent(List<SheetPreview> sheets) {
        JTabbedPane tabbedPane = new JTabbedPane();
        if (sheets.isEmpty()) {
            tabbedPane.addTab("Sheet", buildMessagePreviewContent("No spreadsheet data could be read."));
            return tabbedPane;
        }
        for (SheetPreview sheet : sheets) {
            DefaultTableModel model = new DefaultTableModel(sheet.columnNames().toArray(), 0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
            for (List<String> row : sheet.rows()) {
                model.addRow(row.toArray());
            }
            JTable table = new JTable(model);
            table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
            table.setFillsViewportHeight(true);
            table.setRowHeight(24);
            tabbedPane.addTab(sheet.name(), new JScrollPane(table));
        }
        return tabbedPane;
    }

    private JComponent buildTextPreviewContent(String text) {
        JTextArea textArea = new JTextArea(text == null ? "" : text);
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setMargin(new Insets(12, 12, 12, 12));
        textArea.setCaretPosition(0);
        return new JScrollPane(textArea);
    }

    private JComponent buildMessagePreviewContent(String message) {
        JTextArea textArea = new JTextArea(message == null ? "" : message);
        textArea.setEditable(false);
        textArea.setOpaque(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        return textArea;
    }

    /**
     * Renders PDF pages into images with PDFBox.
     */
    private List<BufferedImage> renderPdfPages(byte[] content) throws Exception {
        List<BufferedImage> pages = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(content)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int pageCount = Math.min(document.getNumberOfPages(), MAX_PDF_PREVIEW_PAGES);
            for (int i = 0; i < pageCount; i++) {
                pages.add(renderer.renderImageWithDPI(i, 110, ImageType.RGB));
            }
        }
        return pages;
    }

    /**
     * Reads an Excel workbook and converts it into table preview data.
     */
    private List<SheetPreview> readSpreadsheetSheets(byte[] content) throws Exception {
        List<SheetPreview> previews = new ArrayList<>();
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                int rowLimit = Math.min(sheet.getLastRowNum() + 1, MAX_SPREADSHEET_PREVIEW_ROWS);
                int columnCount = detectPreviewColumnCount(sheet, rowLimit);
                List<String> columnNames = new ArrayList<>();
                for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
                    columnNames.add(excelColumnName(columnIndex));
                }

                List<List<String>> rows = new ArrayList<>();
                for (int rowIndex = 0; rowIndex < rowLimit; rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    List<String> values = new ArrayList<>();
                    for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
                        values.add(row == null ? "" : formatter.formatCellValue(row.getCell(columnIndex)));
                    }
                    rows.add(values);
                }
                previews.add(new SheetPreview(sheet.getSheetName(), columnNames, rows));
            }
        }
        return previews;
    }

    /**
     * Estimates the number of columns that should be shown in the preview.
     */
    private int detectPreviewColumnCount(Sheet sheet, int rowLimit) {
        int maxColumns = 1;
        for (int rowIndex = 0; rowIndex < rowLimit; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row != null) {
                maxColumns = Math.max(maxColumns, row.getLastCellNum());
            }
        }
        return Math.min(Math.max(1, maxColumns), MAX_SPREADSHEET_PREVIEW_COLUMNS);
    }

    /**
     * Converts a zero-based column index to an Excel-style column name.
     */
    private String excelColumnName(int columnIndex) {
        StringBuilder name = new StringBuilder();
        int value = columnIndex + 1;
        while (value > 0) {
            int remainder = (value - 1) % 26;
            name.insert(0, (char) ('A' + remainder));
            value = (value - 1) / 26;
        }
        return name.toString();
    }

    /**
     * Extracts readable text from a Word document.
     */
    private String extractWordPreviewText(EncryptedData data, byte[] content) throws Exception {
        if (hasExtension(data, ".docx")) {
            try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content));
                 XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
                return extractor.getText();
            }
        }

        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(content));
             WordExtractor extractor = new WordExtractor(document)) {
            return extractor.getText();
        }
    }

    private boolean isPdfDocument(EncryptedData data) {
        return hasMimeType(data, "application/pdf") || hasExtension(data, ".pdf");
    }

    private boolean isSpreadsheetDocument(EncryptedData data) {
        String mimeType = normalizedMimeType(data);
        return mimeType.contains("spreadsheet")
                || mimeType.contains("excel")
                || hasExtension(data, ".xls")
                || hasExtension(data, ".xlsx");
    }

    private boolean isWordDocument(EncryptedData data) {
        String mimeType = normalizedMimeType(data);
        return mimeType.contains("word")
                || hasExtension(data, ".doc")
                || hasExtension(data, ".docx");
    }

    private boolean hasMimeType(EncryptedData data, String expectedMimeType) {
        return expectedMimeType.equals(normalizedMimeType(data));
    }

    private String normalizedMimeType(EncryptedData data) {
        return data == null || data.getMimeType() == null ? "" : data.getMimeType().toLowerCase(Locale.ROOT);
    }

    private boolean hasExtension(EncryptedData data, String extension) {
        String fileName = data == null || data.getFileName() == null ? "" : data.getFileName().toLowerCase(Locale.ROOT);
        return fileName.endsWith(extension);
    }

    private String displayFileName(EncryptedData data) {
        if (data == null || data.getFileName() == null || data.getFileName().isBlank()) {
            return data == null ? "file" : data.getDocId();
        }
        return data.getFileName();
    }

    private String fileTypeLabel(EncryptedData data) {
        if (isPreviewableImage(data)) {
            return "IMG";
        }
        if (isPdfDocument(data)) {
            return "PDF";
        }
        if (isSpreadsheetDocument(data)) {
            return "XLS";
        }
        if (isWordDocument(data)) {
            return "DOC";
        }
        if (operationService.isTextDocument(data)) {
            return "TXT";
        }
        String mediaType = data.getMediaType() == null ? "" : data.getMediaType().toLowerCase(Locale.ROOT);
        if (mediaType.contains("audio")) {
            return "AUD";
        }
        if (mediaType.contains("video")) {
            return "VID";
        }
        return "FILE";
    }

    private Color fileTypeColor(EncryptedData data) {
        if (isPreviewableImage(data)) {
            return new Color(37, 99, 235);
        }
        if (isPdfDocument(data)) {
            return new Color(220, 38, 38);
        }
        if (isSpreadsheetDocument(data)) {
            return new Color(22, 163, 74);
        }
        if (isWordDocument(data)) {
            return new Color(37, 99, 235);
        }
        if (operationService.isTextDocument(data)) {
            return new Color(75, 85, 99);
        }
        return new Color(107, 114, 128);
    }

    /**
     * Shows the full-size image preview dialog.
     */
    private void showImagePreviewDialog(String fileName, BufferedImage image) {
        int maxWidth = Math.max(480, owner.getWidth() - 120);
        int maxHeight = Math.max(360, owner.getHeight() - 180);
        Image scaled = scaleImageToFit(image, maxWidth, maxHeight);

        JLabel imageLabel = new JLabel(new ImageIcon(scaled));
        imageLabel.setHorizontalAlignment(SwingConstants.CENTER);
        JScrollPane scrollPane = new JScrollPane(imageLabel);
        scrollPane.setPreferredSize(new Dimension(
                Math.min(maxWidth, scaled.getWidth(null) + 24),
                Math.min(maxHeight, scaled.getHeight(null) + 24)
        ));

        JDialog dialog = new JDialog(owner, "Image Preview - " + fileName, true);
        dialog.setLayout(new BorderLayout());
        dialog.add(scrollPane, BorderLayout.CENTER);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    /**
     * Scales an image proportionally within the specified maximum width and height.
     */
    private Image scaleImageToFit(BufferedImage image, int maxWidth, int maxHeight) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= maxWidth && height <= maxHeight) {
            return image;
        }
        double scale = Math.min(maxWidth / (double) width, maxHeight / (double) height);
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));
        return image.getScaledInstance(targetWidth, targetHeight, Image.SCALE_SMOOTH);
    }

    /**
     * Creates thumbnail icons in a background thread to avoid smooth-scaling work during Swing result rendering.
     */
    private ImageIcon createThumbnailIcon(BufferedImage image, int maxWidth, int maxHeight) {
        int width = image.getWidth();
        int height = image.getHeight();
        double scale = Math.min(1.0d, Math.min(maxWidth / (double) width, maxHeight / (double) height));
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));
        BufferedImage thumbnail = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = thumbnail.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(image, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return new ImageIcon(thumbnail);
    }

    /**
     * Image preview data prepared in the background.
     */
    private record ImagePreview(BufferedImage fullImage, ImageIcon thumbnailIcon) {
    }

    private enum PreviewKind {
        IMAGE,
        PDF,
        SPREADSHEET,
        TEXT,
        MESSAGE
    }

    private record DocumentPreview(
            String title,
            PreviewKind kind,
            BufferedImage image,
            List<BufferedImage> pdfPages,
            List<SheetPreview> sheets,
            String text
    ) {
        private static DocumentPreview image(String title, BufferedImage image) {
            return new DocumentPreview(title, PreviewKind.IMAGE, image, List.of(), List.of(), null);
        }

        private static DocumentPreview pdf(String title, List<BufferedImage> pages) {
            return new DocumentPreview(title, PreviewKind.PDF, null, pages, List.of(), null);
        }

        private static DocumentPreview spreadsheet(String title, List<SheetPreview> sheets) {
            return new DocumentPreview(title, PreviewKind.SPREADSHEET, null, List.of(), sheets, null);
        }

        private static DocumentPreview text(String title, String text) {
            return new DocumentPreview(title, PreviewKind.TEXT, null, List.of(), List.of(), text);
        }

        private static DocumentPreview message(String title, String text) {
            return new DocumentPreview(title, PreviewKind.MESSAGE, null, List.of(), List.of(), text);
        }
    }

    private record SheetPreview(String name, List<String> columnNames, List<List<String>> rows) {
    }

    /**
     * Simple file-type icon used as the preview entry for non-image search results.
     */
    private static final class FileTypeIcon implements Icon {
        private static final int WIDTH = 92;
        private static final int HEIGHT = 112;

        private final String label;
        private final Color accentColor;

        private FileTypeIcon(String label, Color accentColor) {
            this.label = label;
            this.accentColor = accentColor;
        }

        @Override
        public int getIconWidth() {
            return WIDTH;
        }

        @Override
        public int getIconHeight() {
            return HEIGHT;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Color.WHITE);
                g.fillRoundRect(x + 6, y + 2, WIDTH - 12, HEIGHT - 4, 8, 8);
                g.setColor(new Color(209, 213, 219));
                g.drawRoundRect(x + 6, y + 2, WIDTH - 12, HEIGHT - 4, 8, 8);

                Polygon fold = new Polygon();
                fold.addPoint(x + WIDTH - 30, y + 3);
                fold.addPoint(x + WIDTH - 7, y + 26);
                fold.addPoint(x + WIDTH - 30, y + 26);
                g.setColor(new Color(243, 244, 246));
                g.fillPolygon(fold);
                g.setColor(new Color(209, 213, 219));
                g.drawPolygon(fold);

                g.setColor(accentColor);
                g.fillRoundRect(x + 16, y + HEIGHT - 42, WIDTH - 32, 28, 8, 8);

                Font baseFont = component == null ? new Font(Font.SANS_SERIF, Font.BOLD, 16) : component.getFont();
                float size = label.length() > 3 ? 14f : 17f;
                g.setFont(baseFont.deriveFont(Font.BOLD, size));
                FontMetrics metrics = g.getFontMetrics();
                int textX = x + (WIDTH - metrics.stringWidth(label)) / 2;
                int textY = y + HEIGHT - 23 + metrics.getAscent() / 2 - 4;
                g.setColor(Color.WHITE);
                g.drawString(label, textX, textY);
            } finally {
                g.dispose();
            }
        }
    }

    /**
     * Image preview component: fits the full image to the window by default and also allows zooming in for details.
     */
    private static final class ImagePreviewComponent extends JPanel {
        private static final double MIN_ZOOM = 0.25d;
        private static final double MAX_ZOOM = 3.0d;
        private static final double ZOOM_STEP = 0.15d;

        private final BufferedImage image;
        private final JLabel imageLabel = new JLabel();
        private final JScrollPane scrollPane;
        private final JLabel zoomLabel = new JLabel("Fit window");
        private boolean fitWindow = true;
        private double zoom = 1.0d;

        private ImagePreviewComponent(BufferedImage image) {
            super(new BorderLayout());
            this.image = image;
            add(buildToolbar(), BorderLayout.NORTH);

            imageLabel.setHorizontalAlignment(SwingConstants.CENTER);
            imageLabel.setVerticalAlignment(SwingConstants.CENTER);
            JPanel imagePanel = new JPanel(new GridBagLayout());
            imagePanel.add(imageLabel);

            scrollPane = new JScrollPane(imagePanel);
            scrollPane.getVerticalScrollBar().setUnitIncrement(18);
            scrollPane.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent event) {
                    if (fitWindow) {
                        updateImage();
                    }
                }
            });
            add(scrollPane, BorderLayout.CENTER);
            SwingUtilities.invokeLater(this::updateImage);
        }

        private JComponent buildToolbar() {
            JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
            JButton fitButton = new JButton("Fit Window");
            JButton actualButton = new JButton("100%");
            JButton zoomOutButton = new JButton("-");
            JButton zoomInButton = new JButton("+");

            fitButton.addActionListener(event -> {
                fitWindow = true;
                updateImage();
            });
            actualButton.addActionListener(event -> {
                fitWindow = false;
                zoom = 1.0d;
                updateImage();
            });
            zoomOutButton.addActionListener(event -> {
                fitWindow = false;
                zoom = Math.max(MIN_ZOOM, zoom - ZOOM_STEP);
                updateImage();
            });
            zoomInButton.addActionListener(event -> {
                fitWindow = false;
                zoom = Math.min(MAX_ZOOM, zoom + ZOOM_STEP);
                updateImage();
            });

            toolbar.add(fitButton);
            toolbar.add(actualButton);
            toolbar.add(zoomOutButton);
            toolbar.add(zoomInButton);
            toolbar.add(zoomLabel);
            return toolbar;
        }

        private void updateImage() {
            int viewportWidth = scrollPane.getViewport().getExtentSize().width;
            int viewportHeight = scrollPane.getViewport().getExtentSize().height;
            if (viewportWidth <= 0) {
                viewportWidth = Math.max(640, scrollPane.getWidth());
            }
            if (viewportHeight <= 0) {
                viewportHeight = Math.max(420, scrollPane.getHeight());
            }

            int availableWidth = Math.max(160, viewportWidth - 36);
            int availableHeight = Math.max(160, viewportHeight - 36);
            double effectiveZoom = fitWindow
                    ? Math.min(1.0d, Math.min(availableWidth / (double) image.getWidth(), availableHeight / (double) image.getHeight()))
                    : zoom;
            imageLabel.setIcon(createScaledIcon(image, effectiveZoom));
            zoomLabel.setText((fitWindow ? "Fit window " : "Zoom ") + Math.round(effectiveZoom * 100) + "%");
            revalidate();
            repaint();
        }

        private ImageIcon createScaledIcon(BufferedImage image, double scale) {
            int targetWidth = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int targetHeight = Math.max(1, (int) Math.round(image.getHeight() * scale));
            if (targetWidth == image.getWidth() && targetHeight == image.getHeight()) {
                return new ImageIcon(image);
            }

            BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = scaled.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                graphics.drawImage(image, 0, 0, targetWidth, targetHeight, null);
            } finally {
                graphics.dispose();
            }
            return new ImageIcon(scaled);
        }
    }

    /**
     * PDF preview component: fits pages to the window by default and also allows users to zoom to original size for details.
     */
    private static final class PdfPreviewComponent extends JPanel {
        private static final double MIN_ZOOM = 0.25d;
        private static final double MAX_ZOOM = 2.5d;
        private static final double ZOOM_STEP = 0.15d;

        private final List<BufferedImage> pages;
        private final List<JLabel> pageLabels = new ArrayList<>();
        private final JPanel pagePanel = new JPanel();
        private final JScrollPane scrollPane;
        private final JLabel zoomLabel = new JLabel("Fit page");
        private boolean fitPage = true;
        private boolean fitWidth = false;
        private double zoom = 1.0d;

        private PdfPreviewComponent(List<BufferedImage> pages) {
            super(new BorderLayout());
            this.pages = pages == null ? List.of() : pages;
            add(buildToolbar(), BorderLayout.NORTH);

            pagePanel.setLayout(new BoxLayout(pagePanel, BoxLayout.Y_AXIS));
            pagePanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
            buildPageLabels();

            scrollPane = new JScrollPane(pagePanel);
            scrollPane.getVerticalScrollBar().setUnitIncrement(18);
            scrollPane.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent event) {
                    if (fitPage || fitWidth) {
                        updatePageImages();
                    }
                }
            });
            add(scrollPane, BorderLayout.CENTER);
            SwingUtilities.invokeLater(this::updatePageImages);
        }

        private JComponent buildToolbar() {
            JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
            JButton fitPageButton = new JButton("Fit Page");
            JButton fitWidthButton = new JButton("Fit Width");
            JButton actualButton = new JButton("100%");
            JButton zoomOutButton = new JButton("-");
            JButton zoomInButton = new JButton("+");

            fitPageButton.addActionListener(event -> {
                fitPage = true;
                fitWidth = false;
                updatePageImages();
            });
            fitWidthButton.addActionListener(event -> {
                fitPage = false;
                fitWidth = true;
                updatePageImages();
            });
            actualButton.addActionListener(event -> {
                fitPage = false;
                fitWidth = false;
                zoom = 1.0d;
                updatePageImages();
            });
            zoomOutButton.addActionListener(event -> {
                fitPage = false;
                fitWidth = false;
                zoom = Math.max(MIN_ZOOM, zoom - ZOOM_STEP);
                updatePageImages();
            });
            zoomInButton.addActionListener(event -> {
                fitPage = false;
                fitWidth = false;
                zoom = Math.min(MAX_ZOOM, zoom + ZOOM_STEP);
                updatePageImages();
            });

            toolbar.add(fitPageButton);
            toolbar.add(fitWidthButton);
            toolbar.add(actualButton);
            toolbar.add(zoomOutButton);
            toolbar.add(zoomInButton);
            toolbar.add(zoomLabel);
            return toolbar;
        }

        private void buildPageLabels() {
            if (pages.isEmpty()) {
                pagePanel.add(new JLabel("No PDF pages could be rendered."));
                return;
            }

            for (int i = 0; i < pages.size(); i++) {
                JLabel pageNumber = new JLabel("Page " + (i + 1));
                pageNumber.setAlignmentX(Component.CENTER_ALIGNMENT);
                JLabel pageLabel = new JLabel();
                pageLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
                pageLabel.setBorder(BorderFactory.createLineBorder(new Color(209, 213, 219)));
                pageLabels.add(pageLabel);

                pagePanel.add(pageNumber);
                pagePanel.add(Box.createVerticalStrut(6));
                pagePanel.add(pageLabel);
                pagePanel.add(Box.createVerticalStrut(14));
            }
        }

        private void updatePageImages() {
            if (pages.isEmpty()) {
                return;
            }

            int viewportWidth = scrollPane.getViewport().getExtentSize().width;
            if (viewportWidth <= 0) {
                viewportWidth = Math.max(640, scrollPane.getWidth());
            }
            int viewportHeight = scrollPane.getViewport().getExtentSize().height;
            if (viewportHeight <= 0) {
                viewportHeight = Math.max(420, scrollPane.getHeight());
            }
            int availableWidth = Math.max(160, viewportWidth - 54);
            int availableHeight = Math.max(160, viewportHeight - 72);

            for (int i = 0; i < pages.size(); i++) {
                BufferedImage page = pages.get(i);
                double effectiveZoom = calculateEffectiveZoom(page, availableWidth, availableHeight);
                pageLabels.get(i).setIcon(createScaledIcon(page, effectiveZoom));
            }

            double displayedZoom = calculateEffectiveZoom(pages.get(0), availableWidth, availableHeight);
            zoomLabel.setText((fitPage ? "Fit page " : fitWidth ? "Fit width " : "Zoom ") + Math.round(displayedZoom * 100) + "%");
            pagePanel.revalidate();
            pagePanel.repaint();
        }

        private double calculateEffectiveZoom(BufferedImage page, int availableWidth, int availableHeight) {
            if (fitPage) {
                return Math.min(1.0d, Math.min(availableWidth / (double) page.getWidth(), availableHeight / (double) page.getHeight()));
            }
            if (fitWidth) {
                return Math.min(1.0d, availableWidth / (double) page.getWidth());
            }
            return zoom;
        }

        private ImageIcon createScaledIcon(BufferedImage image, double scale) {
            int targetWidth = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int targetHeight = Math.max(1, (int) Math.round(image.getHeight() * scale));
            if (targetWidth == image.getWidth() && targetHeight == image.getHeight()) {
                return new ImageIcon(image);
            }

            BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = scaled.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                graphics.drawImage(image, 0, 0, targetWidth, targetHeight, null);
            } finally {
                graphics.dispose();
            }
            return new ImageIcon(scaled);
        }
    }

    private record SearchTaskResult(List<EncryptedData> results, Map<String, ImagePreview> imagePreviews, String errorMessage) {
        /** Creates a successful result. */
        private static SearchTaskResult success(List<EncryptedData> results, Map<String, ImagePreview> imagePreviews) {
            return new SearchTaskResult(results, imagePreviews, null);
        }

        /** Creates a failed result. */
        private static SearchTaskResult failure(String errorMessage) {
            return new SearchTaskResult(Collections.emptyList(), Collections.emptyMap(), errorMessage);
        }
    }
}
