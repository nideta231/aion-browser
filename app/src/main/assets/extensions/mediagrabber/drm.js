/* Notes when a page asks for a DRM key system (Widevine, PlayReady...), so Aion can mark the stream "watch only". */
"use strict";
(function () {
  try {
    const nav = window.wrappedJSObject.navigator;
    const original = nav.requestMediaKeySystemAccess;
    if (typeof original !== "function") return;
    exportFunction(function (keySystem, configs) {
      try { browser.runtime.sendMessage({ type: "drm", keySystem: String(keySystem) }); } catch (e) {}
      return original.call(nav, keySystem, configs);
    }, nav, { defineAs: "requestMediaKeySystemAccess" });
  } catch (e) {}
})();
