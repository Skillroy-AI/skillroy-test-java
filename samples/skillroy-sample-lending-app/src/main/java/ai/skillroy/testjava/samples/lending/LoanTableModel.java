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

import javax.swing.table.AbstractTableModel;

/**
 * Table model of the open loan's lines, backed by an immutable {@link Loan} snapshot. Cells are display strings.
 * Event dispatch thread only.
 */
public final class LoanTableModel extends AbstractTableModel {

    /** Column headers. */
    public static final String[] COLUMNS = {"#", "Code", "Title", "Qty", "Deposit", "Amount"};

    private static final long serialVersionUID = 1L;

    private transient Loan loan;

    /** Shows another loan (null for none) and notifies the table. */
    public void setLoan(Loan value) {
        this.loan = value;
        fireTableDataChanged();
    }

    /** @return the loan shown, or null */
    public Loan loan() {
        return loan;
    }

    @Override
    public int getRowCount() {
        return loan == null ? 0 : loan.lines().size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Object getValueAt(int row, int column) {
        LoanLine line = loan.lines().get(row);
        switch (column) {
            case 0:
                return String.valueOf(line.lineNo());
            case 1:
                return line.code();
            case 2:
                return line.title();
            case 3:
                return Deposits.formatLineQty(line.qty(), line.perDay());
            case 4:
                return Deposits.format(line.unitDeposit());
            default:
                return Deposits.format(line.amount());
        }
    }
}
