/*
 * Copyright 2026 Mike Mills.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.skillroy.testjava.samples.lending;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The item catalogue and deposit lookup. Read once at start-up by {@link #load}; immutable afterwards and safe to
 * call from any thread.
 */
public final class Catalogue {

    private final List<Item> items;
    private final Map<String, Item> byCode;
    private final Map<String, Item> byBarcode;

    /** @param items the items; item codes and barcodes must be unique */
    public Catalogue(List<Item> items) {
        List<Item> list = new ArrayList<Item>(items);
        Collections.sort(list, (a, b) -> a.code().compareTo(b.code()));
        this.items = Collections.unmodifiableList(list);
        this.byCode = new HashMap<String, Item>();
        this.byBarcode = new HashMap<String, Item>();
        for (Item item : list) {
            byCode.put(item.code().toUpperCase(Locale.ROOT), item);
            if (item.barcode() != null) {
                byBarcode.put(item.barcode(), item);
            }
        }
    }

    /**
     * Reads every item, active or not.
     *
     * @param connection any connection to the lending database; not closed
     */
    public static Catalogue load(Connection connection) throws SQLException {
        List<Item> list = new ArrayList<Item>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT code, barcode, title, deposit, active, induction_required,"
                 + " per_day FROM item ORDER BY code")) {
            while (rs.next()) {
                list.add(new Item(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
                    rs.getBoolean(5), rs.getBoolean(6), rs.getBoolean(7)));
            }
        }
        return new Catalogue(list);
    }

    /** @return every item, active or not, sorted by item code */
    public List<Item> items() {
        return items;
    }

    /** @return the item with this barcode (exact match), or null */
    public Item findByBarcode(String barcode) {
        return barcode == null ? null : byBarcode.get(barcode.trim());
    }

    /** @return the item with this item code (case-insensitive), or null */
    public Item findByCode(String code) {
        return code == null ? null : byCode.get(code.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * What the desk does with a code typed into the entry field: barcode first, then item code.
     *
     * @return the item, or null
     */
    public Item lookup(String code) {
        Item item = findByBarcode(code);
        return item != null ? item : findByCode(code);
    }

    /** @return the unit deposit of the item code (scale 2), or null if unknown */
    public BigDecimal depositOf(String code) {
        Item item = findByCode(code);
        return item == null ? null : item.unitDeposit();
    }
}
