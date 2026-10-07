package com.raaspal.robotrecommendation.casereport.adapters.googlesheet;

import com.raaspal.robotrecommendation.casereport.adapters.googlesheet.dto.SheetRow;
import com.raaspal.robotrecommendation.casereport.adapters.googlesheet.dto.SheetTable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a tab and shapes it into a {@link SheetTable}: the header row names the columns,
 * and every non-empty row below it becomes a {@link SheetRow}.
 */
@Component
@RequiredArgsConstructor
public class GoogleSheetReader {

    private final GoogleSheetApiClient client;

    /**
     * @param headerRow the header's row number as the sheet shows it, 1 for the top row
     */
    public SheetTable readTable(String spreadsheetId, String tab, int headerRow) {
        return parse(client.readTab(spreadsheetId, tab), headerRow);
    }

    /**
     * The background colour of one column's cells under the header, by row number.
     *
     * @param header a header of {@code table}, as {@link SheetTable#header} returned it: its
     *               place in the header list is its column, hidden columns included
     */
    public Map<Integer, String> readColours(String spreadsheetId, String tab, int headerRow,
                                            SheetTable table, String header) {
        int column = table.headers().indexOf(header);
        if (column < 0) {
            throw new IllegalArgumentException("No column named '" + header + "' in the table");
        }
        return client.readColumnColours(spreadsheetId, tab, columnLetter(column), headerRow + 1);
    }

    /**
     * The shaping alone, without the API call.
     *
     * <p>A column with a blank header is kept, under its letter ({@code Column B}), rather
     * than dropped: hidden columns are exactly the ones nobody labels, and a value that is
     * never stored cannot be looked at later. A repeated header gets a suffix
     * ({@code Pic (2)}) so neither column overwrites the other.
     */
    public static SheetTable parse(List<List<Object>> values, int headerRow) {
        if (headerRow < 1) {
            throw new IllegalArgumentException("headerRow is 1-based; got " + headerRow);
        }
        if (values == null || values.size() < headerRow) {
            return new SheetTable(List.of(), List.of());
        }

        int width = values.stream().mapToInt(List::size).max().orElse(0);
        List<Object> headerCells = values.get(headerRow - 1);

        List<String> headers = new ArrayList<>(width);
        Map<String, Integer> seen = new HashMap<>();
        for (int col = 0; col < width; col++) {
            String header = col < headerCells.size() ? SheetCells.text(headerCells.get(col)) : null;
            if (header == null) {
                header = "Column " + columnLetter(col);
            }
            header = header.replaceAll("\\s+", " ");
            int count = seen.merge(SheetTable.normalise(header), 1, Integer::sum);
            headers.add(count == 1 ? header : header + " (" + count + ")");
        }

        List<SheetRow> rows = new ArrayList<>();
        for (int r = headerRow; r < values.size(); r++) {
            List<Object> row = values.get(r);
            Map<String, Object> cells = new LinkedHashMap<>();
            for (int col = 0; col < row.size(); col++) {
                Object value = row.get(col);
                if (value == null || (value instanceof String s && s.isBlank())) continue;
                cells.put(headers.get(col), value);
            }
            if (!cells.isEmpty()) {
                rows.add(new SheetRow(r + 1, cells));
            }
        }

        return new SheetTable(List.copyOf(headers), List.copyOf(rows));
    }

    /** 0 → A, 25 → Z, 26 → AA. */
    static String columnLetter(int index) {
        StringBuilder letters = new StringBuilder();
        for (int n = index + 1; n > 0; n = (n - 1) / 26) {
            letters.insert(0, (char) ('A' + (n - 1) % 26));
        }
        return letters.toString();
    }
}
