package org.zkforge.keylistener;

import org.zkoss.lang.Objects;
import org.zkoss.zk.ui.HtmlBasedComponent;
import org.zkoss.zk.ui.UiException;
import org.zkoss.zk.ui.WrongValueException;

public class Keylistener extends HtmlBasedComponent {
    private static final long serialVersionUID = 4611014738053691844L;
    
    private String ctrlKeys;
    private String jsCtrlKeys;
    private boolean autoBlur = true;

    public Keylistener() {
    }

    public boolean isAutoBlur() {
        return this.autoBlur;
    }

    public void setAutoBlur(boolean autoBlur) {
        if (this.autoBlur != autoBlur) {
            this.autoBlur = autoBlur;
            smartUpdate("autoblur", autoBlur); // ahora puede ser autoblur como atributo estándar
        }
    }

    public String getCtrlKeys() {
        return this.ctrlKeys;
    }

    public void setCtrlKeys(String ctrlKeys) {
        if (ctrlKeys != null && ctrlKeys.length() == 0) {
            ctrlKeys = null;
        }
        if (!Objects.equals(this.ctrlKeys, ctrlKeys)) {
            this.parseCtrlKeys(ctrlKeys);
            smartUpdate("ctkeys", this.jsCtrlKeys); 
        }
    }

    private void parseCtrlKeys(String keys) throws UiException {
        if (keys != null && keys.length() != 0) {
            StringBuilder sbctl = new StringBuilder();
            StringBuilder sbsft = new StringBuilder();
            StringBuilder sbalt = new StringBuilder();
            StringBuilder sbext = new StringBuilder();
            StringBuilder sbcur = null;

            int len = keys.length();
            for (int j = 0; j < len; ++j) {
                char cc = keys.charAt(j);
                switch (cc) {
                    case '#':
                        int k = j + 1;
                        while (k < len) {
                            char c2 = keys.charAt(k);
                            if (Character.isLetterOrDigit(c2)) {
                                ++k;
                            } else {
                                break;
                            }
                        }
                        if (k == j + 1) {
                            throw new WrongValueException("Unexpected character '#' at position " + j + " in " + keys);
                        }
                        String s = keys.substring(j + 1, k).toLowerCase();
                        cc = parseSpecialKey(s);
                        if (sbcur == null) {
                            sbext.append(cc);
                        } else {
                            sbcur.append(cc);
                            sbcur = null;
                        }
                        j = k - 1;
                        break;
                    case '^':
                        if (sbcur != null) throw new WrongValueException("Combination Shift/Alt/Ctrl not supported: " + keys);
                        sbcur = sbctl;
                        break;
                    case '@':
                        if (sbcur != null) throw new WrongValueException("Combination Shift/Alt/Ctrl not supported: " + keys);
                        sbcur = sbalt;
                        break;
                    case '$':
                        if (sbcur != null) throw new WrongValueException("Combination Shift/Alt/Ctrl not supported: " + keys);
                        sbcur = sbsft;
                        break;
                    default:
                        if (sbcur == null || (!Character.isLetterOrDigit(cc))) {
                            throw new WrongValueException("Unexpected character: " + cc + " in " + keys);
                        }
                        if (sbcur == sbsft) {
                            throw new WrongValueException("$" + cc + " not supported: " + keys);
                        }
                        if (Character.isUpperCase(cc)) {
                            cc = Character.toLowerCase(cc);
                        }
                        sbcur.append(cc);
                        sbcur = null;
                }
            }
            this.jsCtrlKeys = new StringBuilder()
                    .append('^').append(sbctl).append(';')
                    .append('@').append(sbalt).append(';')
                    .append('$').append(sbsft).append(';')
                    .append('#').append(sbext).append(';')
                    .toString();
            this.ctrlKeys = keys;
        } else {
            this.ctrlKeys = this.jsCtrlKeys = null;
        }
    }

    private char parseSpecialKey(String s) {
        switch (s) {
            case "pgup": return 'A';
            case "pgdn": return 'B';
            case "end": return 'C';
            case "home": return 'D';
            case "left": return 'E';
            case "up": return 'F';
            case "right": return 'G';
            case "down": return 'H';
            case "ins": return 'I';
            case "del": return 'J';
            case "enter": return 'K';
            default:
                if (s.length() > 1 && s.charAt(0) == 'f') {
                    try {
                        int v = Integer.parseInt(s.substring(1));
                        if (v >= 1 && v <= 12) {
                            return (char) (79 + v);
                        }
                    } catch (NumberFormatException ignored) {}
                }
                throw new WrongValueException("Unknown key: #" + s);
        }
    }

    @Override
    public boolean isChildable() {
        return false;
    }
}
