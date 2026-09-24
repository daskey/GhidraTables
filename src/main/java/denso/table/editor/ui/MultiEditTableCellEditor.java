/* ###
 * GhidraTables - Ghidra extension for Denso ECU calibration table editing
 * Apache License, Version 2.0
 */
package denso.table.editor.ui;

import java.awt.*;
import java.awt.event.*;
import java.util.EventObject;
import java.util.function.Function;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.*;

/**
 * A cell editor that applies the entered value to <em>all currently selected
 * editable cells</em> when editing stops, enabling true multi-cell editing.
 *
 * <p>Usage: install on a {@link JTable} with
 * {@link JTable#setDefaultEditor(Class, TableCellEditor)}.
 * The editor must be installed <em>after</em> the table model is set so that
 * the column/row types are known.
 *
 * <p>Committing without having typed anything is a no-op: nothing is written,
 * not even to the edited cell. This keeps an accidental F2/Enter (or a focus
 * change while the editor is open) from re-writing values from their rounded
 * display text or copying one cell across a whole selection.
 *
 * <p>Keyboard shortcuts:
 * <ul>
 *   <li><b>Typing</b>       – start editing, replacing the current value</li>
 *   <li><b>F2 / double-click</b> – start editing the current value</li>
 *   <li><b>Enter / Tab</b> – commit and move to next cell</li>
 *   <li><b>Escape</b>      – cancel (no change)</li>
 * </ul>
 */
public class MultiEditTableCellEditor extends AbstractCellEditor
        implements TableCellEditor {

    private final JTextField field;
    private JTable currentTable;

    /** True when editing was started by typing a character, which replaces the value. */
    private boolean replaceOnStart;

    /** True once the user has changed the field text in the current edit session. */
    private boolean userEdited;

    /** Suppresses {@link #userEdited} tracking while the editor sets its own text. */
    private boolean settingText;

    /** Returns a user-facing error for unacceptable input, or {@code null} if it is valid. */
    private Function<String, String> validator = text -> null;

    /** Invoked once before a committed value is written to the model. */
    private Runnable beforeApply = () -> {};

    // track how many cells were modified in the last commit
    private int lastModifiedCount = 0;

    public MultiEditTableCellEditor() {
        field = new JTextField();
        field.setHorizontalAlignment(SwingConstants.CENTER);
        field.setFont(GhidraTheme.textFieldFont());
        field.setBackground(GhidraTheme.textFieldBackground());
        field.setForeground(GhidraTheme.textFieldForeground());
        field.setCaretColor(GhidraTheme.textFieldCaret());
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(GhidraTheme.focusRingColor(), 2),
                BorderFactory.createEmptyBorder(1, 3, 1, 3)));

        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { noteEdit(); }
            @Override public void removeUpdate(DocumentEvent e)  { noteEdit(); }
            @Override public void changedUpdate(DocumentEvent e) { /* attributes only */ }
        });

        // Commit on Enter, cancel on Escape
        field.addActionListener(e -> stopCellEditing());

        field.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    cancelCellEditing();
                }
            }
        });

        // Select-all on focus so the user can immediately type a replacement
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                SwingUtilities.invokeLater(field::selectAll);
            }
        });
    }

    /**
     * Sets the check applied to committed text before anything is written.
     * The function returns a user-facing error message, or {@code null} to accept.
     */
    public void setValidator(Function<String, String> validator) {
        this.validator = validator != null ? validator : text -> null;
    }

    /** Sets a hook that runs once before a committed value is written (e.g. to record undo). */
    public void setBeforeApply(Runnable beforeApply) {
        this.beforeApply = beforeApply != null ? beforeApply : () -> {};
    }

    // ── TableCellEditor ──────────────────────────────────────────────────────

    @Override
    public Component getTableCellEditorComponent(JTable table, Object value,
            boolean isSelected, int row, int column) {
        currentTable = table;
        String text = value != null ? value.toString().trim() : "";
        // When editing starts from a typed key, JTable forwards that key to this
        // field next; start empty so it replaces the value instead of appending.
        settingText = true;
        try {
            field.setText(replaceOnStart ? "" : text);
        }
        finally {
            settingText = false;
        }
        userEdited = false;
        replaceOnStart = false;
        field.setToolTipText(null);
        field.setBackground(GhidraTheme.textFieldBackground());
        return field;
    }

    private void noteEdit() {
        if (!settingText) {
            userEdited = true;
        }
    }

    @Override
    public Object getCellEditorValue() {
        return field.getText().trim();
    }

    @Override
    public boolean isCellEditable(EventObject e) {
        replaceOnStart = false;
        // Allow editing on double-click or a plain key press
        if (e instanceof MouseEvent me) {
            return me.getClickCount() >= 2;
        }
        if (e instanceof KeyEvent ke) {
            // Unhandled shortcuts (Ctrl+S, Cmd+Z, ...) must not open the editor.
            if (ke.isControlDown() || ke.isMetaDown() || ke.isAltDown()) {
                return false;
            }
            char c = ke.getKeyChar();
            replaceOnStart = c != KeyEvent.CHAR_UNDEFINED && !Character.isISOControl(c);
        }
        return true;
    }

    @Override
    public boolean shouldSelectCell(EventObject e) {
        return true;
    }

    @Override
    public boolean stopCellEditing() {
        String text = field.getText().trim();
        lastModifiedCount = 0;

        if (!userEdited) {
            // Nothing was typed; don't rewrite or broadcast the value. Cancel
            // rather than stop so JTable doesn't push the text back either.
            fireEditingCanceled();
            return true;
        }

        String error = validate(text);
        if (error != null) {
            flashInvalid(error);
            return false; // keep editing open
        }

        beforeApply.run();
        applyToSelectedCells(text);
        fireEditingStopped();
        return true;
    }

    @Override
    public void cancelCellEditing() {
        lastModifiedCount = 0;
        fireEditingCanceled();
    }

    private String validate(String text) {
        double value;
        try {
            value = Double.parseDouble(text);
        }
        catch (NumberFormatException ex) {
            return text.isEmpty() ? "Enter a number." : "'" + text + "' is not a number.";
        }
        if (!Double.isFinite(value)) {
            return "Value must be a finite number.";
        }
        return validator.apply(text);
    }

    private void flashInvalid(String message) {
        field.setToolTipText(message);
        field.setBackground(GhidraTheme.invalidFieldBackground());
        Timer reset = new Timer(400, e -> field.setBackground(GhidraTheme.textFieldBackground()));
        reset.setRepeats(false);
        reset.start();
    }

    // ── Multi-cell application ────────────────────────────────────────────────

    /**
     * Writes {@code text} to all currently selected editable cells in
     * {@link #currentTable}.  The call is made before the editor fires
     * "editingStopped", so the model change is visible immediately.
     */
    private void applyToSelectedCells(String text) {
        if (currentTable == null) return;

        TableModel model = currentTable.getModel();
        int[] selectedRows = currentTable.getSelectedRows();
        int[] selectedCols = currentTable.getSelectedColumns();

        for (int row : selectedRows) {
            for (int col : selectedCols) {
                // Convert view indices to model indices
                int modelRow = currentTable.convertRowIndexToModel(row);
                int modelCol = currentTable.convertColumnIndexToModel(col);

                if (model.isCellEditable(modelRow, modelCol)) {
                    model.setValueAt(text, modelRow, modelCol);
                    lastModifiedCount++;
                }
            }
        }
    }

    /**
     * Returns how many cells were modified during the last
     * {@link #stopCellEditing()} call (useful for status-bar messages).
     */
    public int getLastModifiedCount() {
        return lastModifiedCount;
    }
}
