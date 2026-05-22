package com.bdic.admin;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Centrally manages client busy state: control disabling, progress bar display, status text, and wait cursor.
 */
public class UiBusyStateManager {

    /** Main window used to switch the wait cursor. */
    private final JFrame owner;
    /** Controls that must be disabled during business operations. */
    private final List<JComponent> disableWhenBusy = new ArrayList<>();
    /** Upload page status text. */
    private final JLabel uploadStatusLabel;
    /** Upload page progress bar. */
    private final JProgressBar uploadProgressBar;
    /** Search page status text. */
    private final JLabel searchStatusLabel;
    /** Search page progress bar. */
    private final JProgressBar searchProgressBar;
    /** Document management page status text. */
    private final JLabel documentsStatusLabel;
    /** Document management page progress bar. */
    private final JProgressBar documentsProgressBar;
    /** Whether a background task is currently running. */
    private boolean busy;

    /**
     * Binds the window and status controls for the three business pages.
     */
    public UiBusyStateManager(
            JFrame owner,
            JLabel uploadStatusLabel,
            JProgressBar uploadProgressBar,
            JLabel searchStatusLabel,
            JProgressBar searchProgressBar,
            JLabel documentsStatusLabel,
            JProgressBar documentsProgressBar
    ) {
        this.owner = owner;
        this.uploadStatusLabel = uploadStatusLabel;
        this.uploadProgressBar = uploadProgressBar;
        this.searchStatusLabel = searchStatusLabel;
        this.searchProgressBar = searchProgressBar;
        this.documentsStatusLabel = documentsStatusLabel;
        this.documentsProgressBar = documentsProgressBar;
    }

    /**
     * Registers controls that should be disabled uniformly during background tasks.
     */
    public void registerBusySensitiveComponents(List<JComponent> components) {
        disableWhenBusy.clear();
        disableWhenBusy.addAll(components);
    }

    /** Returns whether the current app is busy. */
    public boolean isBusy() {
        return busy;
    }

    /** Toggles upload task busy state and shows only the upload page progress bar. */
    public void setUploadBusy(boolean busy, String statusText) {
        setApplicationBusy(busy);
        toggleProgress(uploadProgressBar, busy);
        toggleProgress(searchProgressBar, false);
        toggleProgress(documentsProgressBar, false);
        setLabelText(uploadStatusLabel, statusText);
        if (!busy) {
            setLabelText(searchStatusLabel, " ");
            setLabelText(documentsStatusLabel, " ");
        }
    }

    /** Toggles search task busy state and shows only the search page progress bar. */
    public void setSearchBusy(boolean busy, String statusText) {
        setApplicationBusy(busy);
        toggleProgress(uploadProgressBar, false);
        toggleProgress(searchProgressBar, busy);
        toggleProgress(documentsProgressBar, false);
        setLabelText(searchStatusLabel, statusText);
        if (!busy) {
            setLabelText(uploadStatusLabel, " ");
            setLabelText(documentsStatusLabel, " ");
        }
    }

    /** Toggles document management task busy state and shows only the Documents page progress bar. */
    public void setDocumentsBusy(boolean busy, String statusText) {
        setApplicationBusy(busy);
        toggleProgress(uploadProgressBar, false);
        toggleProgress(searchProgressBar, false);
        toggleProgress(documentsProgressBar, busy);
        setLabelText(documentsStatusLabel, statusText);
        if (!busy) {
            setLabelText(uploadStatusLabel, " ");
            setLabelText(searchStatusLabel, " ");
        }
    }

    /** Updates the upload page status text. */
    public void updateUploadStatus(String statusText) {
        setLabelText(uploadStatusLabel, statusText);
    }

    /** Updates the search page status text. */
    public void updateSearchStatus(String statusText) {
        setLabelText(searchStatusLabel, statusText);
    }

    /** Updates the document management page status text. */
    public void updateDocumentsStatus(String statusText) {
        setLabelText(documentsStatusLabel, statusText);
    }

    /**
     * App-level busy state: disables controls and switches the mouse cursor.
     */
    private void setApplicationBusy(boolean busy) {
        this.busy = busy;
        for (JComponent component : disableWhenBusy) {
            if (component != null) {
                component.setEnabled(!busy);
            }
        }
        owner.setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
    }

    /**
     * Controls progress bar visibility and indeterminate animation.
     */
    private void toggleProgress(JProgressBar progressBar, boolean visible) {
        if (progressBar == null) {
            return;
        }
        progressBar.setVisible(visible);
        progressBar.setIndeterminate(visible);
    }

    /**
     * Safely sets status text; empty text uses one space to prevent layout height jumps.
     */
    private void setLabelText(JLabel label, String text) {
        if (label == null) {
            return;
        }
        label.setText(text == null || text.isBlank() ? " " : text);
    }
}
