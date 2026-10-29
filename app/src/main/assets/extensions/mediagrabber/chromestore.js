/* Chrome Web Store: Chrome extensions can't install in Aion, so offer the Firefox version from addons.mozilla.org. */
"use strict";
(function () {
  function add() {
    if (document.getElementById("aion-amo")) return;
    const h1 = document.querySelector("h1");
    if (!h1 || !h1.textContent.trim()) return;
    const name = h1.textContent.trim();
    const a = document.createElement("a");
    a.id = "aion-amo";
    a.href = "https://addons.mozilla.org/android/search/?q=" + encodeURIComponent(name);
    a.textContent = "Add to Aion (find Firefox version)";
    a.style.cssText = "position:fixed;right:16px;bottom:24px;z-index:2147483647;padding:12px 18px;border-radius:24px;" +
      "background:#6A3DF0;color:#fff;font:600 15px sans-serif;text-decoration:none;box-shadow:0 4px 12px rgba(0,0,0,.3)";
    document.body.appendChild(a);
  }
  add();
  new MutationObserver(add).observe(document.documentElement, { childList: true, subtree: true });
})();
