package com.nectariich.create_flippies.content.design_workbench.annunciator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.nectariich.create_flippies.CreateFlippies;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Reads the CSVs a rack is described by, legends and lens colours, into an
 * {@link AnnunciatorDraft}. Java port of the Unity {@code AnnunciatorCsv}, so a file
 * written for one reads the same in the other.
 *
 * A file takes either shape, worked out from its first line rather than declared:
 * <ul>
 * <li>grid: one line per row of windows, one field per column, laid out like the
 * rack is. The one to write by hand.</li>
 * <li>list: a header naming row/column/text (and optionally colour and id), then one
 * line per window, rows and columns counted from 1 at the top left.</li>
 * </ul>
 * A list with both a text and a colour column describes the whole rack. A grid is
 * legends unless every field in it is a lens colour, in which case it is colours, so
 * the two files of a grid-described rack can be dropped together without saying
 * which is which ({@link #importFiles}).
 *
 * In a legend a line break is written "\n" or "|", or as a real one inside a quoted
 * field. A field of "-" alone blanks the cell off: frame, no window. Commas,
 * semicolons and tabs all work as the separator; the first line decides which.
 */
public final class AnnunciatorCsv {

    private static final String LANG = "gui." + CreateFlippies.MOD_ID + ".design_workbench.csv.";

    private static final String[] ROW_KEYS = { "row", "r", "line" };
    private static final String[] COLUMN_KEYS = { "column", "col", "c" };
    private static final String[] TEXT_KEYS = { "text", "label", "legend", "value", "window" };
    private static final String[] COLOR_KEYS = { "color", "colour", "lens" };
    private static final String[] ID_KEYS = { "id", "tag", "point" };

    /** What a file is found to hold. */
    public enum Content { LABELS, COLOURS, BOTH }

    /** A file that cannot be read, with the reason as a translation key under the CSV strings. */
    public static final class CsvException extends Exception {
        private final transient Component reason;

        CsvException(String key, Object... args) {
            super(key);
            reason = Component.translatable(LANG + "error." + key, args);
        }

        public Component reason() {
            return reason;
        }
    }

    /**
     * A parsed file, addressed the way the rack is: row 0 at the top, column 0 on the
     * left. Colours and ids are only filled in by the list form.
     */
    public static final class Sheet {
        public int rows, columns;
        public boolean listForm;
        public String[][] values, colors, ids;

        public String value(int row, int column) {
            return at(values, row, column);
        }

        public String idAt(int row, int column) {
            return at(ids, row, column);
        }

        /** Colours come from the colour column when there is one, and from the grid itself otherwise. */
        public String lensAt(int row, int column) {
            return colors != null ? at(colors, row, column) : at(values, row, column);
        }

        /** What this file holds, as {@link #importFiles} sorts it. */
        public Content content() {
            if (listForm)
                return values != null && colors != null ? Content.BOTH : colors != null ? Content.COLOURS
                        : Content.LABELS;
            boolean any = false;
            for (String[] line : values)
                for (String field : line) {
                    if (field == null || field.isBlank() || field.trim().equals("-"))
                        continue;
                    if (LensColor.parse(field) == null)
                        return Content.LABELS;
                    any = true;
                }
            return any ? Content.COLOURS : Content.LABELS;
        }

        private static String at(String[][] cells, int row, int column) {
            if (cells == null || row < 0 || row >= cells.length)
                return null;
            String[] line = cells[row];
            return line != null && column >= 0 && column < line.length ? line[column] : null;
        }
    }

    private AnnunciatorCsv() {}

    // ------------------------------------------------------------------ parse

    public static Sheet parse(String text) throws CsvException {
        if (text == null || text.isBlank())
            throw new CsvException("empty");
        // Normalising line endings up front costs nothing inside quoted fields and
        // saves the tokenizer a case
        String body = text.replace("\r\n", "\n").replace('\r', '\n');
        if (body.startsWith("﻿"))
            body = body.substring(1);
        List<List<String>> records = tokenize(body, detectDelimiter(body));
        while (!records.isEmpty() && isBlank(records.get(records.size() - 1)))
            records.remove(records.size() - 1);
        if (records.isEmpty())
            throw new CsvException("no_rows");
        return isHeader(records.get(0)) ? parseList(records) : parseGrid(records);
    }

    private static Sheet parseGrid(List<List<String>> records) {
        int columns = 0;
        for (List<String> record : records)
            columns = Math.max(columns, record.size());
        Sheet sheet = new Sheet();
        sheet.rows = records.size();
        sheet.columns = columns;
        sheet.values = new String[records.size()][];
        for (int row = 0; row < records.size(); row++) {
            sheet.values[row] = new String[columns];
            for (int column = 0; column < records.get(row).size(); column++)
                sheet.values[row][column] = records.get(row).get(column);
        }
        return sheet;
    }

    private static Sheet parseList(List<List<String>> records) throws CsvException {
        List<String> header = records.get(0);
        int rowIndex = indexOfKey(header, ROW_KEYS);
        int columnIndex = indexOfKey(header, COLUMN_KEYS);
        int textIndex = indexOfKey(header, TEXT_KEYS);
        int colorIndex = indexOfKey(header, COLOR_KEYS);
        int idIndex = indexOfKey(header, ID_KEYS);
        if (rowIndex < 0 || columnIndex < 0)
            throw new CsvException("list_header");
        if (textIndex < 0 && colorIndex < 0)
            throw new CsvException("list_fields");

        // Two passes: the first finds how big the rack has to be, the second fills
        // it in, so the entries can arrive in any order
        int rows = 0, columns = 0;
        List<int[]> cells = new ArrayList<>();
        List<List<String>> entries = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> fields = records.get(i);
            if (isBlank(fields))
                continue;
            int row, column;
            try {
                row = Integer.parseInt(field(fields, rowIndex));
                column = Integer.parseInt(field(fields, columnIndex));
            } catch (NumberFormatException | NullPointerException e) {
                throw new CsvException("number", i + 1);
            }
            if (row < 1 || column < 1)
                throw new CsvException("from_one", i + 1);
            if (row > AnnunciatorRack.MAX_CELLS || column > AnnunciatorRack.MAX_CELLS)
                throw new CsvException("too_big", i + 1, AnnunciatorRack.MAX_CELLS);
            rows = Math.max(rows, row);
            columns = Math.max(columns, column);
            cells.add(new int[] { row - 1, column - 1 });
            entries.add(fields);
        }

        Sheet sheet = new Sheet();
        sheet.rows = rows;
        sheet.columns = columns;
        sheet.listForm = true;
        sheet.values = textIndex >= 0 ? new String[rows][columns] : null;
        sheet.colors = colorIndex >= 0 ? new String[rows][columns] : null;
        sheet.ids = idIndex >= 0 ? new String[rows][columns] : null;
        for (int i = 0; i < cells.size(); i++) {
            int row = cells.get(i)[0], column = cells.get(i)[1];
            List<String> fields = entries.get(i);
            if (sheet.values != null)
                sheet.values[row][column] = field(fields, textIndex);
            if (sheet.colors != null)
                sheet.colors[row][column] = field(fields, colorIndex);
            if (sheet.ids != null)
                sheet.ids[row][column] = field(fields, idIndex);
        }
        return sheet;
    }

    /** A first line is a header if it names the things a list is made of; "row" is no legend. */
    private static boolean isHeader(List<String> record) {
        int hits = 0;
        boolean hasRow = false, hasColumn = false;
        for (String field : record) {
            String name = field == null ? "" : field.trim().toLowerCase(Locale.ROOT);
            if (matches(name, ROW_KEYS)) {
                hasRow = true;
                hits++;
            } else if (matches(name, COLUMN_KEYS)) {
                hasColumn = true;
                hits++;
            } else if (matches(name, TEXT_KEYS) || matches(name, COLOR_KEYS) || matches(name, ID_KEYS)) {
                hits++;
            }
        }
        return hasRow && hasColumn && hits >= 3;
    }

    private static List<List<String>> tokenize(String text, char delimiter) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (quoted) {
                if (character != '"') {
                    field.append(character);
                } else if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"'); // "" inside a quoted field is one quote
                    i++;
                } else {
                    quoted = false;
                }
                continue;
            }
            if (character == '"' && field.isEmpty()) {
                quoted = true;
            } else if (character == delimiter) {
                push(record, field);
            } else if (character == '\n') {
                push(record, field);
                records.add(record);
                record = new ArrayList<>();
            } else {
                field.append(character);
            }
        }
        push(record, field);
        records.add(record);
        return records;
    }

    /** Whichever of comma, semicolon and tab shows up most on the first line. */
    private static char detectDelimiter(String text) {
        char[] candidates = { ',', ';', '\t' };
        int[] counts = new int[candidates.length];
        boolean quoted = false;
        for (char character : text.toCharArray()) {
            if (character == '"')
                quoted = !quoted;
            else if (quoted)
                continue;
            else if (character == '\n')
                break;
            else
                for (int i = 0; i < candidates.length; i++)
                    if (character == candidates[i])
                        counts[i]++;
        }
        int best = 0;
        for (int i = 1; i < candidates.length; i++)
            if (counts[i] > counts[best])
                best = i;
        return counts[best] > 0 ? candidates[best] : ',';
    }

    private static void push(List<String> record, StringBuilder field) {
        record.add(field.toString().trim());
        field.setLength(0);
    }

    private static boolean isBlank(List<String> record) {
        for (String field : record)
            if (field != null && !field.isBlank())
                return false;
        return true;
    }

    private static String field(List<String> fields, int index) {
        return index >= 0 && index < fields.size() ? fields.get(index) : null;
    }

    private static int indexOfKey(List<String> header, String[] keys) {
        for (int i = 0; i < header.size(); i++)
            if (matches(header.get(i) == null ? "" : header.get(i).trim().toLowerCase(Locale.ROOT), keys))
                return i;
        return -1;
    }

    private static boolean matches(String name, String[] keys) {
        for (String key : keys)
            if (name.equals(key))
                return true;
        return false;
    }

    // ----------------------------------------------------------------- import

    /**
     * Imports several files at once, each read for what it holds: legends first,
     * since they may resize the rack, then colours. Every file is parsed before any
     * is applied, so one that cannot be read leaves the draft untouched.
     *
     * @return one line per file, saying what it did
     */
    public static List<Component> importFiles(AnnunciatorDraft draft, List<String> texts) throws CsvException {
        List<Sheet> sheets = new ArrayList<>();
        for (String text : texts)
            sheets.add(parse(text));
        List<Component> messages = new ArrayList<>();
        for (Sheet sheet : sheets)
            if (sheet.content() != Content.COLOURS)
                messages.add(importLabels(draft, sheet));
        for (Sheet sheet : sheets)
            if (sheet.content() != Content.LABELS)
                messages.add(importColors(draft, sheet));
        return messages;
    }

    /**
     * Legends, and the readable ids that come from them. A cell the file does not
     * reach is left alone.
     */
    public static Component importLabels(AnnunciatorDraft draft, Sheet sheet) throws CsvException {
        if (sheet.values == null)
            throw new CsvException("no_legends");
        boolean resized = false;
        if (draft.resizeToCsv && sheet.rows >= 1 && sheet.columns >= 1
                && (draft.cellsX != sheet.columns || draft.cellsY != sheet.rows)) {
            draft.cellsX = sheet.columns;
            draft.cellsY = sheet.rows;
            resized = true;
        }
        draft.ensureTiles();

        int written = 0, blanked = 0;
        for (int row = 0; row < draft.cellsY; row++) {
            for (int column = 0; column < draft.cellsX; column++) {
                String value = sheet.value(row, column);
                if (value == null)
                    continue;
                AnnunciatorDraft.TileDraft tile = draft.tile(row, column);
                if (value.trim().equals("-")) {
                    tile.blank = true;
                    tile.legend = "";
                    tile.id = "";
                    blanked++;
                    continue;
                }
                tile.blank = false;
                tile.legend = normaliseLegend(value);
                String id = sheet.idAt(row, column);
                tile.id = id != null && !id.isBlank() ? id.trim() : AnnunciatorRack.slugFor(tile.legend);
                if (!tile.legend.isEmpty())
                    written++;
            }
        }
        int renamed = makeIdsUnique(draft);

        MutableComponent message = Component.translatable(LANG + "labels", written);
        if (blanked > 0)
            message.append(Component.translatable(LANG + "blanked", blanked));
        if (resized)
            message.append(Component.translatable(LANG + "resized", draft.cellsX, draft.cellsY));
        if (renamed > 0)
            message.append(Component.translatable(LANG + "renamed", renamed));
        return message.append(coverage(draft, sheet));
    }

    /**
     * Lens colours. Cells the file does not name keep the colour they had, and the
     * rack is never resized: a colour grid out of shape is worth being told about.
     */
    public static Component importColors(AnnunciatorDraft draft, Sheet sheet) {
        draft.ensureTiles();
        int written = 0;
        Set<String> unknown = new HashSet<>();
        List<String> unknownInOrder = new ArrayList<>();
        for (int row = 0; row < draft.cellsY; row++) {
            for (int column = 0; column < draft.cellsX; column++) {
                String value = sheet.lensAt(row, column);
                if (value == null || value.isBlank() || value.trim().equals("-"))
                    continue;
                LensColor color = LensColor.parse(value);
                if (color == null) {
                    if (unknown.add(value))
                        unknownInOrder.add(value);
                    continue;
                }
                draft.tile(row, column).color = color;
                written++;
            }
        }
        MutableComponent message = Component.translatable(LANG + "colours", written).append(coverage(draft, sheet));
        if (!unknownInOrder.isEmpty())
            message.append(Component.translatable(LANG + "unknown", String.join(", ", unknownInOrder)));
        return message;
    }

    /** Two windows answering to one id would leave a simulation writing to the first, so later ones are numbered. */
    public static int makeIdsUnique(AnnunciatorDraft draft) {
        Set<String> seen = new HashSet<>();
        int renamed = 0;
        for (AnnunciatorDraft.TileDraft tile : draft.tiles()) {
            if (tile.blank || tile.id.isEmpty() || seen.add(tile.id))
                continue;
            int suffix = 2;
            String candidate;
            do {
                candidate = tile.id + "_" + suffix++;
            } while (!seen.add(candidate));
            tile.id = candidate;
            renamed++;
        }
        return renamed;
    }

    /** "\n" and "|" are how a line break is written in a field that is not quoted. */
    public static String normaliseLegend(String value) {
        if (value == null || value.isEmpty())
            return "";
        return value.replace("\\n", "\n").replace("|", "\n").replace("\r\n", "\n").strip();
    }

    private static Component coverage(AnnunciatorDraft draft, Sheet sheet) {
        if (sheet.rows == draft.cellsY && sheet.columns == draft.cellsX)
            return Component.empty();
        return Component.translatable(LANG + "coverage", sheet.columns, sheet.rows, draft.cellsX, draft.cellsY);
    }

    // ----------------------------------------------------------------- export

    /**
     * The whole rack as one list-form file, which both importers read back: legends
     * with "|" for a line break, "-" for a blanked cell, every colour and id.
     */
    public static String export(AnnunciatorRack rack) {
        StringBuilder out = new StringBuilder("row,column,text,colour,id\n");
        for (int row = 0; row < rack.cellsY; row++) {
            for (int column = 0; column < rack.cellsX; column++) {
                AnnunciatorRack.Tile tile = rack.tile(row, column);
                String text = tile.blank() ? "-" : tile.legend().replace("\n", "|");
                out.append(row + 1).append(',').append(column + 1).append(',').append(quote(text)).append(',')
                        .append(tile.color().wireName()).append(',').append(quote(tile.id())).append('\n');
            }
        }
        return out.toString();
    }

    private static String quote(String field) {
        if (field.indexOf(',') < 0 && field.indexOf('"') < 0 && field.indexOf('\n') < 0
                && field.indexOf(';') < 0 && field.indexOf('\t') < 0)
            return field;
        return '"' + field.replace("\"", "\"\"") + '"';
    }
}
