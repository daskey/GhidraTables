/* ###
 * GhidraTables - Ghidra extension for Denso ECU calibration table editing
 * Apache License, Version 2.0
 */
package denso.table.editor.ui;

import java.awt.*;
import java.awt.event.*;
import java.util.EventObject;
import javax.swing.*;
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
 * <p>Keyboard shortcuts:
 * <ul>
 *   <li><b>Enter / Tab</b> – commit and move to next cell</li>
 *   <li><b>Escape</b>      – cancel (no change)</li>
 *   <li><b>F2</b>          – start editing the selected cell</li>
 * </ul>
 */
public class MultiEditTableCellEditor extends AbstractCellEditor
        implements TableCellEditor {

    private final JTextField field;
    private JTable currentTable;

    // track how many cells were modified in the last commit
    private int lastModifiedCount = 0;

    public MultiEditTableCellEditor() {
        field = new JTextField();
        field.setHorizontalAlignment(SwingConstants.CENTER);
        field.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        field.setBorder(BorderFactory.createLineBorder(new Color(80, 140, 220), 2));

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

    // ── TableCellEditor ──────────────────────────────────────────────────────

    @Override
    public Component getTableCellEditorComponent(JTable table, Object value,
            boolean isSelected, int row, int column) {
        currentTable = table;
        field.setText(value != null ? value.toString() : "");
        return field;
    }

    @Override
    public Object getCellEditorValue() {
        return field.getText().trim();
    }

    @Override
    public boolean isCellEditable(EventObject e) {
        // Allow editing on double-click or any key press
        if (e instanceof MouseEvent me) {
            return me.getClickCount() >= 2;
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

        // Validate numeric input
        try {
            double newValue = Double.parseDouble(text);
            applyToSelectedCells(newValue);
        }
        catch (NumberFormatException ex) {
            // Flash the field red to signal bad input
            field.setBackground(new Color(255, 80, 80));
            Timer reset = new Timer(400, e -> field.setBackground(UIManager.getColor("TextField.background")));
            reset.setRepeats(false);
            reset.start();
            return false; // keep editing open
        }

        fireEditingStopped();
        return true;
    }

    @Override
    public void cancelCellEditing() {
        lastModifiedCount = 0;
        fireEditingCanceled();
    }

    // ── Multi-cell application ────────────────────────────────────────────────

    /**
     * Writes {@code newValue} to all currently selected editable cells in
     * {@link #currentTable}.  The call is made before the editor fires
     * "editingStopped", so the model change is visible immediately.
     */
    private void applyToSelectedCells(double newValue) {
        if (currentTable == null) return;

        TableModel model = currentTable.getModel();
        int[] selectedRows = currentTable.getSelectedRows();
        int[] selectedCols = currentTable.getSelectedColumns();

        lastModifiedCount = 0;

        for (int row : selectedRows) {
            for (int col : selectedCols) {
                // Convert view indices to model indices
                int modelRow = currentTable.convertRowIndexToModel(row);
                int modelCol = currentTable.convertColumnIndexToModel(col);

                if (model.isCellEditable(modelRow, modelCol)) {
                    model.setValueAt(String.valueOf(newValue), modelRow, modelCol);
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
