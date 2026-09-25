import java.awt.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.*;
import denso.table.editor.*;
import denso.table.editor.model.*;
import denso.table.editor.ui.*;
import docking.widgets.table.GFilterTable;
import generic.theme.ThemeManager;
import generic.theme.builtin.FlatDarkTheme;
import ghidra.GhidraApplicationLayout;
import ghidra.app.services.ProgramManager;
import ghidra.app.util.importer.*;
import ghidra.app.util.exporter.BinaryExporter;
import ghidra.app.util.opinion.Loaded;
import ghidra.base.project.GhidraProject;
import ghidra.framework.*;
import ghidra.framework.project.tool.GhidraTool;
import ghidra.program.model.lang.*;
import ghidra.program.model.listing.Program;
import ghidra.program.util.DefaultLanguageService;
import ghidra.util.task.TaskMonitor;

/** Captures the installed extension on a real imported ROM; no mock Program or rendered mockups. */
public class CaptureReadme {
    private static Path output;
    private static Robot robot;
    private static GhidraTool tool;
    private static Program program;
    private static DensoTableListProvider provider;

    public static void main(String[] args) throws Exception {
        try {
            run(args);
            System.out.println("CAPTURE SUCCESS");
            System.exit(0);
        } catch (Throwable ex) {
            ex.printStackTrace();
            if (robot != null && output != null) ImageIO.write(robot.createScreenCapture(
                    new Rectangle(Toolkit.getDefaultToolkit().getScreenSize())), "png", output.resolve("failure.png").toFile());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Arguments: GHIDRA_DIR ROM_PATH EMPTY_OUTPUT_DIR");
        output = Path.of(args[2]).toAbsolutePath();
        Files.createDirectory(output);
        byte[] original = Files.readAllBytes(Path.of(args[1]));
        String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original));
        if (!hash.equals("7c3139546f12fdf68e5d19e985d92e58f1005e6286c6ac0b49a5a58765fb952e"))
            throw new IllegalArgumentException("This capture recipe is for the documented ZF2L101b00G image.");
        System.out.println("ROM SHA-256 " + hash);
        GhidraApplicationConfiguration config = new GhidraApplicationConfiguration();
        config.setShowSplashScreen(false);
        Application.initializeApplication(new GhidraApplicationLayout(new File(args[0])), config);
        edt(() -> { ThemeManager.getInstance().setTheme(new FlatDarkTheme()); return null; });
        robot = new Robot();
        Path projectDir = output.resolve("project"); Files.createDirectory(projectDir);
        GhidraProject project = GhidraProject.createProject(projectDir.toString(), "ReadmeDemo", true);
        // Table data only: use the bundled big-endian 32-bit language without code analysis.
        Language language = DefaultLanguageService.getLanguageService().getLanguage(new LanguageID("PowerPC:BE:32:default"));
        Loaded<Program> loaded = AutoImporter.importAsBinary(new File(args[1]), project.getProject(), "/",
                language, language.getDefaultCompilerSpec(), CaptureReadme.class, new MessageLog(), TaskMonitor.DUMMY);
        program = loaded.getDomainObject();
        int tx = program.startTransaction("Map ZF2L101b00G flash");
        try {
            program.setImageBase(program.getAddressFactory().getDefaultAddressSpace().getAddress(0x08f9c000L), true);
            program.getMemory().getBlocks()[0].setName("Flash");
        } finally { program.endTransaction(tx, true); }
        loaded.save(project.getProject(), new MessageLog(), TaskMonitor.DUMMY);
        System.out.println("Imported " + program.getName() + " at " + program.getMinAddress() + ".." + program.getMaxAddress());
        edt(() -> {
            tool = new GhidraTool(project.getProject(), "CodeBrowser");
            tool.addPlugins(List.of("ghidra.app.plugin.core.progmgr.ProgramManagerPlugin", "denso.table.editor.GhidraTablesPlugin"));
            tool.getService(ProgramManager.class).openProgram(program);
            GhidraTablesPlugin plugin = (GhidraTablesPlugin) tool.getManagedPlugins().stream()
                    .filter(p -> p instanceof GhidraTablesPlugin).findFirst().orElseThrow();
            provider = field(plugin, "listProvider", DensoTableListProvider.class);
            tool.showComponentProvider(provider, true);
            tool.getToolFrame().setBounds(25, 25, 1500, 910); tool.setVisible(true);
            provider.scanProgram(program);
            return null;
        });
        waitUntil(() -> field(provider, "statusLabel", JLabel.class).getText().startsWith("Found"), 120_000);
        GFilterTable<?> filter = field(provider, "filterTable", GFilterTable.class);
        waitUntil(() -> filter.getTable().getRowCount() > 0, 20_000);
        @SuppressWarnings("unchecked") List<DensoTable> tables = (List<DensoTable>) field(provider, "currentTables", List.class);
        System.out.println("SCAN " + field(provider, "statusLabel", JLabel.class).getText());
        StringBuilder listing = new StringBuilder("header\tdimensions\ttype\tmac\tpayload\n");
        for (DensoTable t : tables) listing.append(String.format("%08X\t%s\t%s\t%s\t%08X%n",
                t.getHeaderAddress(), t.getDimensions(), t.getDataType(), t.isHasMAC(), payload(t)));
        Files.writeString(output.resolve("scan-results.tsv"), listing);
        DensoTable two = tables.stream().filter(t -> t.is2D() && payload(t) == 0x09296bb4L).findFirst().orElseThrow();
        select(filter.getTable(), two);
        Window listWindow = edt(() -> SwingUtilities.getWindowAncestor(provider.getComponent()));
        edt(() -> { listWindow.setBounds(25, 25, 1500, 910); return null; });
        capture(listWindow, "01-table-list.png");
        GhidraTablesEditorFrame editor = openSelected(two);
        edt(() -> { editor.setBounds(25, 25, 1540, 930); return null; });
        capture(editor, "02-table-editor.png");
        System.out.println("2D " + two.getAddressHex() + " " + two.getDimensions() + " " + two.getDataType() + " payload=" + Long.toHexString(payload(two)));

        SwingUtilities.invokeLater(() -> invoke(editor, "exportCsv"));
        waitUntil(() -> chooser() != null, 10_000);
        edt(() -> { JFileChooser c = chooser(); c.setSelectedFile(output.resolve("table.csv").toFile()); c.approveSelection(); return null; });
        waitUntil(() -> Files.isRegularFile(output.resolve("table.csv")), 10_000);
        if (Files.readAllLines(output.resolve("table.csv")).size() != ((DensoTable2D)two).getCountY() + 1) throw new AssertionError("CSV dimensions");
        System.out.println("VERIFIED: CSV export with both axes");
        edt(() -> { field(editor, "view3DToggle", JToggleButton.class).doClick(); return null; });
        Thread.sleep(1500);
        Object surface = field(editor, "surface3DPanel", Surface3DPanel.class);
        if (field(surface, "chart", Object.class) == null) throw new AssertionError("3D initialization failed");
        capture(editor, "03-surface-view.png");
        edt(() -> { field(editor, "view3DToggle", JToggleButton.class).doClick(); return null; });
        JTable grid = field(editor, "grid", JTable.class);
        byte[] before = new byte[two.getCountX() * ((DensoTable2D)two).getCountY() * two.getDataType().getValueSize()];
        readPayload(two, before);
        edt(() -> {
            JToggleButton inspector = field(editor, "inspectorToggle", JToggleButton.class);
            if (!inspector.isSelected()) inspector.doClick();
            grid.setRowSelectionInterval(2, 3); grid.setColumnSelectionInterval(3, 5);
            double value = two.toPhysical(((DensoTable2D)two).getZ(2, 3)) + 1;
            if (!grid.editCellAt(2, 3)) throw new AssertionError("Cannot edit");
            ((JTextField)grid.getEditorComponent()).setText(Double.toString(value));
            if (!grid.getCellEditor().stopCellEditing() || !editor.hasUnsavedChanges()) throw new AssertionError("Edit not committed");
            return null;
        });
        capture(editor, "04-multi-cell-edit.png");
        edt(() -> { field(editor, "saveBtn", JButton.class).doClick(); return null; });
        if (edt(editor::hasUnsavedChanges)) throw new AssertionError("Save did not finish");
        byte[] after = before.clone(); readPayload(two, after);
        if (Arrays.equals(before, after)) throw new AssertionError("Save did not change bytes");
        if (!new BinaryExporter().export(output.resolve("edited.bin").toFile(), program, null, TaskMonitor.DUMMY)) throw new AssertionError("Binary export failed");
        if (Files.size(output.resolve("edited.bin")) != original.length) throw new AssertionError("Export size");
        edt(() -> { invoke(editor, "undoLastOperation"); field(editor, "saveBtn", JButton.class).doClick(); return null; });
        readPayload(two, after);
        if (!Arrays.equals(before, after)) throw new AssertionError("Undo + save did not restore bytes");
        new BinaryExporter().export(output.resolve("restored.bin").toFile(), program, null, TaskMonitor.DUMMY);
        if (!Arrays.equals(Files.readAllBytes(output.resolve("restored.bin")), original)) throw new AssertionError("Restored binary mismatch");
        System.out.println("VERIFIED: multi-cell typing, Save, undo, binary export, and exact whole-image restoration");

        SwingUtilities.invokeLater(() -> invoke(editor, "createStructure"));
        waitUntil(() -> structureDialog() != null, 10_000);
        capture(structureDialog(), "06-apply-structure.png");
        edt(() -> { button(structureDialog(), "Apply").doClick(); return null; });
        waitUntil(() -> structureDialog() == null, 10_000);
        if (program.getListing().getDataAt(program.getAddressFactory().getDefaultAddressSpace().getAddress(two.getHeaderAddress())) == null) throw new AssertionError("Header missing");
        System.out.println("VERIFIED: Apply Structure dialog and header data type");
        edt(() -> { editor.dispose(); return null; });
        DensoTable one = tables.stream().filter(t -> !t.is2D() && t.isHasMAC() && t.getCountX() >= 8 && t.getCountX() <= 12).findFirst().orElseThrow();
        waitUntil(() -> filter.getTable().getRowCount() == tables.size(), 10_000);
        select(filter.getTable(), one);
        GhidraTablesEditorFrame curve = openSelected(one);
        edt(() -> { curve.setBounds(25, 25, 1500, 580); return null; });
        capture(curve, "05-curve-editor.png");
        System.out.println("1D " + one.getAddressHex() + " " + one.getDimensions() + " " + one.getDataType() + " payload=" + Long.toHexString(payload(one)));
        edt(() -> { curve.dispose(); tool.dispose(); return null; });
        loaded.release(CaptureReadme.class); project.close();
        if (!Arrays.equals(original, Files.readAllBytes(Path.of(args[1])))) throw new AssertionError("Input ROM changed");
    }

    private static long payload(DensoTable t) { return t instanceof DensoTable2D d ? d.getPtrZ() : ((DensoTable1D)t).getPtrY(); }
    private static void readPayload(DensoTable t, byte[] b) throws Exception {
        program.getMemory().getBytes(program.getAddressFactory().getDefaultAddressSpace().getAddress(payload(t)), b);
    }
    private static JFileChooser chooser() {
        for (Window w : Window.getWindows()) if (w.isShowing()) { JFileChooser c = component(w, JFileChooser.class); if (c != null) return c; }
        return null;
    }
    private static JDialog structureDialog() {
        for (Window w : Window.getWindows()) if (w instanceof JDialog d && w.isShowing() && d.getTitle().contains("Apply")) return d;
        return null;
    }
    private static <T> T component(Container root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        for (Component c : root.getComponents()) if (c instanceof Container child) { T result = component(child, type); if (result != null) return result; }
        return null;
    }
    private static JButton button(Container root, String name) {
        if (root instanceof JButton b && name.equals(b.getText())) return b;
        for (Component c : root.getComponents()) if (c instanceof Container child) { JButton b = button(child, name); if (b != null) return b; }
        return null;
    }
    private static void select(JTable grid, DensoTable table) throws Exception {
        edt(() -> {
            for (int r = 0; r < grid.getRowCount(); r++) if (table.getAddressHex().equals(grid.getValueAt(r, 4))) {
                grid.setRowSelectionInterval(r, r); grid.scrollRectToVisible(grid.getCellRect(r, 0, true)); return null;
            }
            throw new AssertionError("Table not in list");
        });
    }
    private static GhidraTablesEditorFrame openSelected(DensoTable table) throws Exception {
        edt(() -> { invoke(provider, "openSelectedTable"); return null; });
        return edt(() -> ((List<?>)field(provider, "editors", List.class)).stream().map(x -> (GhidraTablesEditorFrame)x)
                .filter(e -> e.getHeaderAddress() == table.getHeaderAddress()).findFirst().orElseThrow());
    }
    private static void capture(Window window, String name) throws Exception {
        edt(() -> { window.toFront(); window.repaint(); return null; }); Thread.sleep(650);
        Rectangle rect = edt(() -> new Rectangle(window.getLocationOnScreen(), window.getSize()));
        ImageIO.write(robot.createScreenCapture(rect), "png", output.resolve(name).toFile());
        System.out.println("Captured " + name + " " + rect.width + "x" + rect.height);
    }
    private static void waitUntil(BooleanSupplier condition, long timeout) throws Exception {
        long end = System.currentTimeMillis() + timeout;
        while (!edt(condition::getAsBoolean)) { if (System.currentTimeMillis() > end) throw new AssertionError("Timed out waiting for UI"); Thread.sleep(100); }
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
    private static <T> T field(Object target, String name, Class<T> type) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return type.cast(f.get(target)); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private static void invoke(Object target, String name) {
        try { Method m = target.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(target); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
