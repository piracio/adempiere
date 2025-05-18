/******************************************************************************
 * Product: Posterita Ajax UI 												  *
 * Copyright (C) 2007 Posterita Ltd.  All Rights Reserved.                    *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 * You should have received a copy of the GNU General Public License along    *
 * with this program; if not, write to the Free Software Foundation, Inc.,    *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.                     *
 * For the text or an alternative of this public license, you may reach us    *
 * Posterita Ltd., 3, Draper Avenue, Quatre Bornes, Mauritius                 *
 * or via info@posterita.org or http://www.posterita.org/                     *
 *****************************************************************************/

package org.adempiere.webui.component;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.text.ParseException;

import org.adempiere.webui.LayoutUtils;
import org.adempiere.webui.apps.AEnv;
import org.compiere.util.DisplayType;
import org.compiere.util.Env;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zul.Button;
import org.zkoss.zul.Decimalbox;
import org.zkoss.zul.Div;
import org.zkoss.zul.Hbox;
import org.zkoss.zul.Popup;
import org.zkoss.zul.Textbox;
import org.zkoss.zul.Vbox;

/**
 * Refactored NumberBox for ZK 6.5+
 *
 * Provides a numeric input with an optional calculator popup.
 */
public class NumberBox extends Div {
    private static final long serialVersionUID = 7089099079981906933L;

    private Textbox txtCalc;
    private boolean integral = false;
    private NumberFormat format = null;
    private Decimalbox decimalBox = null;
    private Button btn;
    private Object m_oldValue = null;
    private boolean btnEnabled = true;
    private Popup popup;

    public NumberBox(boolean integral) {
        super();
        this.integral = integral;
        init();
    }

    public Popup getPopupMenu() {
        return popup;
    }

    private void init() {
        Hbox hbox = new Hbox();
        hbox.setSpacing("2px");
        appendChild(hbox);

        decimalBox = new Decimalbox();
        if (integral)
            decimalBox.setScale(0);
        decimalBox.setStyle("text-align: right; padding-right: 2px;");
        hbox.appendChild(decimalBox);

        btn = new Button();
        btn.setImage("/images/Calculator10.png");
        btn.setTabindex(-1);
        LayoutUtils.addSclass("editor-button", btn);
        hbox.appendChild(btn);

        popup = createCalculatorPopup();
        btn.setPopup(popup);
        btn.setStyle("text-align: center;");
        appendChild(popup);

        String style = AEnv.isFirefox2() ? "display: inline" : "display: inline-block";
        style += ";white-space:nowrap";
        setStyle(style);
    }

    public void setFormat(NumberFormat format) {
        this.format = format;
    }

    public void setValue(Object value) {
        if (value == null) {
            decimalBox.setValue((BigDecimal) null);
        } else if (value instanceof BigDecimal) {
            decimalBox.setValue((BigDecimal) value);
        } else if (value instanceof Number) {
            decimalBox.setValue(BigDecimal.valueOf(((Number) value).doubleValue()));
        } else {
            decimalBox.setValue(new BigDecimal(value.toString()));
        }
    }

    public BigDecimal getValue() {
        return decimalBox.getValue();
    }

    public String getText() {
        BigDecimal value = decimalBox.getValue();
        if (value == null)
            return null;
        if (format != null)
            return format.format(value);
        else
            return value.toPlainString();
    }

    public void setValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            decimalBox.setValue((BigDecimal) null);
            return;
        }
        try {
            if (format != null) {
                Number numberValue = format.parse(value);
                setValue(numberValue);
            } else {
                decimalBox.setValue(new BigDecimal(value));
            }
        } catch (ParseException e) {
            // ignore parse errors or handle as needed
        }
    }

    private Popup createCalculatorPopup() {
        Popup popup = new Popup();
        Vbox vbox = new Vbox();
        popup.appendChild(vbox);

        // Decimal separator for the current locale
        char separatorChar = DisplayType.getNumberFormat(DisplayType.Number, Env.getLanguage(Env.getCtx()))
                .getDecimalFormatSymbols().getDecimalSeparator();
        String separator = Character.toString(separatorChar);

        txtCalc = new Textbox();
        txtCalc.setMaxlength(250);
        txtCalc.setCols(30);
        vbox.appendChild(txtCalc);

        // Buttons row 1
        Hbox row1 = new Hbox();
        addButton(row1, "AC", e -> calcClearAll());
        addButton(row1, "7", e -> calcAppend("7"));
        addButton(row1, "8", e -> calcAppend("8"));
        addButton(row1, "9", e -> calcAppend("9"));
        addButton(row1, "*", e -> calcAppend(" * "));
        vbox.appendChild(row1);

        // Buttons row 2
        Hbox row2 = new Hbox();
        addButton(row2, "C", e -> calcClear());
        addButton(row2, "4", e -> calcAppend("4"));
        addButton(row2, "5", e -> calcAppend("5"));
        addButton(row2, "6", e -> calcAppend("6"));
        addButton(row2, "/", e -> calcAppend(" / "));
        vbox.appendChild(row2);

        // Buttons row 3
        Hbox row3 = new Hbox();
        addButton(row3, "%", e -> calcPercentage());
        addButton(row3, "1", e -> calcAppend("1"));
        addButton(row3, "2", e -> calcAppend("2"));
        addButton(row3, "3", e -> calcAppend("3"));
        addButton(row3, "-", e -> calcAppend(" - "));
        vbox.appendChild(row3);

        // Buttons row 4
        Hbox row4 = new Hbox();
        Button btnCurrency = new Button("$");
        btnCurrency.setWidth("40px");
        btnCurrency.setDisabled(true);
        row4.appendChild(btnCurrency);

        Button btnDot = new Button(separator);
        btnDot.setWidth("30px");
        btnDot.setDisabled(integral);
        btnDot.addEventListener(Events.ON_CLICK, e -> calcAppend(separator));
        row4.appendChild(btnDot);

        addButton(row4, "0", e -> calcAppend("0"));
        addButton(row4, "=", e -> calcEvaluate());
        addButton(row4, "+", e -> calcAppend(" + "));
        vbox.appendChild(row4);

        return popup;
    }

    private void addButton(Hbox container, String label, EventListener<Event> listener) {
        Button btn = new Button(label);
        btn.setWidth(label.equals("AC") || label.equals("C") || label.equals("%") ? "40px" : "30px");
        btn.addEventListener(Events.ON_CLICK, listener);
        container.appendChild(btn);
    }

    // Calculator logic

    private void calcClearAll() {
        txtCalc.setValue("");
    }

    private void calcClear() {
        String val = txtCalc.getValue();
        if (val != null && val.length() > 0) {
            txtCalc.setValue(val.substring(0, val.length() - 1));
        }
    }

    private void calcAppend(String s) {
        String val = txtCalc.getValue();
        txtCalc.setValue((val != null ? val : "") + s);
    }

    private void calcPercentage() {
        // Calculate percentage based on current decimalBox value and input
        try {
            BigDecimal base = decimalBox.getValue();
            if (base == null) base = BigDecimal.ZERO;

            String calcVal = txtCalc.getValue();
            if (calcVal == null || calcVal.trim().isEmpty()) return;

            BigDecimal percentage = new BigDecimal(calcVal.trim());
            BigDecimal result = base.multiply(percentage).divide(BigDecimal.valueOf(100));
            decimalBox.setValue(result);
            popup.close();
        } catch (Exception e) {
            // Handle parsing errors silently or log
        }
    }

    private void calcEvaluate() {
        try {
            String expr = txtCalc.getValue();
            if (expr == null || expr.trim().isEmpty())
                return;

            // Use built-in ScriptEngine for evaluation (JS engine)
            javax.script.ScriptEngine engine = new javax.script.ScriptEngineManager().getEngineByName("JavaScript");
            Object evalResult = engine.eval(expr);

            BigDecimal bdResult = null;
            if (evalResult instanceof Number) {
                bdResult = new BigDecimal(((Number) evalResult).doubleValue());
            } else {
                bdResult = new BigDecimal(evalResult.toString());
            }

            if (integral) {
                bdResult = bdResult.setScale(0, BigDecimal.ROUND_HALF_UP);
            }

            decimalBox.setValue(bdResult);
            popup.close();
        } catch (Exception e) {
            // Optionally notify user of invalid expression
        }
    }

    public boolean isIntegral() {
        return integral;
    }

    public void setIntegral(boolean integral) {
        this.integral = integral;
        if (integral)
            decimalBox.setScale(0);
        else
            decimalBox.setScale(Decimalbox.AUTO);
    }

    public void setEnabled(boolean enabled) {
        decimalBox.setReadonly(!enabled);

        boolean isCalculatorEnabled = btnEnabled && enabled;
        btn.setDisabled(!isCalculatorEnabled);
        if (isCalculatorEnabled)
            btn.setPopup(popup);
        else
            btn.setPopup((Popup) null);
    }

    public boolean isEnabled() {
        return !decimalBox.isReadonly();
    }

    public boolean isReadonly() {
        return decimalBox.isReadonly();
    }

    @Override
    public boolean addEventListener(String evtnm, EventListener<?> listener) {
        if (Events.ON_CLICK.equals(evtnm)) {
            return btn.addEventListener(evtnm, listener);
        } else {
            return decimalBox.addEventListener(evtnm, listener);
        }
    }

    @Override
    public void focus() {
        decimalBox.focus();
    }

    public Decimalbox getDecimalbox() {
        return decimalBox;
    }

    public void setCalculatorEnabled(boolean enabled) {
        btnEnabled = enabled;
        btn.setDisabled(!btnEnabled);
        btn.setVisible(btnEnabled);
    }

    public boolean isCalculatorEnabled() {
        return btnEnabled;
    }

    public void set_oldValue() {
        this.m_oldValue = getValue();
    }

    public Object get_oldValue() {
        return m_oldValue;
    }

    public boolean hasChanged() {
        if (getValue() != null)
            return m_oldValue == null || !m_oldValue.equals(getValue());
        else
            return m_oldValue != null;
    }
}
