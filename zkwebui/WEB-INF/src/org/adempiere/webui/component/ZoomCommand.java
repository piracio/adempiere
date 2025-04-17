/******************************************************************************
 * Copyright (C) 2009 Low Heng Sin                                            *
 * Copyright (C) 2009 Idalica Corporation                                     *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 * You should have received a copy of the GNU General Public License along    *
 * with this program; if not, write to the Free Software Foundation, Inc.,    *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.                     *
 *****************************************************************************/
package org.adempiere.webui.component;

import java.util.Map;

import org.adempiere.webui.event.ZoomEvent;
import org.compiere.model.MQuery;
import org.zkoss.lang.Objects;
import org.zkoss.zk.au.AuRequest;
import org.zkoss.zk.au.AuService;
import org.zkoss.zk.mesg.MZk;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.UiException;
import org.zkoss.zk.ui.event.Events;

/**
 * 
 * @author hengsin
 *
 */
public class ZoomCommand implements AuService  {

	private static final String CMD_ZOOM = "onZoomCommand";


	@Override
	public boolean service(AuRequest request, boolean everError)  {
		if (!CMD_ZOOM.equals(request.getCommand()))
			return false;
		final Component comp = request.getComponent();
		Map<?, ?> data = request.getData();
		
		if (comp == null)
			throw new UiException(MZk.ILLEGAL_REQUEST_COMPONENT_REQUIRED, this);
		
		if (data == null || !data.containsKey("column") || !data.containsKey("value"))
			throw new UiException(MZk.ILLEGAL_REQUEST_WRONG_DATA, new Object[] {
					Objects.toString(data), this });
		
		String columnName = (String) data.get("column");
		Object value = data.get("value");
		String tableName = MQuery.getZoomTableName(columnName);
		Object code = null; 
		
		if (columnName.endsWith("_ID"))
		{
			try {
				code = Integer.parseInt(value.toString());
			} catch (Exception e) {
				code = value;
			}
		}
		else
		{
			code = value;
		}
		//
		MQuery query = new MQuery(tableName);
		query.addRestriction(columnName, MQuery.EQUAL, code);
		query.setRecordCount(1);
		query.setZoomTableName(tableName);
		query.setZoomColumnName(columnName);
		query.setZoomValue(code);

		Events.postEvent(new ZoomEvent(comp, query));
		return true;
	}

}
