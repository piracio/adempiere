/******************************************************************************
 * Product: Adempiere ERP & CRM Smart Business Solution                       *
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
 * Copyright (C) 2003-2014 e-Evolution,SC. All Rights Reserved.               *
 * Contributor(s): Victor Perez www.e-evolution.com                           *
 *****************************************************************************/

package org.adempiere.webui.component;

import org.adempiere.webui.apps.AEnv;
import org.compiere.model.Obscure;
import org.zkoss.zk.ui.WrongValueException;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zul.Constraint;
import org.zkoss.zul.Div;

/**
 * @author e-Evolution , victor.perez@e-evolution.com
 *    <li>Implement embedded or horizontal tab panel https://adempiere.atlassian.net/browse/ADEMPIERE-319
 *    <li>New ADempiere 3.8.0 ZK Theme Light  https://adempiere.atlassian.net/browse/ADEMPIERE-320
 */

public class StringBox extends Div {
    private static final long serialVersionUID = 7089099079981906933L;

    private Textbox textbox;
    private Obscure m_obscure = null;

    public StringBox() {
        super();
        init();
    }

    private void init() {
        textbox = new Textbox();
        String style = AEnv.isFirefox2() ? "display: inline" : "display: inline-block";
        style += ";white-space:nowrap";
        this.setStyle(style);
        this.appendChild(textbox);
    }

    public void setValue(Object value) {
        if (value == null)
            textbox.setValue((String) null);
        else
            textbox.setValue(value.toString());
    }

    public String getValue() {
        return textbox.getValue();
    }

    public boolean isReadonly() {
        return textbox.isReadonly();
    }

    public void setReadonly(boolean readonly) {
        textbox.setReadonly(readonly);
    }

    public boolean isEnabled() {
        return !textbox.isReadonly(); // corregido
    }

    public void setDisabled(boolean disabled) {
        textbox.setDisabled(disabled);
    }

    public boolean isDisabled() {
        return textbox.isDisabled();
    }

    public void focus() {
        textbox.focus();
    }

    public void addFocusListener(EventListener<?> listener) {
        textbox.addEventListener(Events.ON_FOCUS, listener);
        textbox.addEventListener(Events.ON_BLUR, listener);
    }

    @Override
    public void setWidth(String width) {
        super.setWidth(width);
        textbox.setWidth(width);
    }

    @Override
    public void setHeight(String height) {
        super.setHeight(height);
        textbox.setHeight("95%");
    }

    public Textbox getTextBox() {
        return textbox;
    }

    public void setObscureType(String obscureType) {
        if (obscureType != null && !obscureType.isEmpty()) {
            m_obscure = new Obscure("", obscureType);
        } else {
            m_obscure = null;
        }
        setValue(getValue());
    }

    // Métodos delegados de Textbox

    public void setText(String value) throws WrongValueException {
        textbox.setText(value);
    }

    public String getText() throws WrongValueException {
        return textbox.getText();
    }

    public void setName(String name) {
        textbox.setName(name);
    }

    public String getName() {
        return textbox.getName();
    }

    public int getMaxlength() {
        return textbox.getMaxlength();
    }

    public void setMaxlength(int maxlength) {
        textbox.setMaxlength(maxlength);
    }

    public int getCols() {
        return textbox.getCols();
    }

    public void setCols(int cols) throws WrongValueException {
        textbox.setCols(cols);
    }

    public void setTabindex(int tabindex) throws WrongValueException {
        textbox.setTabindex(tabindex);
    }

    public int getTabindex() {
        return textbox.getTabindex();
    }

    public boolean isMultiline() {
        return textbox.isMultiline();
    }

    public void setMultiline(boolean multiline) {
        textbox.setMultiline(multiline);
    }

    public int getRows() {
        return textbox.getRows();
    }

    public void setRows(int rows) throws WrongValueException {
        textbox.setRows(rows);
    }

    public String getType() {
        return textbox.getType();
    }

    public void setType(String type) throws WrongValueException {
        textbox.setType(type);
    }

    public void setConstraint(String constr) {
        textbox.setConstraint(constr);
    }

    public void setConstraint(Constraint constraint) {
        textbox.setConstraint(constraint);
    }

    public Constraint getConstraint() {
        return textbox.getConstraint();
    }

    public void select() {
        textbox.select();
    }

    public void setSelectionRange(int start, int end) {
        textbox.setSelectionRange(start, end);
    }

    public void setSelectedText(int start, int end, String newtxt, boolean isHighLight) {
        textbox.setSelectedText(start, end, newtxt, isHighLight);
    }

    public boolean isValid() {
        return textbox.isValid();
    }

    public void clearErrorMessage() {
        textbox.clearErrorMessage();
    }

    public void clearErrorMessage(boolean revalidateRequired) {
        textbox.clearErrorMessage(revalidateRequired);
    }

    public String getErrorMessage() {
        return textbox.getErrorMessage();
    }

    public Object getRawValue() {
        return textbox.getRawValue();
    }

    public void setRawValue(Object value) {
        textbox.setRawValue(value);
    }

    public String getRawText() {
        return textbox.getRawText();
    }

    public String getAreaText() {
        return textbox.getText();
    }
}

