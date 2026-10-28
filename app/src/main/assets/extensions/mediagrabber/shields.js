/*
 * Aion Shields, pop-up part (Brave-style): blocks "click-jacking" pop-ups, where a tap on the page
 * (not on a link to that place) opens another site in a new window/tab. Real links still work.
 */
"use strict";
(function () {
  let enabled = true;
  let allowed = [];
  const host = location.hostname.replace(/^www\./, "");
  browser.runtime.sendMessage({ type: "shieldsConfig" }).then(c => {
    if (c) { enabled = c.enabled; allowed = c.allowed || []; }
  }).catch(() => {});

  function siteOf(h) {
    const p = h.replace(/^www\./, "").split(".");
    return p.length > 2 ? p.slice(-2).join(".") : p.join(".");
  }
  function isAllowedHere() {
    return allowed.some(a => host === a || host.endsWith("." + a));
  }
  function crossSite(url) {
    try {
      const u = new URL(url, location.href);
      if (!/^https?:$/.test(u.protocol)) return false;
      return siteOf(u.hostname) !== siteOf(location.hostname);
    } catch (e) { return false; }
  }
  // The last element the user actually tapped, to tell real links from hijacked taps.
  let lastTarget = null;
  document.addEventListener("pointerdown", e => { lastTarget = e.target; }, true);
  document.addEventListener("click", e => { lastTarget = e.target; }, true);

  function tappedLinkTo(url) {
    const a = lastTarget && lastTarget.closest ? lastTarget.closest("a[href]") : null;
    if (!a) return false;
    try { return new URL(a.href, location.href).href === new URL(url, location.href).href; } catch (e) { return false; }
  }
  function isBlank(url) {
    const u = String(url || "").trim().toLowerCase();
    return u === "" || u === "about:blank" || u.startsWith("javascript:") || u.startsWith("data:");
  }
  function shouldBlock(url) {
    if (!enabled || isAllowedHere()) return false;
    // window.open("") / "about:blank" then filled with an ad URL: never a real link.
    if (isBlank(url)) return true;
    return crossSite(url) && !tappedLinkTo(url);
  }
  function report(url) {
    try { browser.runtime.sendMessage({ type: "popupBlocked", url: String(url), page: location.href }); } catch (e) {}
  }

  const win = window.wrappedJSObject;
  const originalOpen = win.open;
  exportFunction(function (url, target, features) {
    if (shouldBlock(url === undefined ? "" : String(url))) {
      report(url);
      return null;
    }
    return originalOpen.call(win, url, target, features);
  }, win, { defineAs: "open" });

  // Scripts that create an <a target=_blank> and click() it to dodge window.open blocking.
  const anchorProto = win.HTMLAnchorElement.prototype;
  const originalClick = anchorProto.click;
  exportFunction(function () {
    const a = this;
    const target = (a.getAttribute("target") || "").toLowerCase();
    if ((target === "_blank" || target === "_new") && !a.isConnected && shouldBlock(a.href)) {
      report(a.href);
      return;
    }
    return originalClick.call(a);
  }, anchorProto, { defineAs: "click" });

  // Forced redirects: a tap that makes the page itself jump to another site (tab-under).
  let lastTapAt = 0;
  document.addEventListener("pointerdown", () => { lastTapAt = Date.now(); }, true);
  function guardNavigation(url) {
    if (!enabled || isAllowedHere()) return false;
    if (!crossSite(String(url)) || tappedLinkTo(String(url))) return false;
    if (Date.now() - lastTapAt > 3000) return false; // not triggered by a tap; Aion's native guard handles it
    report(url);
    return true;
  }
  try {
    const loc = win.location;
    for (const name of ["assign", "replace"]) {
      const original = loc[name];
      exportFunction(function (url) {
        if (guardNavigation(url)) return;
        return original.call(loc, url);
      }, loc, { defineAs: name });
    }
  } catch (e) {}

  // Overlay ads: a tap on the page that opens a cross-site link which isn't what the user tapped.
  document.addEventListener("click", e => {
    const a = e.target && e.target.closest ? e.target.closest("a[href]") : null;
    if (!a) return;
    const target = (a.getAttribute("target") || "").toLowerCase();
    if (target !== "_blank" || !shouldBlock(a.href)) return;
    const r = a.getBoundingClientRect();
    const style = getComputedStyle(a);
    const invisible = parseFloat(style.opacity) < 0.1 || (r.width * r.height > innerWidth * innerHeight * 0.5 && !a.textContent.trim() && !a.querySelector("img"));
    if (invisible) {
      e.preventDefault();
      e.stopImmediatePropagation();
      report(a.href);
    }
  }, true);
})();
