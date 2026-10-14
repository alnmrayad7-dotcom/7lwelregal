(function () {
  'use strict';
  if (window.__vc) { return; }

  var isTop = (window === window.top);

  /* ------------------------------------------------------------------
   * 1) Look like a desktop PC
   * ------------------------------------------------------------------ */
  function def(obj, prop, value) {
    try {
      Object.defineProperty(obj, prop, {
        get: function () { return value; },
        configurable: true
      });
    } catch (e) {}
  }

  var major = '130';
  var mm = /Chrome\/(\d+)/.exec(navigator.userAgent);
  if (mm) { major = mm[1]; }

  var brands = [
    { brand: 'Chromium', version: major },
    { brand: 'Google Chrome', version: major },
    { brand: 'Not?A_Brand', version: '99' }
  ];

  var uad = {
    brands: brands,
    mobile: false,
    platform: 'Windows',
    getHighEntropyValues: function () {
      return Promise.resolve({
        brands: brands,
        mobile: false,
        platform: 'Windows',
        platformVersion: '10.0.0',
        architecture: 'x86',
        bitness: '64',
        model: '',
        uaFullVersion: major + '.0.0.0',
        fullVersionList: brands.map(function (b) {
          return { brand: b.brand, version: b.version + '.0.0.0' };
        })
      });
    },
    toJSON: function () {
      return { brands: brands, mobile: false, platform: 'Windows' };
    }
  };

  def(navigator, 'platform', 'Win32');
  def(navigator, 'maxTouchPoints', 0);
  def(navigator, 'vendor', 'Google Inc.');
  def(navigator, 'userAgentData', uad);

  // sites sometimes detect touch devices through these handlers
  ['ontouchstart', 'ontouchend', 'ontouchmove', 'ontouchcancel'].forEach(function (p) {
    try { delete window[p]; } catch (e) {}
    [Window.prototype, Document.prototype, HTMLElement.prototype, Element.prototype].forEach(function (o) {
      try { delete o[p]; } catch (e) {}
    });
  });

  // answer "mouse + hover" to media queries
  if (window.matchMedia) {
    var realMM = window.matchMedia.bind(window);
    window.matchMedia = function (q) {
      var mql = realMM(q);
      var s = String(q);
      var forced = null;
      if (/(pointer\s*:\s*coarse)|(hover\s*:\s*none)/i.test(s)) { forced = false; }
      else if (/(pointer\s*:\s*fine)|(hover\s*:\s*hover)/i.test(s)) { forced = true; }
      if (forced === null) { return mql; }
      return new Proxy(mql, {
        get: function (t, p) {
          if (p === 'matches') { return forced; }
          var v = t[p];
          return (typeof v === 'function') ? v.bind(t) : v;
        }
      });
    };
  }

  // force a desktop-sized layout viewport
  var VIEWPORT = 'width=1280';
  function forceViewport() {
    if (!isTop) { return; }
    try {
      var el = document.querySelector('meta[name="viewport"]');
      if (!el) {
        el = document.createElement('meta');
        el.name = 'viewport';
        (document.head || document.documentElement).appendChild(el);
      }
      if (el.content !== VIEWPORT) { el.content = VIEWPORT; }
    } catch (e) {}
  }
  document.addEventListener('DOMContentLoaded', forceViewport);
  window.addEventListener('load', forceViewport);
  setTimeout(forceViewport, 300);
  setTimeout(forceViewport, 1500);

  /* ------------------------------------------------------------------
   * 2) Virtual mouse
   * ------------------------------------------------------------------ */
  var BIT = { 0: 1, 1: 4, 2: 2 };      // DOM button -> DOM "buttons" bit
  var cur = { x: -1, y: -1 };
  var buttons = 0;
  var hoverEl = null;
  var lockEl = null;                   // element that owns the fake pointer lock
  var lockChild = null;                // iframe whose content owns the lock
  var lastReported = false;
  var cursorEl = null;
  var cursorVisible = true;
  var hasPE = (typeof window.PointerEvent === 'function');

  function clamp(v, a, b) { return v < a ? a : (v > b ? b : v); }
  function anyLock() { return !!(lockEl || lockChild); }

  function make(Ctor, type, init) {
    var ev;
    try { ev = new Ctor(type, init); } catch (e) { ev = new MouseEvent(type, init); }
    if (init.movementX !== undefined && ev.movementX !== init.movementX) {
      try {
        Object.defineProperty(ev, 'movementX', { value: init.movementX });
        Object.defineProperty(ev, 'movementY', { value: init.movementY });
      } catch (e) {}
    }
    return ev;
  }

  function fire(el, Ctor, type, init) {
    try { el.dispatchEvent(make(Ctor, type, init)); } catch (e) {}
  }

  function focusAt(el) {
    try {
      var f = el.closest ?
        el.closest('a[href],button,input,select,textarea,summary,[tabindex],[contenteditable]:not([contenteditable="false"])') :
        null;
      if (f && f.focus) { f.focus(); }
      else if (document.activeElement && document.activeElement !== document.body) { document.activeElement.blur(); }
    } catch (e) {}
  }

  function updateHover(el, base) {
    if (el === hoverEl) { return; }
    var old = hoverEl;
    hoverEl = el;
    if (old && old.isConnected) {
      if (hasPE) { fire(old, window.PointerEvent, 'pointerout', Object.assign({ pointerId: 1, pointerType: 'mouse', isPrimary: true }, base, { relatedTarget: el })); }
      fire(old, MouseEvent, 'mouseout', Object.assign({}, base, { relatedTarget: el }));
    }
    if (hasPE) { fire(el, window.PointerEvent, 'pointerover', Object.assign({ pointerId: 1, pointerType: 'mouse', isPrimary: true }, base, { relatedTarget: old })); }
    fire(el, MouseEvent, 'mouseover', Object.assign({}, base, { relatedTarget: old }));
  }

  function deliver(el, m) {
    var b = m.b || 0;
    var bit = BIT[b] || 0;
    if (m.t === 'down') { buttons |= bit; }
    else if (m.t === 'up') { buttons &= ~bit; }

    var base = {
      bubbles: true, cancelable: true, composed: true, view: window,
      clientX: m.x, clientY: m.y, screenX: m.x, screenY: m.y,
      buttons: buttons, movementX: m.dx || 0, movementY: m.dy || 0
    };
    function pinit(extra) {
      return Object.assign(
        { pointerId: 1, pointerType: 'mouse', isPrimary: true, width: 1, height: 1, pressure: buttons ? 0.5 : 0 },
        base, extra);
    }

    if (m.t === 'move') {
      if (!anyLock()) { updateHover(el, base); }
      if (hasPE) { fire(el, window.PointerEvent, 'pointermove', pinit({ button: -1 })); }
      fire(el, MouseEvent, 'mousemove', Object.assign({}, base, { button: 0 }));
    } else if (m.t === 'down') {
      if (hasPE) { fire(el, window.PointerEvent, 'pointerdown', pinit({ button: b })); }
      fire(el, MouseEvent, 'mousedown', Object.assign({}, base, { button: b, detail: 1 }));
      if (b === 0) { focusAt(el); }
      if (b === 2) { fire(el, MouseEvent, 'contextmenu', Object.assign({}, base, { button: 2, detail: 0 })); }
    } else if (m.t === 'up') {
      if (hasPE) { fire(el, window.PointerEvent, 'pointerup', pinit({ button: b })); }
      fire(el, MouseEvent, 'mouseup', Object.assign({}, base, { button: b, detail: 1 }));
      if (b === 0) { fire(el, MouseEvent, 'click', Object.assign({}, base, { button: 0, detail: 1 })); }
      else { fire(el, MouseEvent, 'auxclick', Object.assign({}, base, { button: b, detail: 1 })); }
    }
  }

  // route a mouse message to the right element, descending into iframes
  function handle(m) {
    var el = lockChild || lockEl || document.elementFromPoint(m.x, m.y) || document.documentElement;
    if (el.tagName === 'IFRAME' || el.tagName === 'FRAME') {
      var r = el.getBoundingClientRect();
      var c = {};
      for (var k in m) { if (Object.prototype.hasOwnProperty.call(m, k)) { c[k] = m[k]; } }
      c.x = m.x - r.left - (el.clientLeft || 0);
      c.y = m.y - r.top - (el.clientTop || 0);
      try { el.contentWindow.postMessage({ __vc: c }, '*'); } catch (e) {}
      return;
    }
    deliver(el, m);
  }

  window.addEventListener('message', function (e) {
    var d = e.data;
    if (!d || typeof d !== 'object') { return; }
    if (d.__vc && e.source === window.parent && !isTop) {
      handle(d.__vc);
    } else if (d.__vcUnlock === true && e.source === window.parent && !isTop) {
      unlock();
    } else if (typeof d.__vcLock === 'boolean') {
      var frames = document.querySelectorAll('iframe,frame');
      for (var i = 0; i < frames.length; i++) {
        if (frames[i].contentWindow === e.source) {
          if (d.__vcLock) { lockChild = frames[i]; }
          else if (lockChild === frames[i]) { lockChild = null; }
          break;
        }
      }
      reportLock();
    }
  }, true);

  /* ------------------------------------------------------------------
   * 3) Fake Pointer Lock (WebView has none)
   * ------------------------------------------------------------------ */
  function reportLock() {
    var l = anyLock();
    if (l === lastReported) { return; }
    lastReported = l;
    if (isTop) {
      drawCursor();
      try { if (window.__vcNative) { window.__vcNative.onLock(l); } } catch (e) {}
    } else {
      try { window.parent.postMessage({ __vcLock: l }, '*'); } catch (e) {}
    }
  }

  function changeEvent() {
    setTimeout(function () {
      try { document.dispatchEvent(new Event('pointerlockchange')); } catch (e) {}
    }, 0);
  }

  function unlock() {
    if (lockEl) {
      lockEl = null;
      changeEvent();
    }
    if (lockChild) {
      try { lockChild.contentWindow.postMessage({ __vcUnlock: true }, '*'); } catch (e) {}
    }
    reportLock();
  }

  try {
    Object.defineProperty(document, 'pointerLockElement', {
      get: function () { return lockEl; },
      configurable: true
    });
    Element.prototype.requestPointerLock = function () {
      lockEl = this;
      changeEvent();
      reportLock();
      return Promise.resolve();
    };
    Document.prototype.exitPointerLock = function () { unlock(); };
  } catch (e) {}

  /* ------------------------------------------------------------------
   * 4) On-page cursor (top frame only)
   * ------------------------------------------------------------------ */
  function drawCursor() {
    if (!isTop || cur.x < 0) { return; }
    if (!cursorEl) {
      cursorEl = document.createElement('div');
      cursorEl.id = '__vc_cursor';
      cursorEl.style.cssText =
        'position:fixed;left:0;top:0;width:0;height:0;z-index:2147483647;pointer-events:none;';
      cursorEl.innerHTML =
        '<svg style="position:absolute;left:-3px;top:-3px;overflow:visible" width="40" height="40" viewBox="0 0 22 22">' +
        '<path d="M2 2 L2 17 L6 13 L9 20 L12 19 L9 12 L15 12 Z" fill="#fff" stroke="#000" stroke-width="1.5" stroke-linejoin="round"/>' +
        '</svg>';
    }
    if (!cursorEl.isConnected) {
      (document.documentElement || document.body).appendChild(cursorEl);
    }
    cursorEl.style.transform = 'translate(' + cur.x + 'px,' + cur.y + 'px)';
    cursorEl.style.display = (cursorVisible && !anyLock()) ? 'block' : 'none';
  }

  function ensureStart() {
    if (cur.x < 0) {
      cur.x = window.innerWidth / 2;
      cur.y = window.innerHeight / 2;
    }
  }

  /* ------------------------------------------------------------------
   * 5) API used by the native app
   * ------------------------------------------------------------------ */
  window.__vc = {
    move: function (dx, dy) {
      if (!isTop) { return; }
      ensureStart();
      if (!anyLock()) {
        cur.x = clamp(cur.x + dx, 0, window.innerWidth - 1);
        cur.y = clamp(cur.y + dy, 0, window.innerHeight - 1);
      }
      drawCursor();
      handle({ t: 'move', x: cur.x, y: cur.y, dx: dx, dy: dy });
    },
    button: function (b, down) {
      if (!isTop) { return; }
      ensureStart();
      drawCursor();
      handle({ t: down ? 'down' : 'up', b: b, x: cur.x, y: cur.y });
    },
    show: function (v) {
      cursorVisible = !!v;
      drawCursor();
    },
    unlock: function () { unlock(); }
  };
})();
