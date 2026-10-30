/* Aion YouTube Music skin helpers: hides "Open App" entries and upsell dialogs, and exposes the
 * playing track's artwork to the CSS (blurred Now Playing backdrop). */
"use strict";
(() => {
  const OPEN_APP = /^(open app|buka aplikasi|open in the youtube music app)$/i;
  const UPSELL = /youtube music app|get the app|download the app|aplikasi youtube music/i;

  function hideOpenApp() {
    for (const entry of document.querySelectorAll("ytmusic-guide-entry-renderer:not([aion-open-app])")) {
      const text = (entry.innerText || "").trim();
      if (OPEN_APP.test(text)) entry.setAttribute("aion-open-app", "");
    }
  }

  function closeUpsells() {
    for (const dialog of document.querySelectorAll("tp-yt-paper-dialog, ytmusic-popup-container *[role=dialog]")) {
      if (dialog.hasAttribute("aion-upsell")) continue;
      const text = dialog.innerText || "";
      if (!UPSELL.test(text)) continue;
      const notNow = [...dialog.querySelectorAll("button, yt-button-renderer, tp-yt-paper-button")]
        .find(b => /not now|no thanks|nanti|tidak/i.test(b.innerText || ""));
      if (notNow) notNow.click();
      dialog.setAttribute("aion-upsell", "");
    }
    for (const backdrop of document.querySelectorAll("tp-yt-iron-overlay-backdrop.opened")) {
      if (!document.querySelector("tp-yt-paper-dialog:not([aion-upsell])[aria-hidden='false'], tp-yt-paper-dialog:not([aion-upsell]):not([aria-hidden])")) {
        backdrop.classList.add("aion-hidden");
      }
    }
  }

  let lastArt = "";
  function syncArtwork() {
    const page = document.querySelector("ytmusic-player-page");
    const img = document.querySelector("ytmusic-player-bar .image, ytmusic-player-bar img.image");
    if (!page || !img || !img.src || img.src.startsWith("data:")) return;
    const big = img.src.replace(/=w\d+-h\d+/, "=w544-h544");
    if (big === lastArt) return;
    lastArt = big;
    page.style.setProperty("--aion-art", `url("${big.replace(/"/g, "%22")}")`);
    page.setAttribute("aion-art", "");
  }

  let queued = false;
  function tick() {
    queued = false;
    hideOpenApp();
    closeUpsells();
    syncArtwork();
  }
  const observer = new MutationObserver(() => {
    if (!queued) {
      queued = true;
      requestAnimationFrame(tick);
    }
  });
  const start = () => {
    observer.observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ["src", "opened"] });
    tick();
  };
  if (document.documentElement) start();
  else document.addEventListener("DOMContentLoaded", start, { once: true });
})();
