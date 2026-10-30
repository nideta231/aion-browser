/* YouTube Music serves a stripped "mobile web" layout (no side queue, no Open App-free UI) to Android
 * user agents. Send a desktop Firefox user agent to music.youtube.com only, so tablets get the full app. */
"use strict";

const DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64; rv:158.0) Gecko/20100101 Firefox/158.0";

browser.webRequest.onBeforeSendHeaders.addListener(
  details => {
    for (const h of details.requestHeaders) {
      if (h.name.toLowerCase() === "user-agent") h.value = DESKTOP_UA;
    }
    return { requestHeaders: details.requestHeaders };
  },
  { urls: ["*://music.youtube.com/*"] },
  ["blocking", "requestHeaders"]
);
