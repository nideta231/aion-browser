/*
 * Aion bridge (added by Aion, not part of upstream uBlock Origin).
 * Lets Aion set the blocking level (Brave-style) and turn blocking off per site.
 */
import µb from './background.js';

const LEVELS = {
  standard: [
    'ublock-filters', 'ublock-badware', 'ublock-privacy', 'ublock-unbreak', 'ublock-quick-fixes',
    'easylist', 'easyprivacy', 'urlhaus-1', 'plowe-0', 'IDN-0',
  ],
};
LEVELS.moderate = LEVELS.standard.concat([
  'adguard-generic', 'adguard-mobile', 'adguard-popup-overlays', 'adguard-mobile-app-banners',
  'ublock-annoyances', 'curben-phishing',
]);
LEVELS.aggressive = LEVELS.moderate.concat([
  'ublock-experimental', 'adguard-spyware-url', 'easylist-annoyances', 'adguard-other-annoyances',
  'adguard-cookies', 'ublock-cookies-adguard', 'easylist-chat', 'easylist-newsletters',
  'easylist-notifications', 'adguard-social', 'fanboy-social', 'adguard-widgets', 'fanboy-ai-suggestions',
]);

const siteURL = host => `https://${host}/`;

function connect() {
  let port;
  try { port = browser.runtime.connectNative('aionShields'); } catch (e) { return; }
  port.onMessage.addListener(async m => {
    if (!m || typeof m !== 'object') { return; }
    await µb.isReadyPromise;
    if (m.type === 'level' && LEVELS[m.level]) {
      const wanted = LEVELS[m.level].join();
      const current = µb.selectedFilterLists.slice().sort().join();
      if (wanted.split(',').sort().join() !== current) {
        µb.applyFilterListSelection({ toSelect: LEVELS[m.level] });
        await µb.loadFilterLists();
      }
      if (m.update) { µb.scheduleAssetUpdater({ now: true }); }
      port.postMessage({ type: 'levelApplied', level: m.level, lists: µb.selectedFilterLists.length });
    } else if (m.type === 'update') {
      µb.scheduleAssetUpdater({ now: true });
    } else if (m.type === 'site' && m.host) {
      µb.toggleNetFilteringSwitch(siteURL(m.host), '', m.enabled !== false);
      port.postMessage({ type: 'site', host: m.host, enabled: µb.getNetFilteringSwitch(siteURL(m.host)) });
    } else if (m.type === 'query' && m.host) {
      port.postMessage({ type: 'site', host: m.host, enabled: µb.getNetFilteringSwitch(siteURL(m.host)) });
    }
  });
}
connect();
