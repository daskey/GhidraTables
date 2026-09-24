package denso.table.editor.ui;

/** Parses rectangular spreadsheet text without silently trimming away empty edge cells. */
final class TableClipboard {
    private TableClipboard() {}

    static double[][] parse(String text) {
        String[] lines = text.replaceFirst("(?:\\r\\n|\\r|\\n)+\\z", "").split("\\R", -1);
        double[][] values = new double[lines.length][];
        for (int r = 0; r < lines.length; r++) {
            String[] cells = lines[r].split("\\t", -1);
            if (r > 0 && cells.length != values[0].length) {
                throw new IllegalArgumentException("Clipboard rows must have equal widths.");
            }
            values[r] = new double[cells.length];
            for (int c = 0; c < cells.length; c++) {
                try {
                    values[r][c] = Double.parseDouble(cells[c].trim());
                    if (!Double.isFinite(values[r][c])) throw new NumberFormatException();
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("Clipboard row " + (r + 1)
                            + ", column " + (c + 1) + " must contain a finite number.");
                }
            }
        }
        return values;
    }
}
