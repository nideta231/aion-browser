/* Watches network responses for downloadable video/audio and reports them to Aion on request. */
"use strict";

const MAX_PER_TAB = 60;
const tabs = new Map(); // tabId -> { page, generation, items: Map<key, item>, ytBlocked }

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
    entry = { page: "", generation: 0, items: new Map(), ytBlocked: false };
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
    entry.generation++;
    entry.items.clear();
    entry.ytBlocked = false;
    return;
  }
  if (/\.googlevideo\.com$/i.test(new URL(details.url).hostname)) {
    youtube(details);
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

/*
 * YouTube streams video and audio separately from *.googlevideo.com/videoplayback, fetched in
 * ranges. Dropping the range parameters gives the whole stream; one entry per format (itag).
 */
const YT_QUALITY = {
  "18": "360p", "22": "720p", "133": "240p", "134": "360p", "135": "480p", "136": "720p",
  "137": "1080p", "160": "144p", "242": "240p", "243": "360p", "244": "480p", "247": "720p",
  "248": "1080p", "271": "1440p", "313": "2160p", "278": "144p", "298": "720p60", "299": "1080p60",
  "302": "720p60", "303": "1080p60", "308": "1440p60", "315": "2160p60", "394": "144p", "395": "240p",
  "396": "360p", "397": "480p", "398": "720p", "399": "1080p", "400": "1440p", "401": "2160p",
  "139": "48k", "140": "128k", "141": "256k", "249": "50k", "250": "70k", "251": "160k",
};

function youtube(details) {
  if (details.statusCode >= 400) return;
  const entry = tabEntry(details.tabId);
  const type = header(details.responseHeaders, "content-type").split(";")[0].trim();
  const url = new URL(details.url);
  const params = url.searchParams;
  if (details.method === "POST" || /ump/i.test(type) || params.get("sabr") === "1") {
    // Newer YouTube player format: the stream URL alone can't be downloaded.
    entry.ytBlocked = true;
    return;
  }
  const itag = params.get("itag");
  const mime = decodeURIComponent(params.get("mime") || type || "");
  if (!itag || !/^(video|audio)\//.test(mime)) return;
  for (const p of ["range", "rn", "rbuf", "ump", "srfvp", "alr", "cpn"]) params.delete(p);
  const key = "yt:" + itag;
  if (entry.items.has(key)) return;
  const audio = mime.startsWith("audio/");
  entry.items.set(key, {
    url: url.toString(),
    kind: audio ? "Audio" : "Video",
    mime,
    size: parseInt(params.get("clen") || "-1", 10),
    page: entry.page,
    time: Date.now(),
    youtube: true,
    quality: YT_QUALITY[itag] || ("itag " + itag),
    container: /webm/.test(mime) ? "webm" : "mp4",
  });
}

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
  port.postMessage({
    type: "media",
    id: message.id,
    items,
    generation: entry ? entry.generation : 0,
    ytBlocked: entry ? entry.ytBlocked : false,
  });
});
