/* Watches network responses for downloadable video/audio and reports them to Aion on request. */
"use strict";

const MAX_PER_TAB = 60;
const tabs = new Map(); // tabId -> { page: string, items: Map<url, item> }

const MEDIA_EXT = /\.(m3u8|mpd|mp4|m4v|webm|mkv|mov|avi|flv|3gp|mp3|m4a|aac|ogg|oga|opus|flac|wav)(\?|#|$)/i;
const SEGMENT = /\.(ts|m4s|cmfv|cmfa|vtt|webvtt|key)(\?|#|$)|[?&](range|rn|rbuf)=|\/seg-?\d+|\/segment|\/chunk|\/frag/i;

function kindOf(url, type) {
  const t = (type || "").toLowerCase();
  if (/mpegurl/.test(t) || /\.m3u8(\?|#|$)/i.test(url)) return "HLS";
  if (/dash\+xml/.test(t) || /\.mpd(\?|#|$)/i.test(url)) return "DASH";
  if (t.startsWith("video/")) return "Video";
  if (t.startsWith("audio/")) return "Audio";
  const m = url.match(MEDIA_EXT);
  if (!m) return null;
  return /^(mp3|m4a|aac|ogg|oga|opus|flac|wav)$/i.test(m[1]) ? "Audio" : "Video";
}

function tabEntry(tabId) {
  let entry = tabs.get(tabId);
  if (!entry) {
    entry = { page: "", items: new Map() };
    tabs.set(tabId, entry);
  }
  return entry;
}

function header(headers, name) {
  const h = (headers || []).find(x => x.name.toLowerCase() === name);
  return h ? h.value : "";
}

browser.webRequest.onHeadersReceived.addListener(details => {
  if (details.tabId < 0) return;
  if (details.type === "main_frame") {
    const entry = tabEntry(details.tabId);
    entry.page = details.url;
    entry.items.clear();
    return;
  }
  if (details.statusCode >= 400 || !/^https?:/i.test(details.url)) return;
  const type = header(details.responseHeaders, "content-type").split(";")[0].trim();
  const kind = kindOf(details.url, type);
  if (!kind) return;
  // Pieces of a stream are useless on their own; the playlist (.m3u8/.mpd) is what gets downloaded.
  if ((kind === "Video" || kind === "Audio") && SEGMENT.test(details.url)) return;
  if (/video\/mp2t|iso\.segment/i.test(type)) return;
  const entry = tabEntry(details.tabId);
  if (entry.items.has(details.url)) return;
  const size = parseInt(header(details.responseHeaders, "content-length"), 10);
  // Tiny "video" responses are usually ads/beacons or partial range requests.
  if ((kind === "Video" || kind === "Audio") && size > 0 && size < 100 * 1024) return;
  const range = header(details.responseHeaders, "content-range");
  const total = range && range.includes("/") ? parseInt(range.split("/")[1], 10) : size;
  entry.items.set(details.url, {
    url: details.url,
    kind,
    mime: type,
    size: Number.isFinite(total) ? total : -1,
    page: entry.page || details.originUrl || details.documentUrl || "",
    time: Date.now(),
  });
  while (entry.items.size > MAX_PER_TAB) entry.items.delete(entry.items.keys().next().value);
}, { urls: ["<all_urls>"] }, ["responseHeaders"]);

browser.tabs.onRemoved.addListener(tabId => tabs.delete(tabId));

const port = browser.runtime.connectNative("aionMedia");
port.onMessage.addListener(async message => {
  if (!message || message.type !== "list") return;
  let tabId = -1;
  try {
    const active = await browser.tabs.query({ active: true });
    if (active.length) tabId = active[0].id;
  } catch (e) {}
  let entry = tabs.get(tabId);
  if (!entry || entry.items.size === 0) {
    // Fall back to the tab whose page matches the URL Aion is showing.
    for (const [, e] of tabs) if (message.url && e.page === message.url) entry = e;
  }
  const items = entry ? Array.from(entry.items.values()).sort((a, b) => b.time - a.time) : [];
  port.postMessage({ type: "media", id: message.id, items });
});
