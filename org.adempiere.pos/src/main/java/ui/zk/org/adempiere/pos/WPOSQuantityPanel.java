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
 * Copyright (C) 2003-2014 E.R.P. Consultores y Asociados, C.A.               *
 * All Rights Reserved.                                                       *
 *
 * Refactor by Horacio Miranda, PROLINUX CHILE, hmiranda@prolinux.cl          *
 * ****************************************************************************/

package org.adempiere.pos;

import java.math.BigDecimal;

import org.adempiere.webui.component.Label;
import org.adempiere.webui.component.NumberBox;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zul.Button;
import org.zkoss.zul.Grid;
import org.zkoss.zul.Row;
import org.zkoss.zul.Rows;
import org.zkoss.zul.Window;

public class WPOSQuantityPanel extends Window implements EventListener<Event> {

	private static final long serialVersionUID = 1L;

	private Label lblQuantity;
	private NumberBox fldQuantity;
	private Button btnOk;
	private Button btnCancel;

	public WPOSQuantityPanel() {
		super();
		init();
	}

	private void init() {
		this.setTitle("Enter Quantity");
		this.setBorder("normal");
		this.setWidth("300px");
		this.setHeight("150px");
		this.setClosable(true);

		// 🔁 Register Ctrl+Q key shortcut to trigger cancel
		this.setCtrlKeys("^q");
		this.addEventListener(Events.ON_CTRL_KEY, e -> {
			onCancel();
		});

		Grid grid = new Grid();
		Rows rows = new Rows();

		// Quantity input row
		Row quantityRow = new Row();
		lblQuantity = new Label("Quantity:");
		fldQuantity = new NumberBox(false);
		fldQuantity.setValue(BigDecimal.ONE);
		quantityRow.appendChild(lblQuantity);
		quantityRow.appendChild(fldQuantity);
		rows.appendChild(quantityRow);

		// Buttons row
		Row btnRow = new Row();
		btnOk = new Button("OK");
		btnCancel = new Button("Cancel");

		btnOk.addEventListener(Events.ON_CLICK, this);
		btnCancel.addEventListener(Events.ON_CLICK, this);

		btnRow.appendChild(btnOk);
		btnRow.appendChild(btnCancel);
		rows.appendChild(btnRow);

		grid.appendChild(rows);
		this.appendChild(grid);
	}

	@Override
	public void onEvent(Event event) throws Exception {
		if (event.getTarget().equals(btnOk)) {
			onOK();
		} else if (event.getTarget().equals(btnCancel)) {
			onCancel();
		}
	}

	private void onOK() {
		BigDecimal qty = fldQuantity.getValue();
		// Logic to apply the entered quantity
		this.detach();
	}

	private void onCancel() {
		// Logic to cancel and close the panel
		this.detach();
	}

	public BigDecimal getQuantity() {
		return fldQuantity.getValue();
	}

	public void resetPanel() {
		fldQuantity.setValue(BigDecimal.ONE);
	}

	public void changeViewPanel() {
		// Implement view change logic here if needed
		this.setVisible(true);
	}

	public void requestFocus() {
		fldQuantity.setFocus(true); // Focus the number field
	}

	public void setQuantity(BigDecimal qty) {
		fldQuantity.setValue(qty);
	}

}