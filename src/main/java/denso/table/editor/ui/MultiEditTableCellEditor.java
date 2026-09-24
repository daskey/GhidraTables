/* GhidraTables - Apache License, Version 2.0 */
package denso.table.editor.ui;

import java.awt.Component;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.EventObject;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.TableCellEditor;

/** Delegates a complete edit to the owner, so validation and undo cover the whole selection. */
public class MultiEditTableCellEditor extends AbstractCellEditor implements TableCellEditor {
    @FunctionalInterface
    public interface CommitHandler {
        int commit(double physicalValue, int[] modelRows, int[] modelColumns);
    }

    private final JTextField field = new JTextField();
    private final CommitHandler commitHandler;
    private int[] rows;
    private int[] columns;
    private boolean replacing;
    private boolean initializing;
    private boolean changed;
    private int lastModifiedCount;
    private Runnable changeListener = () -> {};

    public MultiEditTableCellEditor(CommitHandler commitHandler) {
        this.commitHandler = commitHandler;
        field.setHorizontalAlignment(SwingConstants.CENTER);
        field.setFont(GhidraTheme.textFieldFont());
        field.setForeground(GhidraTheme.textFieldForeground());
        field.setCaretColor(GhidraTheme.textFieldCaret());
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(GhidraTheme.focusRingColor(), 2),
                BorderFactory.createEmptyBorder(1, 3, 1, 3)));
        field.addActionListener(e -> stopCellEditing());
        field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        field.getActionMap().put("cancel", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                cancelCellEditing();
            }
        });
        field.getDocument().addDocumentListener(new DocumentListener() {
            private void updated() {
                if (!initializing) {
                    changed = true;
                    changeListener.run();
                }
            }
            @Override public void insertUpdate(DocumentEvent e) { updated(); }
            @Override public void removeUpdate(DocumentEvent e) { updated(); }
            @Override public void changedUpdate(DocumentEvent e) { updated(); }
        });
    }

    @Override
    public boolean isCellEditable(EventObject event) {
        replacing = event instanceof KeyEvent key && !key.isActionKey()
                && key.getKeyChar() != KeyEvent.CHAR_UNDEFINED
                && !Character.isISOControl(key.getKeyChar())
                && !key.isControlDown() && !key.isMetaDown() && !key.isAltDown();
        return !(event instanceof MouseEvent mouse) || mouse.getClickCount() >= 2;
    }

    @Override
    public Component getTableCellEditorComponent(JTable table, Object value,
            boolean selected, int row, int column) {
        rows = java.util.Arrays.stream(table.getSelectedRows())
                .map(table::convertRowIndexToModel).toArray();
        columns = java.util.Arrays.stream(table.getSelectedColumns())
                .map(table::convertColumnIndexToModel).toArray();
        // Mouse editing may begin before JTable updates its selection.
        if (!table.isCellSelected(row, column)) {
            rows = new int[] {table.convertRowIndexToModel(row)};
            columns = new int[] {table.convertColumnIndexToModel(column)};
        }
        initializing = true;
        field.setText(replacing ? "" : value == null ? "" : value.toString());
        field.setBackground(GhidraTheme.textFieldBackground());
        field.setToolTipText(null);
        field.selectAll();
        initializing = false;
        changed = false;
        lastModifiedCount = 0;
        return field;
    }

    @Override public Object getCellEditorValue() { return field.getText().trim(); }
    @Override public boolean shouldSelectCell(EventObject event) { return false; }

    public boolean hasPendingEdit() { return changed; }
    public void setChangeListener(Runnable listener) { changeListener = listener; }

    @Override
    public boolean stopCellEditing() {
        if (changed) {
            try {
                double value = Double.parseDouble(field.getText().trim());
                if (!Double.isFinite(value)) throw new IllegalArgumentException("Value must be finite.");
                lastModifiedCount = commitHandler.commit(value, rows, columns);
            } catch (IllegalArgumentException ex) {
                field.setBackground(GhidraTheme.invalidFieldBackground());
                field.setToolTipText(ex.getMessage());
                return false;
            }
        }
        changed = false;
        // The owner already applied the batch. Remove the editor without JTable
        // writing the lead cell a second time (or rewriting unchanged rounded text).
        fireEditingCanceled();
        changeListener.run();
        return true;
    }

    @Override
    public void cancelCellEditing() {
        changed = false;
        lastModifiedCount = 0;
        fireEditingCanceled();
        changeListener.run();
    }

    public int getLastModifiedCount() { return lastModifiedCount; }
}
