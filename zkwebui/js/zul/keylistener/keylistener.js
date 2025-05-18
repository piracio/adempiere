// keylistener.js actualizado para ZK 6.5.7

(function () {
    zk.afterLoad('zul', function () {

        zul.KeyListener = zk.$extends(zk.Widget, {
            _ctkeys: '',
            _autoblur: true,

            bind_: function (desktop, skipper, after) {
                this.$supers('bind_', arguments);
                this._keydown = this.proxy(this._doKeyDown);
                jq(document).keydown(this._keydown);
            },

            unbind_: function () {
                jq(document).unbind('keydown', this._keydown);
                this._keydown = null;
                this.$supers('unbind_', arguments);
            },

            _doKeyDown: function (evt) {
                var keycode = evt.keyCode,
                    zkcode = this._translateKeyCode(evt);

                if (!zkcode) return;

                if (this._inCtrlKeys(evt, zkcode)) {
                    if (this._autoblur) {
                        this.$n().focus();
                    }

                    this.fire('onCtrlKey', {
                        keyCode: keycode,
                        ctrlKey: evt.ctrlKey,
                        shiftKey: evt.shiftKey,
                        altKey: evt.altKey
                    }, {toServer: true});

                    evt.stop();
                    return false;
                }
            },

            _translateKeyCode: function (evt) {
                var kc = evt.keyCode;
                switch (kc) {
                    case 13: return 'K'; // Enter
                    case 45: return 'I'; // Insert
                    case 46: return 'J'; // Delete
                    default:
                        if (kc >= 33 && kc <= 40) {
                            return String.fromCharCode('A'.charCodeAt(0) + (kc - 33));
                        } else if (kc >= 112 && kc <= 123) {
                            return String.fromCharCode('P'.charCodeAt(0) + (kc - 112));
                        } else if (evt.ctrlKey || evt.altKey) {
                            return String.fromCharCode(kc).toLowerCase();
                        }
                }
                return null;
            },

            _inCtrlKeys: function (evt, zkcode) {
                if (!this._ctkeys) return false;

                var prefix = evt.ctrlKey ? '^' : evt.altKey ? '@' : evt.shiftKey ? '$' : '#',
                    keys = this._ctkeys,
                    j = keys.indexOf(prefix),
                    k = keys.indexOf(';', j + 1);

                if (j >= 0 && k >= 0) {
                    var segment = keys.substring(j + 1, k);
                    return segment.indexOf(zkcode) >= 0;
                }
                return false;
            },

            setCtkeys: function (val) {
                this._ctkeys = val || '';
            },

            getCtkeys: function () {
                return this._ctkeys;
            },

            setAutoblur: function (val) {
                this._autoblur = val;
            },

            getAutoblur: function () {
                return this._autoblur;
            }
        });

	zul.KeyListener.molds = {
	        default: function (out) {
	        out.push('<div', this.domAttrs_(), '></div>');
	    }
        };

    });
})();
