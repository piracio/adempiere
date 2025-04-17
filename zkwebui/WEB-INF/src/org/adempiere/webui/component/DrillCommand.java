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

import org.adempiere.webui.event.DrillEvent;
import org.compiere.model.MQuery;
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
public class DrillCommand implements AuService {

    @Override
    public boolean service(AuRequest request, boolean everError) {
        String cmd = request.getCommand();

        if (!("onDrillAcross".equals(cmd) || "onDrillDown".equals(cmd))) {
            return false;
        }

        Component comp = request.getComponent();
        final Map<?, ?> data = request.getData();
        if (comp == null)
			throw new UiException(MZk.ILLEGAL_REQUEST_COMPONENT_REQUIRED, this);

        if (comp == null || data == null || !data.containsKey("column") || !data.containsKey("value")) {
            throw new UiException("Invalid AU request data for command: " + cmd);
        }

        String columnName = (String) data.get("column");
        Object value = data.get("value");

        String tableName = MQuery.getZoomTableName(columnName);

        MQuery query = new MQuery(tableName);
        query.addRestriction(columnName, MQuery.EQUAL, value);

        Events.postEvent(new DrillEvent(cmd, comp, query));
        return true;
    }
}
