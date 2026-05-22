package com.bdic.admin;

import com.bdic.crypto.ClientKeyManager;
import com.bdic.model.DocumentIdGenerator;
import com.bdic.model.EncryptedData;
import com.bdic.model.ServerResponse;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Upload page controller for upload-related UI and asynchronous upload workflow.
 */
public class UploadPanelController {

    /** Main window used as the owner for dialogs and native file choosers. */
    private final JFrame owner;
    /** Client used to communicate with the server. */
    private final DocumentServiceClient serviceClient;
    /** Service for local document encryption and keyword index building. */
    private final DocumentOperationService operationService;
    /** Local key bundle for the current user. */
    private final ClientKeyManager.KeyBundle keyBundle;
    /** Callback that refreshes the document list after upload succeeds. */
    private final Runnable refreshDocumentsAction;

    /** Currently selected file paths prepared for batch upload. */
    private final List<Path> selectedFilePaths = new ArrayList<>();
    /** Global busy-state manager, injected after the main window is assembled. */
    private UiBusyStateManager busyStateManager;

    /** Auto-generated document ID input field used for plaintext uploads. */
    private JTextField docIdField;
    /** User-entered document description, stored in encrypted keyword metadata. */
    private JTextField descriptionField;
    /** Plaintext upload content input area. */
    private JTextArea plainTextContentArea;
    /** Displays the current file selection status. */
    private JLabel selectedFileLabel;
    /** Menu button for importing files or folders. */
    private JButton importButton;
    /** Button for clearing selected files. */
    private JButton clearFileButton;
    /** Button that triggers upload. */
    private JButton uploadButton;
    /** Upload page status text. */
    private JLabel uploadStatusLabel;
    /** Upload page progress bar. */
    private JProgressBar uploadProgressBar;

    /**
     * Creates the upload page controller.
     */
    public UploadPanelController(
            JFrame owner,
            DocumentServiceClient serviceClient,
            DocumentOperationService operationService,
            ClientKeyManager.KeyBundle keyBundle,
            Runnable refreshDocumentsAction
    ) {
        this.owner = owner;
        this.serviceClient = serviceClient;
        this.operationService = operationService;
        this.keyBundle = keyBundle;
        this.refreshDocumentsAction = refreshDocumentsAction;
    }

    /**
     * Creates the full upload page UI.
     */
    public JPanel createPanel() {
        JPanel uploadPanel = new JPanel(new BorderLayout(10, 10));
        uploadPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // The top form contains the document ID, file selection status, and import actions.
        JPanel uploadFormPanel = new JPanel(new GridLayout(3, 2, 5, 5));
        uploadFormPanel.add(new JLabel("Document ID:"));
        docIdField = new JTextField();
        docIdField.setEditable(false);
        docIdField.setText(DocumentIdGenerator.generate());
        uploadFormPanel.add(docIdField);

        uploadFormPanel.add(new JLabel("Selected Files:"));
        selectedFileLabel = new JLabel("No file or folder selected");
        uploadFormPanel.add(selectedFileLabel);

        uploadFormPanel.add(new JLabel("Import Actions:"));
        JPanel fileActionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        importButton = new JButton("Import");
        importButton.addActionListener(e -> showImportMenu(importButton));
        UiComponentFactory.stylePrimaryButton(importButton);
        clearFileButton = new JButton("Clear Selection");
        clearFileButton.addActionListener(e -> clearSelectedFiles());
        UiComponentFactory.styleSecondaryButton(clearFileButton);
        fileActionPanel.add(importButton);
        fileActionPanel.add(Box.createHorizontalStrut(8));
        fileActionPanel.add(clearFileButton);
        uploadFormPanel.add(fileActionPanel);

        uploadPanel.add(uploadFormPanel, BorderLayout.NORTH);

        // The middle area supports both entering a description and pasting plaintext body content.
        descriptionField = new JTextField();

        plainTextContentArea = new JTextArea(14, 20);
        plainTextContentArea.setLineWrap(true);
        plainTextContentArea.setWrapStyleWord(true);

        JScrollPane contentScrollPane = new JScrollPane(plainTextContentArea);

        JPanel centerPanel = new JPanel(new BorderLayout(8, 8));
        JPanel descriptionPanel = new JPanel(new BorderLayout(6, 0));
        descriptionPanel.add(new JLabel("Description:"), BorderLayout.WEST);
        descriptionPanel.add(descriptionField, BorderLayout.CENTER);
        centerPanel.add(descriptionPanel, BorderLayout.NORTH);
        centerPanel.add(UiComponentFactory.createSectionPanel(
                "Content (plain text)",
                "Paste plain text here when uploading as a text document (without selecting files).",
                contentScrollPane
        ), BorderLayout.CENTER);
        uploadPanel.add(centerPanel, BorderLayout.CENTER);

        // The bottom area contains progress status and the upload button.
        uploadButton = new JButton("Upload Document");
        uploadButton.addActionListener(e -> handleUpload());
        UiComponentFactory.stylePrimaryButton(uploadButton);
        uploadStatusLabel = new JLabel(" ");
        uploadStatusLabel.setForeground(new Color(75, 85, 99));
        uploadProgressBar = new JProgressBar();
        uploadProgressBar.setIndeterminate(true);
        uploadProgressBar.setVisible(false);
        uploadProgressBar.setPreferredSize(new Dimension(160, 18));

        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        statusPanel.add(uploadProgressBar);
        statusPanel.add(Box.createHorizontalStrut(8));
        statusPanel.add(uploadStatusLabel);

        JPanel footerPanel = new JPanel(new BorderLayout());
        footerPanel.add(statusPanel, BorderLayout.WEST);
        footerPanel.add(uploadButton, BorderLayout.EAST);
        uploadPanel.add(footerPanel, BorderLayout.SOUTH);
        registerEnterToUpload(uploadPanel);
        return uploadPanel;
    }

    /** Injects the busy-state manager. */
    public void setBusyStateManager(UiBusyStateManager busyStateManager) {
        this.busyStateManager = busyStateManager;
    }

    /** Returns the upload page status label for unified busy-state updates. */
    public JLabel getStatusLabel() {
        return uploadStatusLabel;
    }

    /** Returns the upload page progress bar for unified show/hide handling. */
    public JProgressBar getProgressBar() {
        return uploadProgressBar;
    }

    /** Returns upload page controls that must be disabled while background tasks run. */
    public List<JComponent> getBusySensitiveComponents() {
        List<JComponent> components = new ArrayList<>();
        components.add(uploadButton);
        components.add(importButton);
        components.add(clearFileButton);
        components.add(descriptionField);
        components.add(plainTextContentArea);
        return components;
    }

    /**
     * Handles the upload button: validates input, locks the UI, and starts a background upload task.
     */
    private void handleUpload() {
        String description = descriptionField.getText();
        String plainTextContent = plainTextContentArea.getText();

        if ((plainTextContent == null || plainTextContent.isBlank()) && selectedFilePaths.isEmpty()) {
            JOptionPane.showMessageDialog(owner, "Please enter plain text content or choose files/folder.", "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (busyStateManager == null || busyStateManager.isBusy()) {
            return;
        }

        final List<Path> filesToUpload = new ArrayList<>(selectedFilePaths);
        final String textDocId = docIdField.getText().trim();
        busyStateManager.setUploadBusy(true, initialUploadStatus(filesToUpload));
        // SwingWorker performs encryption and network upload in the background, while done/process update the UI on the EDT.
        new SwingWorker<UploadTaskResult, String>() {
            @Override
            protected UploadTaskResult doInBackground() {
                try {
                    return performUpload(textDocId, description, plainTextContent, filesToUpload, status -> publish(status));
                } catch (Exception ex) {
                    ex.printStackTrace();
                    return UploadTaskResult.error("Upload failed: " + DocumentOperationService.describeException(ex));
                }
            }

            @Override
            protected void process(List<String> chunks) {
                // publish may accumulate multiple statuses; displaying only the latest one is enough.
                if (!chunks.isEmpty() && busyStateManager != null) {
                    busyStateManager.updateUploadStatus(chunks.get(chunks.size() - 1));
                }
            }

            @Override
            protected void done() {
                UploadTaskResult result;
                try {
                    result = get();
                } catch (Exception ex) {
                    ex.printStackTrace();
                    result = UploadTaskResult.error("Upload failed: " + DocumentOperationService.describeException(ex));
                }

                if (result.clearInputs()) {
                    resetUploadForm();
                }
                // Clear the busy state before refreshing the list or showing dialogs so the UI does not feel stuck.
                if (busyStateManager != null) {
                    busyStateManager.setUploadBusy(false, " ");
                }
                if (result.refreshDocuments()) {
                    refreshDocumentsAction.run();
                }
                JOptionPane.showMessageDialog(
                        owner,
                        result.message(),
                        result.title(),
                        result.messageType()
                );
            }
        }.execute();
    }

    /**
     * Runs plaintext upload or batch file upload based on the current selection.
     */
    private UploadTaskResult performUpload(
            String textDocId,
            String description,
            String plainTextContent,
            List<Path> filesToUpload,
            Consumer<String> statusUpdater
    ) throws Exception {
        if (filesToUpload.isEmpty()) {
            // When no file is selected, upload the text box content as one text/plain document.
            statusUpdater.accept("Encrypting and uploading text content...");
            String docId = textDocId;
            DocumentOperationService.UploadContent uploadContent = operationService.resolveTextUploadContent(docId, plainTextContent);
            ServerResponse response = uploadSingleDocument(docId, uploadContent, description);
            return new UploadTaskResult(
                    "Upload Result",
                    response.getMessage(),
                    response.isSuccess() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE,
                    response.isSuccess(),
                    response.isSuccess()
            );
        }

        int successCount = 0;
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < filesToUpload.size(); i++) {
            Path filePath = filesToUpload.get(i);
            String fileName = filePath.getFileName() == null ? filePath.toString() : filePath.getFileName().toString();
            statusUpdater.accept("Uploading (" + (i + 1) + "/" + filesToUpload.size() + "): " + fileName);
            String docId = DocumentIdGenerator.generate();
            try {
                // Each file is built, encrypted, and uploaded independently; one failure does not stop the whole batch.
                DocumentOperationService.UploadContent uploadContent = operationService.resolveFileUploadContent(filePath);
                ServerResponse response = uploadSingleDocument(docId, uploadContent, description);
                if (response.isSuccess()) {
                    successCount++;
                } else {
                    failures.add(uploadContent.fileName() + ": " + response.getMessage());
                }
            } catch (Exception fileException) {
                failures.add(fileName + ": " + DocumentOperationService.describeException(fileException));
            }
        }

        // After batch upload, summarize the success count and failure reasons.
        StringBuilder summary = new StringBuilder();
        summary.append("Batch upload finished.\n")
                .append("Success: ").append(successCount).append('\n')
                .append("Failed: ").append(failures.size());
        if (!failures.isEmpty()) {
            summary.append("\n\nFailures:\n").append(String.join("\n", failures));
        }

        return new UploadTaskResult(
                "Batch Upload Result",
                summary.toString(),
                failures.isEmpty() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE,
                true,
                successCount > 0
        );
    }

    /**
     * Builds one encrypted document and sends it to the server.
     */
    private ServerResponse uploadSingleDocument(String docId, DocumentOperationService.UploadContent uploadContent, String descriptionInput) throws Exception {
        EncryptedData data = operationService.buildEncryptedData(docId, uploadContent, descriptionInput, keyBundle.desKey(), keyBundle.peksPublicKey());
        return serviceClient.upload(data);
    }

    /**
     * Opens the system file chooser and supports selecting multiple files at once.
     */
    private void chooseFiles() {
        if (busyStateManager != null && busyStateManager.isBusy()) {
            return;
        }
        FileDialog fileDialog = new FileDialog(owner, "Select Files to Upload", FileDialog.LOAD);
        fileDialog.setMultipleMode(true);
        fileDialog.setVisible(true);

        // FileDialog returns a File array; convert it to Path for later NIO reads.
        File[] selectedFiles = fileDialog.getFiles();
        if (selectedFiles == null || selectedFiles.length == 0) {
            return;
        }

        List<Path> chosenPaths = new ArrayList<>();
        for (File selectedFile : selectedFiles) {
            if (selectedFile != null) {
                chosenPaths.add(selectedFile.toPath());
            }
        }
        replaceSelectedFiles(chosenPaths);
    }

    /**
     * Opens the folder chooser and collects all regular files under the folder.
     */
    private void chooseFolder() {
        if (busyStateManager != null && busyStateManager.isBusy()) {
            return;
        }
        String selectedFolder = NativeDialogHelper.chooseFolder("Select Folder to Upload");
        if (selectedFolder == null || selectedFolder.isBlank()) {
            return;
        }

        try {
            List<Path> files = operationService.collectFiles(Path.of(selectedFolder));
            replaceSelectedFiles(files);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(owner, "Failed to load folder: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Shows the file/folder import menu below the import button.
     */
    private void showImportMenu(Component invoker) {
        if (busyStateManager != null && busyStateManager.isBusy()) {
            return;
        }
        JPopupMenu menu = new JPopupMenu();

        JMenuItem filesItem = new JMenuItem("Import Files");
        filesItem.addActionListener(e -> chooseFiles());
        menu.add(filesItem);

        JMenuItem folderItem = new JMenuItem("Import Folder");
        folderItem.addActionListener(e -> chooseFolder());
        menu.add(folderItem);

        applyPopupMenuScale(menu, invoker, filesItem, folderItem);
        menu.show(invoker, 0, invoker.getHeight());
    }

    /**
     * Keeps the popup menu font aligned with the current button scaling ratio.
     */
    private void applyPopupMenuScale(JPopupMenu menu, Component invoker, JMenuItem... items) {
        Font targetFont = invoker.getFont();
        if (targetFont == null) {
            return;
        }

        int horizontalPadding = Math.max(10, Math.round(targetFont.getSize2D() * 0.8f));
        int verticalPadding = Math.max(4, Math.round(targetFont.getSize2D() * 0.35f));
        int minHeight = Math.max(invoker.getHeight(), 24);

        menu.setFont(targetFont);
        for (JMenuItem item : items) {
            item.setFont(targetFont);
            item.setBorder(BorderFactory.createEmptyBorder(verticalPadding, horizontalPadding, verticalPadding, horizontalPadding));
            Dimension preferredSize = item.getPreferredSize();
            item.setPreferredSize(new Dimension(preferredSize.width, Math.max(preferredSize.height, minHeight)));
        }
    }

    /** Clears the current file selection. */
    private void clearSelectedFiles() {
        selectedFilePaths.clear();
        updateSelectedFilesLabel();
    }

    /** Replaces the current selection with a new set of file paths. */
    private void replaceSelectedFiles(List<Path> paths) {
        selectedFilePaths.clear();
        selectedFilePaths.addAll(paths);
        updateSelectedFilesLabel();
    }

    /** Updates the UI prompt according to the selected file count. */
    private void updateSelectedFilesLabel() {
        if (selectedFileLabel == null) {
            return;
        }
        if (selectedFilePaths.isEmpty()) {
            selectedFileLabel.setText("No file or folder selected");
            return;
        }
        if (selectedFilePaths.size() == 1) {
            selectedFileLabel.setText(selectedFilePaths.get(0).getFileName().toString());
            return;
        }
        selectedFileLabel.setText(selectedFilePaths.size() + " files selected");
    }

    /** Generates the status text shown at upload start. */
    private String initialUploadStatus(List<Path> filesToUpload) {
        return filesToUpload.isEmpty() ? "Uploading text content..." : "Preparing " + filesToUpload.size() + " file(s)...";
    }

    /** Resets the form after upload succeeds and generates a new document ID. */
    private void resetUploadForm() {
        docIdField.setText(DocumentIdGenerator.generate());
        descriptionField.setText("");
        plainTextContentArea.setText("");
        clearSelectedFiles();
    }

    /**
     * Binds Enter as an upload shortcut on the upload panel while preserving newline behavior inside multiline text areas.
     */
    private void registerEnterToUpload(JComponent root) {
        InputMap inputMap = root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap actionMap = root.getActionMap();
        inputMap.put(KeyStroke.getKeyStroke("ENTER"), "triggerUpload");
        actionMap.put("triggerUpload", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                if (focusOwner instanceof JTextArea) {
                    return;
                }
                handleUpload();
            }
        });
    }

    /**
     * Result object for upload background tasks.
     *
     * @param title dialog title.
     * @param message dialog content.
     * @param messageType JOptionPane message type.
     * @param clearInputs whether to clear the upload form.
     * @param refreshDocuments whether to refresh the document list.
     */
    private record UploadTaskResult(
            String title,
            String message,
            int messageType,
            boolean clearInputs,
            boolean refreshDocuments
    ) {
        /** Creates an upload failure result. */
        private static UploadTaskResult error(String message) {
            return new UploadTaskResult("Upload Result", message, JOptionPane.ERROR_MESSAGE, false, false);
        }
    }
}
