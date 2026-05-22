package com.bdic.admin;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

/**
 * Dynamically scales fonts, button padding, and common layout gaps according to window size.
 */
public final class UiScaleManager {

    /** Client property key that records a component's original font size. */
    private static final String BASE_FONT_SIZE_KEY = "ui.scale.baseFontSize";
    /** Client property key that records a button's original padding. */
    private static final String BASE_BUTTON_MARGIN_KEY = "ui.scale.baseButtonMargin";
    /** Client property key that records a component's original border. */
    private static final String BASE_BORDER_KEY = "ui.scale.baseBorder";
    /** Client property key that records original FlowLayout gaps. */
    private static final String BASE_FLOW_GAP_KEY = "ui.scale.baseFlowGap";
    /** Client property key that records original GridLayout gaps. */
    private static final String BASE_GRID_GAP_KEY = "ui.scale.baseGridGap";
    /** Client property key that records original Box.Filler sizes. */
    private static final String BASE_FILLER_SIZE_KEY = "ui.scale.baseFillerSize";
    private static final String MANAGER_KEY = "ui.scale.manager";

    /** Main window managed by the scaling manager. */
    private final JFrame frame;
    /** Design-time base width. */
    private final int baseWidth;
    /** Design-time base height. */
    private final int baseHeight;
    /** Minimum allowed scale. */
    private final double minScale;
    /** Maximum allowed scale. */
    private final double maxScale;

    /**
     * Creates the scaling manager; base dimensions are used to convert the current window size into a scale.
     */
    private UiScaleManager(JFrame frame, int baseWidth, int baseHeight, double minScale, double maxScale) {
        this.frame = frame;
        this.baseWidth = Math.max(1, baseWidth);
        this.baseHeight = Math.max(1, baseHeight);
        this.minScale = minScale;
        this.maxScale = maxScale;
    }

    /**
     * Installs adaptive scaling listeners on the window and immediately applies the current scale once.
     */
    public static void install(JFrame frame, int baseWidth, int baseHeight) {
        UiScaleManager manager = new UiScaleManager(frame, baseWidth, baseHeight, 1.0d, 1.8d);
        frame.getRootPane().putClientProperty(MANAGER_KEY, manager);
        manager.applyCurrentScale();
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                manager.applyCurrentScale();
            }
        });
    }

    public static void reapplyCurrentScale(JFrame frame) {
        if (frame == null || frame.getRootPane() == null) {
            return;
        }
        Object managerValue = frame.getRootPane().getClientProperty(MANAGER_KEY);
        if (managerValue instanceof UiScaleManager manager) {
            manager.applyCurrentScale();
        }
    }

    public static void reapplyCurrentScale(JFrame frame, Component root) {
        if (frame == null || frame.getRootPane() == null || root == null) {
            return;
        }
        Object managerValue = frame.getRootPane().getClientProperty(MANAGER_KEY);
        if (managerValue instanceof UiScaleManager manager) {
            manager.applyCurrentScale(root);
        }
    }

    /**
     * Recalculates the scale from the current window size and recursively scales components in the window.
     */
    private void applyCurrentScale() {
        double scale = calculateScale(frame.getWidth(), frame.getHeight());
        scaleRecursively(frame.getRootPane(), scale);
        frame.revalidate();
        frame.repaint();
    }

    /**
     * Scales only the specified dynamic subtree to avoid recalculating the whole window during local refreshes such as search results.
     */
    private void applyCurrentScale(Component root) {
        double scale = calculateScale(frame.getWidth(), frame.getHeight());
        scaleRecursively(root, scale);
        root.revalidate();
        root.repaint();
    }

    /**
     * Uses the smaller width/height scale to avoid oversized content in either direction.
     */
    private double calculateScale(int width, int height) {
        double widthScale = width / (double) baseWidth;
        double heightScale = height / (double) baseHeight;
        double rawScale = Math.min(widthScale, heightScale);
        if (rawScale < minScale) {
            return minScale;
        }
        if (rawScale > maxScale) {
            return maxScale;
        }
        return rawScale;
    }

    /**
     * Traverses the component tree deeply and applies font, border, and spacing scale to each Swing component.
     */
    private void scaleRecursively(Component component, double scale) {
        if (component instanceof JComponent jComponent) {
            scaleFont(jComponent, scale);
            scaleBorder(jComponent, scale);
            scaleLayoutGap(jComponent, scale);
            scaleButtonMargin(jComponent, scale);
            scaleBoxFiller(jComponent, scale);
        }

        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                scaleRecursively(child, scale);
            }
        }
    }

    /**
     * Scales component fonts; saves the original font size on first encounter and uses it for later calculations.
     */
    private void scaleFont(JComponent component, double scale) {
        Font font = component.getFont();
        if (font == null) {
            return;
        }
        Object baseValue = component.getClientProperty(BASE_FONT_SIZE_KEY);
        float baseSize;
        if (baseValue instanceof Float f) {
            baseSize = f;
        } else {
            baseSize = font.getSize2D();
            component.putClientProperty(BASE_FONT_SIZE_KEY, baseSize);
        }
        float scaledSize = Math.max(11f, (float) (baseSize * scale));
        component.setFont(font.deriveFont(scaledSize));
    }

    /**
     * Scales button padding so button text does not feel cramped after window enlargement.
     */
    private void scaleButtonMargin(JComponent component, double scale) {
        if (!(component instanceof AbstractButton button)) {
            return;
        }
        Insets margin = button.getMargin();
        if (margin == null) {
            return;
        }
        Object baseMarginValue = component.getClientProperty(BASE_BUTTON_MARGIN_KEY);
        Insets baseMargin;
        if (baseMarginValue instanceof Insets insets) {
            baseMargin = insets;
        } else {
            baseMargin = copyInsets(margin);
            component.putClientProperty(BASE_BUTTON_MARGIN_KEY, baseMargin);
        }
        button.setMargin(scaleInsets(baseMargin, scale));
    }

    /**
     * Scales whitespace inside component borders.
     */
    private void scaleBorder(JComponent component, double scale) {
        Border currentBorder = component.getBorder();
        if (currentBorder == null) {
            return;
        }
        Object baseBorderValue = component.getClientProperty(BASE_BORDER_KEY);
        Border baseBorder;
        if (baseBorderValue instanceof Border border) {
            baseBorder = border;
        } else {
            baseBorder = currentBorder;
            component.putClientProperty(BASE_BORDER_KEY, baseBorder);
        }
        Border scaledBorder = scaleBorderValue(baseBorder, scale);
        if (scaledBorder != null) {
            component.setBorder(scaledBorder);
        }
    }

    /**
     * Recursively scales EmptyBorder and CompoundBorder while leaving other borders unchanged.
     */
    private Border scaleBorderValue(Border border, double scale) {
        if (border instanceof EmptyBorder emptyBorder) {
            Insets insets = emptyBorder.getBorderInsets();
            return BorderFactory.createEmptyBorder(
                    scaled(insets.top, scale),
                    scaled(insets.left, scale),
                    scaled(insets.bottom, scale),
                    scaled(insets.right, scale)
            );
        }
        if (border instanceof CompoundBorder compoundBorder) {
            Border outside = scaleBorderValue(compoundBorder.getOutsideBorder(), scale);
            Border inside = scaleBorderValue(compoundBorder.getInsideBorder(), scale);
            if (outside != null && inside != null) {
                return BorderFactory.createCompoundBorder(outside, inside);
            }
        }
        return border;
    }

    /**
     * Scales horizontal and vertical gaps in FlowLayout and GridLayout.
     */
    private void scaleLayoutGap(JComponent component, double scale) {
        LayoutManager layoutManager = component.getLayout();
        if (layoutManager instanceof FlowLayout flowLayout) {
            int[] baseGap = (int[]) component.getClientProperty(BASE_FLOW_GAP_KEY);
            if (baseGap == null) {
                baseGap = new int[]{flowLayout.getHgap(), flowLayout.getVgap()};
                component.putClientProperty(BASE_FLOW_GAP_KEY, baseGap);
            }
            flowLayout.setHgap(scaled(baseGap[0], scale));
            flowLayout.setVgap(scaled(baseGap[1], scale));
            return;
        }
        if (layoutManager instanceof GridLayout gridLayout) {
            int[] baseGap = (int[]) component.getClientProperty(BASE_GRID_GAP_KEY);
            if (baseGap == null) {
                baseGap = new int[]{gridLayout.getHgap(), gridLayout.getVgap()};
                component.putClientProperty(BASE_GRID_GAP_KEY, baseGap);
            }
            gridLayout.setHgap(scaled(baseGap[0], scale));
            gridLayout.setVgap(scaled(baseGap[1], scale));
        }
    }

    /**
     * Scales Box.Filler spacer components so UI whitespace changes with the window.
     */
    private void scaleBoxFiller(JComponent component, double scale) {
        if (!(component instanceof Box.Filler filler)) {
            return;
        }
        Object baseSizeValue = component.getClientProperty(BASE_FILLER_SIZE_KEY);
        Dimension baseSize;
        if (baseSizeValue instanceof Dimension dimension) {
            baseSize = dimension;
        } else {
            baseSize = filler.getPreferredSize();
            component.putClientProperty(BASE_FILLER_SIZE_KEY, baseSize);
        }
        Dimension preferred = scaleDimension(baseSize, scale);
        filler.changeShape(preferred, preferred, preferred);
    }

    /** Scales all four Insets values by ratio. */
    private Insets scaleInsets(Insets baseInsets, double scale) {
        return new Insets(
                scaled(baseInsets.top, scale),
                scaled(baseInsets.left, scale),
                scaled(baseInsets.bottom, scale),
                scaled(baseInsets.right, scale)
        );
    }

    /** Copies Insets so later changes do not affect the original padding record. */
    private Insets copyInsets(Insets insets) {
        return new Insets(insets.top, insets.left, insets.bottom, insets.right);
    }

    /** Scales a Dimension's width and height by ratio. */
    private Dimension scaleDimension(Dimension baseSize, double scale) {
        return new Dimension(
                Math.max(0, scaled(baseSize.width, scale)),
                Math.max(0, scaled(baseSize.height, scale))
        );
    }

    /** Scales one integer size by ratio and rounds it. */
    private int scaled(int value, double scale) {
        return Math.max(0, (int) Math.round(value * scale));
    }
}
