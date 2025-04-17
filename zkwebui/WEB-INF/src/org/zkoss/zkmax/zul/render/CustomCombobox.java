/* Combobox2Default.java

{{IS_NOTE
	Purpose:

	Description:

	History:
		Jun 6, 2008 8:57:53 AM , Created by jumperchen
}}IS_NOTE

Copyright (C) 2008 Potix Corporation. All Rights Reserved.

{{IS_RIGHT
	This program is distributed under GPL Version 2.0 in the hope that
	it will be useful, but WITHOUT ANY WARRANTY.
}}IS_RIGHT
*/
package org.zkoss.zkmax.zul.render;

import java.io.IOException;
import java.io.Writer;

import org.zkoss.zk.ui.sys.ComponentCtrl;
import org.zkoss.zul.Combobox;

/**
 * {@link Combobox}'s default mold.
 *
 * @author jumperchen
 *
 * @since 3.5.0
 *
 * @author hengsin
 * modify default zk layout for combobox
 */
public class CustomCombobox extends Combobox  {
	private static final long serialVersionUID = 1L;

	@Override
    public void redraw(Writer out) throws IOException {
        final String uuid = getUuid();
        final String zcls = getZclass() != null ? getZclass() : "z-combobox";
        final String width = getWidth();
        final boolean buttonVisible = isButtonVisible();
        
        // manualmente los atributos externos del <span>
        String outerAttrs = "class=\"" + zcls + "\"";
        if (getStyle() != null && !getStyle().isEmpty()) {
            outerAttrs += " style=\"" + getStyle() + "\"";
        }
        
        // Atributos del <input>
        String inputAttrs = "style='width:100%'";

        // estruc HTML
        out.write("<span id=\"" + uuid + "\" " + outerAttrs
                + " z.type=\"zul.cb.Cmbox\" z.combo=\"true\">");

        out.write("<table border='0' cellspacing='0' cellpadding='0' "
                + (width != null ? "width='" + width + "'" : "")
                + " style='display:inline-block'>");

        out.write("<tr style='white-space:nowrap; border:none'>");
        
        // Campo de entrada (input)
        out.write("<td style='width:100%; border:none'>");
        out.write("<input id=\"" + uuid + "_real\" autocomplete=\"off\" class=\"" + zcls + "-inp\" "
                + inputAttrs + "/>");
        out.write("</td>");
        
       // Botón desplegable
        out.write("<td style='width:17px'>");
        out.write("<span id=\"" + uuid + "_btn\" class=\"" + zcls + "-btn\""
                + (buttonVisible ? " style='margin-left:2px'" : " style='display:none'") + ">");
        out.write("<div class=\"" + zcls + "-img\" style=\"width:16px;height:16px;\" onmousedown=\"return false;\"></div>");
        out.write("</span></td>");

        out.write("</tr></table>");
        
        // Panel emergente con las opciones
        out.write("<div id=\"" + uuid + "_pp\" class=\"" + zcls + "-pp\" style=\"display:none\" tabindex=\"-1\">");
        out.write("<table id=\"" + uuid + "_cave\" cellpadding=\"0\" cellspacing=\"0\">");

        // Redibuja hijos
        for (Object child : getChildren()) {
            ((ComponentCtrl) child).redraw(out);
        }

        out.write("</table></div>");
        out.write("</span>");
    }
}
