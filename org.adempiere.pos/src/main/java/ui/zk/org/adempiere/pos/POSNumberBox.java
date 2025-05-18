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
 * or via info@posterita.org or http://www.posterita.org/
 * Refactor by Horacio Miranda, Prolinux Chile, hmiranda@prolinux.cl          *
 *
 *****************************************************************************/

package org.adempiere.pos;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.text.ParseException;

import org.adempiere.webui.LayoutUtils;
import org.adempiere.webui.apps.AEnv;
import org.compiere.util.DisplayType;
import org.compiere.util.Env;
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
 * POSNumberBox for ZK 6.5 refactored version
 *
 * @author Ashley G Ramdass, refactored by ChatGPT
 */
public class POSNumberBox extends Div {

    private static final long serialVersionUID = 7089099079981906933L;

    private final String FONT_SIZE = "font-size: medium;";
    private final String HEIGHT = "height: 30px;";
    private final String WIDTH = "width: auto;";

    private boolean integral;

    private NumberFormat format;

    private Decimalbox decimalBox;

    private Button btnCalc;

    private Popup popup;

    private Textbox txtCalc;

    private Object oldValue;

    private boolean btnEnabled = true;

    public POSNumberBox(boolean integral) {
        this.integral = integral;
        init();
    }

    private void init() {
        this.setStyle((AEnv.isFirefox2() ? "display:inline;" : "display:inline-block;") + "white-space:nowrap;");

        // Main decimal box setup
        decimalBox = new Decimalbox();
        decimalBox.setReadonly(false);
        if (integral) {
            decimalBox.setScale(0);
            decimalBox.setStyle("width:85px;" + HEIGHT + FONT_SIZE);
        } else {
            decimalBox.setStyle("width:80px; text-align:right;" + HEIGHT + FONT_SIZE);
            decimalBox.setScale(Decimalbox.AUTO);
        }
        this.appendChild(decimalBox);

        // Calculator button setup
        btnCalc = new Button();
        btnCalc.setImage("/images/Calculator10.png");
        btnCalc.setTabindex(-1);
        LayoutUtils.addSclass("editor-button", btnCalc);
        btnCalc.setStyle("text-align:center; height:35px;");
        this.appendChild(btnCalc);

        // Calculator popup
        popup = createCalculatorPopup();
        btnCalc.setPopup(popup);
        this.appendChild(popup);
    }

    private Popup createCalculatorPopup() {
        Popup popup = new Popup();

        Vbox vbox = new Vbox();

        char separatorChar = DisplayType
                .getNumberFormat(DisplayType.Number, Env.getLanguage(Env.getCtx()))
                .getDecimalFormatSymbols()
                .getDecimalSeparator();

        String separator = Character.toString(separatorChar);

        txtCalc = new Textbox();
        txtCalc.setMaxlength(250);
        txtCalc.setCols(30);
        txtCalc.setWidth("98%");
        txtCalc.setHeight("30px");
        txtCalc.setStyle(FONT_SIZE);

        vbox.appendChild(txtCalc);

        Hbox row1 = new Hbox();
        Hbox row2 = new Hbox();
        Hbox row3 = new Hbox();
        Hbox row4 = new Hbox();

        // Utility to create buttons
        Button btnAC = createCalcButton("AC", 60, "calc.clearAll('" + txtCalc.getUuid() + "')");
        Button btn7 = createCalcButton("7", 60, "calc.append('" + txtCalc.getUuid() + "', '7')");
        Button btn8 = createCalcButton("8", 60, "calc.append('" + txtCalc.getUuid() + "', '8')");
        Button btn9 = createCalcButton("9", 60, "calc.append('" + txtCalc.getUuid() + "', '9')");
        Button btnMultiply = createCalcButton("*", 60, "calc.append('" + txtCalc.getUuid() + "', ' * ')");

        row1.appendChild(btnAC);
        row1.appendChild(btn7);
        row1.appendChild(btn8);
        row1.appendChild(btn9);
        row1.appendChild(btnMultiply);

        Button btnC = createCalcButton("C", 60, "calc.clear('" + txtCalc.getUuid() + "')");
        Button btn4 = createCalcButton("4", 60, "calc.append('" + txtCalc.getUuid() + "', '4')");
        Button btn5 = createCalcButton("5", 60, "calc.append('" + txtCalc.getUuid() + "', '5')");
        Button btn6 = createCalcButton("6", 60, "calc.append('" + txtCalc.getUuid() + "', '6')");
        Button btnDivide = createCalcButton("/", 60, "calc.append('" + txtCalc.getUuid() + "', ' / ')");

        row2.appendChild(btnC);
        row2.appendChild(btn4);
        row2.appendChild(btn5);
        row2.appendChild(btn6);
        row2.appendChild(btnDivide);

        Button btnModulo = createCalcButton("%", 60, "calc.percentage('" + decimalBox.getUuid() + "','" + txtCalc.getUuid() + "','" + separator + "')");
        Button btn1 = createCalcButton("1", 60, "calc.append('" + txtCalc.getUuid() + "', '1')");
        Button btn2 = createCalcButton("2", 60, "calc.append('" + txtCalc.getUuid() + "', '2')");
        Button btn3 = createCalcButton("3", 60, "calc.append('" + txtCalc.getUuid() + "', '3')");
        Button btnSubtract = createCalcButton("-", 60, "calc.append('" + txtCalc.getUuid() + "', ' - ')");

        row3.appendChild(btnModulo);
        row3.appendChild(btn1);
        row3.appendChild(btn2);
        row3.appendChild(btn3);
        row3.appendChild(btnSubtract);

        Button btnCurrency = createCalcButton("$", 60, null);
        btnCurrency.setDisabled(true);

        Button btnDot = createCalcButton(separator, 60, "calc.append('" + txtCalc.getUuid() + "', '" + separator + "')");
        btnDot.setDisabled(integral);

        Button btn0 = createCalcButton("0", 60, "calc.append('" + txtCalc.getUuid() + "', '0')");

        Button btnEqual = createCalcButton("=", 60, "calc.evaluate('" + decimalBox.getUuid() + "','" + txtCalc.getUuid() + "','" + separator + "')");

        Button btnAdd = createCalcButton("+", 60, "calc.append('" + txtCalc.getUuid() + "', ' + ')");

        row4.appendChild(btnCurrency);
        row4.appendChild(btnDot);
        row4.appendChild(btn0);
        row4.appendChild(btnEqual);
        row4.appendChild(btnAdd);

        vbox.appendChild(row1);
        vbox.appendChild(row2);
        vbox.appendChild(row3);
        vbox.appendChild(row4);

        row1.setHeight("60px");
        row2.setHeight("60px");
        row3.setHeight("60px");
        row4.setHeight("60px");

        popup.appendChild(vbox);
        return popup;
    }

    private Button createCalcButton(String label, int size, String onClickAction) {
        Button btn = new Button(label);
        btn.setWidth(size + "px");
        btn.setHeight(size + "px");
        btn.setStyle(FONT_SIZE);
        if (onClickAction != null) {
            btn.setSclass("z-button");
            btn.setAction(onClickAction);
        }
        return btn;
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
        if (format != null) {
            try {
                Number numberValue = format.parse(value);
                setValue(numberValue);
            } catch (ParseException e) {
                decimalBox.setValue(new BigDecimal(value));
            }
        } else {
            decimalBox.setValue(new BigDecimal(value));
        }
    }

    public boolean isIntegral() {
        return integral;
    }

    public void setIntegral(boolean integral) {
        this.integral = integral;
        decimalBox.setScale(integral ? 0 : Decimalbox.AUTO);
        txtCalc.setDisabled(integral);
    }

    public void setEnabled(boolean enabled) {
        decimalBox.setReadonly(!enabled);
        boolean calcEnabled = btnEnabled && enabled;
        btnCalc.setDisabled(!calcEnabled);
        btnCalc.setPopup(calcEnabled ? popup : null);
    }

    public boolean isEnabled() {
        return !decimalBox.isReadonly();
    }

    public boolean isReadonly() {
        return decimalBox.isReadonly();
    }

    @Override
    public boolean addEventListener(String evtnm, EventListener listener) {
        if (Events.ON_CLICK.equals(evtnm)) {
            btnCalc.setFocus(true);
            return btnCalc.addEventListener(evtnm, listener);
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
        btnCalc.setDisabled(!enabled);
        btnCalc.setVisible(enabled);
    }

    public boolean isCalculatorEnabled() {
        return btnEnabled;
    }

    public void set_oldValue() {
        oldValue = getValue();
    }

    public Object get_oldValue() {
        return oldValue;
    }

    public boolean hasChanged() {
        BigDecimal currentValue = getValue();
        if (currentValue != null) {
            return oldValue == null || !oldValue.equals(currentValue);
        } else {
            return oldValue != null;
        }
    }
}
