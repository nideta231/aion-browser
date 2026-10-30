/* Page-side half of the desktop user agent: YouTube Music also checks navigator.userAgent. */
"use strict";
(() => {
  const UA = "Mozilla/5.0 (X11; Linux x86_64; rv:158.0) Gecko/20100101 Firefox/158.0";
  const page = window.wrappedJSObject;
  if (!page || typeof exportFunction !== "function") return;
  const nav = page.Navigator.prototype;
  const define = (name, value) => {
    try {
      Object.defineProperty(nav, name, {
        configurable: true,
        enumerable: true,
        get: exportFunction(() => value, window),
      });
    } catch (e) {}
  };
  define("userAgent", UA);
  define("appVersion", UA.slice(UA.indexOf("/") + 1));
  define("platform", "Linux x86_64");
  define("oscpu", "Linux x86_64");
})();
