package com.bdic.admin;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Windows native dialog utility for system-level interactions such as folder selection.
 */
public final class NativeDialogHelper {

    /** Utility class; instantiation is not needed. */
    private NativeDialogHelper() {
    }

    /**
     * Opens the Windows native folder chooser and returns the folder path selected by the user.
     *
     * <p>Swing's {@link javax.swing.JFileChooser} offers a weaker folder experience in some Windows environments,
     * so this calls FolderBrowserDialog through PowerShell.</p>
     */
    public static String chooseFolder(String description) {
        // Base64-encode the path before PowerShell outputs it to avoid corruption from non-ASCII paths or special characters.
        String script = "$dialog = New-Object System.Windows.Forms.FolderBrowserDialog; "
                + "$dialog.Description = '" + escapePowerShellSingleQuoted(description) + "'; "
                + "$dialog.ShowNewFolderButton = $false; "
                + "if ($dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) { "
                + "Write-Output ([Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($dialog.SelectedPath))) }";
        ProcessBuilder processBuilder = new ProcessBuilder(
                "powershell",
                "-NoProfile",
                "-STA",
                "-Command",
                "Add-Type -AssemblyName System.Windows.Forms; " + script
        );
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            String output;
            try (var inputStream = process.getInputStream()) {
                output = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            int exitCode = process.waitFor();
            if (exitCode != 0 || output.isBlank()) {
                return null;
            }
            // Use the last non-empty output line to avoid extra prompt text that PowerShell may emit.
            String encodedPath = output.lines()
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .reduce((first, second) -> second)
                    .orElse(null);
            if (encodedPath == null || encodedPath.isBlank()) {
                return null;
            }
            return new String(Base64.getDecoder().decode(encodedPath), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Escapes single quotes so description text can be safely placed inside a PowerShell single-quoted string.
     */
    private static String escapePowerShellSingleQuoted(String value) {
        return value == null ? "" : value.replace("'", "''");
    }
}
