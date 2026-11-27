/* Liza web — browser front-end. Talks to the local server (server.py),
 * which holds the EZVIZ session and forwards a fixed set of API calls. */
"use strict";

const $ = (s) => document.querySelector(s);
const $$ = (s) => [...document.querySelectorAll(s)];
const store = {
  get(k, d) { try { const v = localStorage.getItem("liza." + k); return v === null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem("liza." + k, JSON.stringify(v)); } catch { /* private mode */ } },
};

const state = {
  session: null,
  devices: [],
  device: null,          // current device object
  player: null,
  mode: "idle",          // idle | live | rec
  playing: false,
  sound: true,
  talking: false,
  recording: false,
  zoom: { scale: 1, x: 0, y: 0 },
  quality: (() => { const q = Number(store.get("quality", 2)); return q >= 1 && q <= 6 ? q : 2; })(),
  stallTimer: 0,
  recFiles: [],          // current SD list, newest first
  recIndex: -1,
  rec: null,             // {begin, end} in ms for the clip playing
  lookupWindow: null,    // clip window for the player's SD lookup (see fix above)
  osdTimer: 0,
  thumbs: {},            // clip key -> image URL (data URL or alert snapshot)
  scanId: 0,             // increments to cancel a running preview scan
  sdEstimate: null,      // last SD card estimate shown
};

/* ---------- EZVIZ player fix ----------
 * For SD playback the web player looks up the whole local calendar day
 * (00:00–23:59:59). Outside UTC that range spans two UTC days and EZVIZ
 * rejects it ("cannot be more than 1 day"), so playback fails with 395402.
 * Narrow that one lookup to the clip being played. */
const nativeFetch = window.fetch.bind(window);
window.fetch = (input, init) => {
  const url = typeof input === "string" ? input : input && input.url;
  const win = state.lookupWindow;
  if (url && url.includes("/api/v3/device/local/video/unify/query") && win) {
    const u = new URL(url, location.href);
    if (!u.searchParams.has("nextFileTime") || u.searchParams.get("startTime") === u.searchParams.get("nextFileTime")) {
      const from = Math.floor(win.begin / 1000) - 60;
      const to = Math.ceil(win.end / 1000) + 60;
      const start = Number(u.searchParams.get("startTime"));
      // Keep a later startTime (paging with nextFileTime), otherwise use the clip window.
      u.searchParams.set("startTime", String(start > from && start < to ? start : from));
      u.searchParams.set("endTime", String(to));
      return nativeFetch(typeof input === "string" ? u.toString() : new Request(u.toString(), input), init);
    }
  }
  return nativeFetch(input, init);
};

/* ---------- helpers ---------- */
function toast(msg) {
  const t = $("#toast");
  t.textContent = msg;
  t.classList.add("show");
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => t.classList.remove("show"), 3200);
}
function setStatus(msg) { $("#status").textContent = msg; }
const pad = (n) => String(n).padStart(2, "0");

/* EZVIZ's web API reports SD recording and alert times as the camera's
 * wall-clock time written as if it were UTC ("wall time"). We keep those
 * values as they are, format them with UTC fields, and convert real
 * timestamps to wall time with the laptop's UTC offset (assumed to match
 * the camera's). */
const tzOffset = (ms = Date.now()) => -new Date(ms).getTimezoneOffset() * 60000;
const wallNow = () => Date.now() + tzOffset();
function wallStamp(ms) { // YYYYMMDDHHmmss of a wall-time value, as the player expects
  const d = new Date(ms);
  return `${d.getUTCFullYear()}${pad(d.getUTCMonth() + 1)}${pad(d.getUTCDate())}${pad(d.getUTCHours())}${pad(d.getUTCMinutes())}${pad(d.getUTCSeconds())}`;
}
const hhmmss = (ms) => new Date(ms).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit", timeZone: "UTC" });
const hhmm = (ms) => new Date(ms).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", timeZone: "UTC" });
const wallDateTime = (ms) => new Date(ms).toLocaleString([], { dateStyle: "medium", timeStyle: "medium", timeZone: "UTC" });
function dur(ms) { const s = Math.max(0, Math.round(ms / 1000)); return `${Math.floor(s / 60)} min ${pad(s % 60)} sec`; }
function fileStamp() { return `Liza-${wallStamp(wallNow())}`; }
function el(tag, attrs = {}, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === "class") e.className = v;
    else if (k.startsWith("on")) e.addEventListener(k.slice(2), v);
    else if (k === "style") e.setAttribute("style", v);
    else e[k] = v;
  }
  for (const k of kids) e.append(k);
  return e;
}

async function api(name, params = {}) {
  const res = await fetch("/api/ezviz/" + name, { method: "POST", body: JSON.stringify(params) });
  const j = await res.json().catch(() => ({}));
  if (j.signedOut) { showSignIn(); throw new Error(j.error); }
  if (j.error) throw new Error(j.error);
  if (String(j.code) !== "200") throw new Error(`EZVIZ error ${j.code}${j.msg ? ": " + j.msg : ""}`);
  return j.data;
}

/* ---------- connection setup (AppKey / AppSecret / region) ---------- */
async function getConfig() {
  state.config = await fetch("/api/config").then((r) => r.json());
  return state.config;
}
function fillConnForm(form, cfg) {
  const sel = form.elements.region;
  if (!sel.options.length) for (const r of cfg.regions) sel.append(new Option(r, r));
  form.elements.appKey.value = cfg.appKey || "";
  form.elements.appSecret.value = "";
  form.elements.appSecret.placeholder = cfg.hasSecret ? "Saved (hidden). Type a new one to replace it." : form.id === "setupForm"
    ? "Leave empty to sign in with your EZVIZ account instead" : "Not set";
  sel.value = cfg.region || "Europe";
}
async function saveConnection(form, extra = {}) {
  const msg = form.querySelector(".form-msg");
  const body = { appKey: form.elements.appKey.value.trim(), region: form.elements.region.value, ...extra };
  if (form.elements.appSecret.value.trim()) body.appSecret = form.elements.appSecret.value.trim();
  msg.textContent = body.appSecret ? "Checking your AppKey and AppSecret with EZVIZ…" : "Saving…";
  const res = await fetch("/api/config", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  const j = await res.json().catch(() => ({ error: "No response from the Liza server." }));
  form.elements.appSecret.value = "";
  if (j.error) { msg.textContent = j.error; return false; }
  state.config = j;
  msg.textContent = "Saved.";
  return true;
}
function showSetup() {
  destroyPlayer();
  $("#app").hidden = true;
  $("#signin").hidden = true;
  $("#setup").hidden = false;
  fillConnForm($("#setupForm"), state.config || { regions: ["Europe"] });
}
$("#setupForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  if (await saveConnection(e.target)) boot();
});
$("#openSetup").addEventListener("click", showSetup);
$("#connForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const before = JSON.stringify(state.config);
  if (await saveConnection(e.target)) {
    renderConnInfo();
    if (JSON.stringify(state.config) !== before) { toast("Connection updated. Reconnecting…"); boot(); }
  }
});
$("#clearSecret").addEventListener("click", async () => {
  if (!state.config || !state.config.hasSecret) return toast("No AppSecret is saved");
  if (!confirm("Remove the AppSecret? You'll sign in with your EZVIZ account instead.")) return;
  if (await saveConnection($("#connForm"), { clearSecret: true })) { toast("AppSecret removed"); boot(); }
});
function renderConnInfo() {
  const c = state.config || {};
  $("#connInfo").textContent = c.hasSecret
    ? `Connected automatically with your AppSecret · ${c.region}. Access renews by itself.`
    : `Signed in with your EZVIZ account · ${c.region}. Add an AppSecret to skip the sign-in step.`;
  fillConnForm($("#connForm"), c);
}

/* ---------- sign-in ---------- */
function showSignIn() {
  destroyPlayer();
  $("#app").hidden = true;
  $("#setup").hidden = true;
  $("#signin").hidden = false;
}
$("#pasteBtn").addEventListener("click", async () => {
  const input = $("#pasteUrl");
  const msg = $("#pasteMsg");
  msg.textContent = "Checking…";
  const res = await fetch("/auth/paste", { method: "POST", body: JSON.stringify({ url: input.value }) });
  const j = await res.json();
  input.value = "";
  if (j.ok) { msg.textContent = ""; boot(); }
  else msg.textContent = "Couldn't read that address: " + j.error + (j.fieldsFound ? ` (fields found: ${j.fieldsFound.join(", ") || "none"})` : "");
});
$("#signOutBtn").addEventListener("click", async () => {
  if (!confirm("Sign out of EZVIZ on this computer?")) return;
  await fetch("/auth/logout", { method: "POST" });
  showSignIn();
});

/* ---------- devices ---------- */
async function loadDevices() {
  setStatus("Loading devices…");
  try {
    state.devices = (await api("deviceList", { pageStart: 0, pageSize: 50 })) || [];
  } catch (e) { setStatus("Could not load devices. " + e.message); return; }
  renderDevices();
  if (!state.devices.length) { setStatus("No devices on this EZVIZ account."); return; }
  const last = store.get("device");
  const pick = state.devices.find((d) => d.deviceSerial === last) || state.devices[0];
  selectDevice(pick);
}
function renderDevices() {
  const box = $("#devices");
  box.replaceChildren();
  for (const d of state.devices) {
    const online = d.status === 1;
    const b = el("button", { class: "device" + (state.device && state.device.deviceSerial === d.deviceSerial ? " active" : ""),
      onclick: () => selectDevice(d) },
      el("div", { class: "name" }, d.deviceName || "Door viewer"),
      el("div", { class: "meta" }, el("span", { class: "dot" + (online ? " on" : "") }), online ? "Online" : "Offline",
        d.defence === 1 ? " · Armed" : " · Disarmed"));
    box.append(b);
  }
}
function selectDevice(d) {
  state.device = d;
  store.set("device", d.deviceSerial);
  $("#deviceTitle").textContent = d.deviceName || "Door viewer";
  renderDevices();
  cancelScan();
  state.recFiles = [];
  $("#recList").replaceChildren();
  $("#recMsg").textContent = "";
  const tab = currentTab();
  if (tab === "settings") loadSettings();
  if (tab === "alerts") loadAlerts();
  loadSdPlan();
  startLive();
}

/* ---------- player ---------- */
function destroyPlayer() {
  clearInterval(state.osdTimer);
  clearInterval(state.stallTimer);
  resetZoom();
  const p = state.player;
  state.player = null;
  state.playing = false;
  if (p) { try { p.destroy(); } catch { /* already gone */ } }
  $("#player").replaceChildren();
}
function createPlayer(url, onReady) {
  destroyPlayer();
  const wrap = $("#playerWrap");
  $("#playerIdle").hidden = true;
  state.player = new EZUIKit.EZUIKitPlayer({
    id: "player",
    accessToken: state.session.accessToken,
    url,
    template: "simple",
    width: Math.min(wrap.clientWidth, Math.round(wrap.clientHeight * 16 / 9)),
    height: Math.min(wrap.clientHeight, Math.round(wrap.clientWidth * 9 / 16)),
    env: { domain: state.session.domain },
    staticPath: location.origin + "/vendor/ezuikit_static",
    handleSuccess: () => { state.playing = true; onReady && onReady(); },
    handleError: (e) => {
      state.playing = false;
      const code = e && (e.retcode || (e.data && (e.data.nErrorCode || e.data.code)));
      setStatus("Video error" + (code ? " " + code : "") + ". Wake the camera (press the doorbell) and try again.");
    },
  });
}
function liveUrl() {
  const d = state.device;
  // The highest level uses the camera's main stream (".hd"); the rest use the sub stream.
  return `ezopen://open.ezviz.com/${d.deviceSerial}/1${state.quality >= 3 ? ".hd" : ""}.live`;
}

/* ---------- quality: SD / HD / FHD as reported by the camera ---------- */
const LEVEL_NAMES = { VIDEO_LEVEL_FLUNET: "Fluent", VIDEO_LEVEL_FLUENT: "Fluent", VIDEO_LEVEL_BALANCED: "Balanced",
  VIDEO_LEVEL_STANDARD: "SD", VIDEO_LEVEL_HEIGH: "HD", VIDEO_LEVEL_HIGH: "HD", VIDEO_LEVEL_SUPER: "FHD", VIDEO_LEVEL_EXTREME: "2K+" };
const levelLabel = (l) => LEVEL_NAMES[l.name] || ["", "SD", "HD", "FHD", "2K", "3K", "4K"][l.level] || `Level ${l.level}`;
function fillQuality() {
  const list = (state.player && state.player.videoLevelList) || [];
  const sel = $("#quality");
  if (!list.length) return;
  sel.replaceChildren(...list.map((l) => new Option(levelLabel(l), String(l.level))));
  const current = state.player.videoLevel || state.quality;
  sel.value = String(list.some((l) => l.level === current) ? current : list[list.length - 1].level);
}
async function applyQuality(level) {
  const p = state.player;
  const target = p && (p.videoLevelList || []).find((l) => l.level === level);
  if (!target || p.videoLevel === level) return;
  const name = levelLabel(target);
  setStatus(`Switching to ${name}…`);
  try {
    await p.changeVideoLevel(target);
    setStatus(`Live · ${name}`);
  } catch { setStatus("Couldn't change quality. Try again."); }
  fillQuality();
}

/* ---------- stall guard: reconnect if the live picture stops ---------- */
function startStallGuard() {
  clearInterval(state.stallTimer);
  let last = null;
  let since = Date.now();
  state.stallTimer = setInterval(async () => {
    if (state.mode !== "live" || !state.playing || !state.player || document.hidden) { since = Date.now(); return; }
    let t = null;
    try { const r = await state.player.getOSDTime(); t = r && (r.data ?? r); } catch { /* ignore */ }
    if (t && t !== last) { last = t; since = Date.now(); return; }
    if (Date.now() - since > 6000) {
      clearInterval(state.stallTimer);
      const wasRecording = state.recording;
      if (wasRecording) { try { await state.player.stopSave(); } catch { /* ignore */ } state.recording = false; }
      toast(wasRecording ? "Live video stalled. The recording so far was saved; reconnecting…" : "Live video stalled. Reconnecting…");
      startLive();
    }
  }, 2000);
}

/* ---------- zoom (Liza's own: scroll, +/−, drag) ---------- */
function applyZoom() {
  const z = state.zoom;
  const wrap = $("#playerWrap");
  const w = wrap.clientWidth, h = wrap.clientHeight;
  z.scale = Math.min(4, Math.max(1, z.scale));
  // Keep the zoomed picture covering the frame.
  z.x = Math.min(0, Math.max(w - w * z.scale, z.x));
  z.y = Math.min(0, Math.max(h - h * z.scale, z.y));
  if (z.scale === 1) { z.x = 0; z.y = 0; }
  $("#zoomLayer").style.transform = `translate(${z.x}px, ${z.y}px) scale(${z.scale})`;
  $("#zoomLevel").textContent = (Math.round(z.scale * 10) / 10) + "×";
  wrap.classList.toggle("zoomed", z.scale > 1);
  $("#zoomBtn").classList.toggle("on", z.scale > 1);
  $("#zoomBtn").textContent = z.scale > 1 ? "Reset zoom" : "Zoom";
}
function zoomAt(factor, cx, cy) {
  const z = state.zoom;
  const wrap = $("#playerWrap");
  if (cx === undefined) { cx = wrap.clientWidth / 2; cy = wrap.clientHeight / 2; }
  const next = Math.min(4, Math.max(1, z.scale * factor));
  // Keep the point under the cursor in place.
  z.x = cx - (cx - z.x) * (next / z.scale);
  z.y = cy - (cy - z.y) * (next / z.scale);
  z.scale = next;
  applyZoom();
}
function resetZoom() { Object.assign(state.zoom, { scale: 1, x: 0, y: 0 }); if ($("#zoomLayer")) applyZoom(); }
(() => {
  const wrap = $("#playerWrap");
  wrap.addEventListener("wheel", (e) => {
    if (!state.player) return;
    e.preventDefault();
    const r = wrap.getBoundingClientRect();
    zoomAt(e.deltaY < 0 ? 1.15 : 1 / 1.15, e.clientX - r.left, e.clientY - r.top);
  }, { passive: false });
  let drag = null;
  wrap.addEventListener("pointerdown", (e) => {
    if (state.zoom.scale <= 1 || e.target.closest(".overlay")) return;
    drag = { x: e.clientX, y: e.clientY, ox: state.zoom.x, oy: state.zoom.y };
    wrap.setPointerCapture(e.pointerId);
    wrap.classList.add("dragging");
  });
  wrap.addEventListener("pointermove", (e) => {
    if (!drag) return;
    state.zoom.x = drag.ox + (e.clientX - drag.x);
    state.zoom.y = drag.oy + (e.clientY - drag.y);
    applyZoom();
  });
  const endDrag = () => { drag = null; wrap.classList.remove("dragging"); };
  wrap.addEventListener("pointerup", endDrag);
  wrap.addEventListener("pointercancel", endDrag);
  // Double-click: our full screen (the player's own one hides Liza's controls).
  wrap.addEventListener("dblclick", (e) => {
    if (e.target.closest(".overlay")) return;
    e.preventDefault(); e.stopPropagation();
    toggleFullscreen();
  }, true);
  $("#zoomIn").addEventListener("click", () => zoomAt(1.5));
  $("#zoomOut").addEventListener("click", () => zoomAt(1 / 1.5));
  $("#fsBtn").addEventListener("click", toggleFullscreen);
})();

/* ---------- full screen ---------- */
function setFakeFullscreen(on) {
  // Fallback when the browser won't grant real full screen: fill the window.
  $("#playerWrap").classList.toggle("fake-fs", on);
  document.body.classList.toggle("no-scroll", on);
  syncFullscreenLabels();
  resetZoom();
}
function isFullscreen() { return document.fullscreenElement === $("#playerWrap") || $("#playerWrap").classList.contains("fake-fs"); }
function syncFullscreenLabels() {
  const on = isFullscreen();
  $("#fsBtn").textContent = on ? "✕ Exit full screen" : "⛶ Full screen";
  $("#fullBtn").textContent = on ? "Exit full screen" : "Full screen";
  $("#recFullBtn").textContent = on ? "Exit full screen" : "Full screen";
}
function toggleFullscreen() {
  const wrap = $("#playerWrap");
  if (wrap.classList.contains("fake-fs")) return setFakeFullscreen(false);
  if (document.fullscreenElement) { document.exitFullscreen().catch(() => {}); return; }
  if (!wrap.requestFullscreen) return setFakeFullscreen(true);
  let settled = false;
  wrap.requestFullscreen().then(() => { settled = true; }, () => { settled = true; setFakeFullscreen(true); });
  setTimeout(() => { if (!settled && !document.fullscreenElement) setFakeFullscreen(true); }, 1200);
}
document.addEventListener("fullscreenchange", () => {
  if (document.fullscreenElement) $("#playerWrap").classList.remove("fake-fs");
  syncFullscreenLabels();
  resetZoom();
});
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && $("#playerWrap").classList.contains("fake-fs")) return setFakeFullscreen(false);
  if (e.target.closest("input, select, textarea")) return;
  if (e.key === "f" || e.key === "F") toggleFullscreen();
  if (e.key === "+" || e.key === "=") zoomAt(1.5);
  if (e.key === "-") zoomAt(1 / 1.5);
  if (e.key === "0") resetZoom();
});
function startLive() {
  if (!state.device) return;
  if (state.talking) stopTalk();
  state.mode = "live";
  state.rec = null;
  $("#liveBar").hidden = false;
  $("#recBar").hidden = true;
  state.recIndex = -1;
  if (state.recFiles.length) renderRecordings();
  state.recording = false;
  syncButtons();
  if (state.device.status !== 1) setStatus("Camera is offline. Trying anyway…");
  else setStatus("Connecting to camera…");
  createPlayer(liveUrl(), () => {
    setStatus("Live");
    if (!state.sound) state.player.closeSound();
    fillQuality();
    // Apply the saved quality once the player knows the camera's levels.
    setTimeout(() => applyQuality(state.quality), 300);
    startStallGuard();
    syncButtons();
  });
}
function playRecording(begin, end, label, seekTo) {
  if (!state.device) return;
  if (state.talking) stopTalk();
  cancelScan();
  state.mode = "rec";
  state.rec = { begin, end };
  state.lookupWindow = { begin, end };
  $("#liveBar").hidden = true;
  $("#recBar").hidden = false;
  setStatus("Loading recording " + (label || "") + "…");
  const from = seekTo ? Math.max(begin, seekTo) : begin;
  const url = `ezopen://open.ezviz.com/${state.device.deviceSerial}/1.rec?begin=${wallStamp(from)}&end=${wallStamp(end)}`;
  createPlayer(url, () => {
    setStatus("Playing recording " + (label || ""));
    clearInterval(state.osdTimer);
    state.osdTimer = setInterval(tickOsd, 1000);
  });
}
async function osdMs() {
  try {
    const t = await state.player.getOSDTime();
    const v = typeof t === "object" && t !== null ? (t.data ?? t.time ?? t.osdTime) : t;
    const n = Number(v);
    if (!n) return null;
    const real = n < 1e12 ? n * 1000 : n; // the player reports real time
    return real + tzOffset(real);          // convert to wall time
  } catch { return null; }
}
async function tickOsd() {
  if (state.mode !== "rec" || !state.player) return;
  const now = await osdMs();
  if (now) $("#osd").textContent = hhmmss(now);
  if (now && state.rec && now >= state.rec.end - 1500 && $("#autoplay").checked) {
    clearInterval(state.osdTimer);
    playNewer();
  }
}
async function seekBy(offset) {
  if (!state.rec) return;
  const now = (await osdMs()) || state.rec.begin;
  const to = Math.min(state.rec.end - 2000, Math.max(state.rec.begin, now + offset));
  playRecording(state.rec.begin, state.rec.end, "", to);
}
function playNewer() {
  if (state.recIndex > 0) {
    state.recIndex -= 1;
    playFile(state.recIndex);
  } else {
    setStatus("End of the latest recording.");
  }
}

/* ---------- live controls ---------- */
function syncButtons() {
  $("#playBtn").textContent = state.playing ? "Pause video" : "Resume video";
  $("#soundBtn").textContent = state.sound ? "Sound on" : "Sound off";
  $("#soundBtn").classList.toggle("on", state.sound);
  $("#talkBtn").textContent = state.talking ? "End talk" : "Talk";
  $("#talkBtn").classList.toggle("danger", state.talking);
  $("#recBtn").textContent = state.recording ? "Stop recording" : "Record";
  $("#recBtn").classList.toggle("danger", state.recording);
}
$("#playBtn").addEventListener("click", async () => {
  if (!state.player) return startLive();
  if (state.playing) {
    if (state.recording) await toggleRecord();
    if (state.talking) stopTalk();
    await state.player.stop();
    state.playing = false;
    setStatus("Video paused");
  } else {
    setStatus("Resuming…");
    await state.player.play();
    state.playing = true;
    setStatus("Live");
  }
  syncButtons();
});
$("#quality").addEventListener("change", (e) => {
  state.quality = Number(e.target.value);
  store.set("quality", state.quality);
  applyQuality(state.quality);
});
$("#soundBtn").addEventListener("click", async () => {
  if (!state.player) return;
  state.sound = !state.sound;
  try { state.sound ? await state.player.openSound() : await state.player.closeSound(); }
  catch { state.sound = !state.sound; toast("Could not change sound"); }
  syncButtons();
});
async function stopTalk() {
  try { await state.player.stopTalk(); } catch { /* ignore */ }
  state.talking = false;
  syncButtons();
  setStatus("Live");
}
$("#talkBtn").addEventListener("click", async () => {
  if (!state.player || !state.playing) return toast("Wait for live video before talking");
  if (state.talking) return stopTalk();
  try {
    setStatus("Starting two-way talk… (allow the microphone if asked)");
    await state.player.startTalk();
    state.talking = true;
    setStatus("Live · Two-way talk active");
  } catch (e) {
    setStatus("Two-way talk could not start. " + (e && e.message ? e.message : ""));
  }
  syncButtons();
});
async function snapshot() {
  if (!state.player || !state.playing) return toast("Start the video first");
  try { await state.player.capturePicture(fileStamp()); toast("Snapshot downloaded"); }
  catch { toast("Snapshot failed"); }
}
$("#snapBtn").addEventListener("click", snapshot);
$("#recSnapBtn").addEventListener("click", snapshot);
async function toggleRecord() {
  if (!state.player || !state.playing) return toast("Start the video first");
  try {
    if (state.recording) {
      await state.player.stopSave();
      state.recording = false;
      toast("Recording downloaded");
    } else {
      await state.player.startSave(fileStamp());
      state.recording = true;
      toast("Recording… record at least 5 seconds");
    }
  } catch { toast("Recording failed"); state.recording = false; }
  syncButtons();
}
$("#recBtn").addEventListener("click", toggleRecord);
$("#zoomBtn").addEventListener("click", () => {
  if (state.zoom.scale > 1) resetZoom();
  else { zoomAt(2); toast("Zoomed 2×. Scroll to zoom, drag to move, press 0 to reset."); }
});
$("#fullBtn").addEventListener("click", toggleFullscreen);
$("#recFullBtn").addEventListener("click", toggleFullscreen);
$("#backLiveBtn").addEventListener("click", startLive);
$("#minus10").addEventListener("click", () => seekBy(-10000));
$("#plus10").addEventListener("click", () => seekBy(10000));

/* ---------- SD card recordings ---------- */
function todayInput() { const d = new Date(); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`; }
$("#recDate").value = todayInput();
$("#recDate").max = todayInput();
$("#autoplay").checked = store.get("autoplay", true);
$("#autoplay").addEventListener("change", (e) => store.set("autoplay", e.target.checked));
$("#recSearch").addEventListener("click", searchRecordings);

async function searchRecordings() {
  if (!state.device) return;
  const [y, m, d] = $("#recDate").value.split("-").map(Number);
  const start = Date.UTC(y, m - 1, d);                 // wall-time midnight
  const end = Math.min(wallNow(), start + 86400000 - 1);
  $("#recMsg").textContent = "Searching the camera's SD card…";
  $("#recList").replaceChildren();
  cancelScan();
  try {
    const files = (await api("recordsByTime", {
      deviceSerial: state.device.deviceSerial, channelNo: 1, startTime: start, endTime: end, recType: 2,
    })) || [];
    state.recFiles = files.sort((a, b) => b.startTime - a.startTime);
    state.recDay = { start, end };
    renderRecordings();
    loadThumbs(start, end);
  } catch (e) {
    $("#recMsg").textContent = "Search failed. " + e.message + " If the camera is asleep, press the doorbell and try again.";
  }
}
function renderRecordings() {
  const list = $("#recList");
  list.replaceChildren();
  const n = state.recFiles.length;
  $("#recMsg").textContent = n ? `${n} recording${n === 1 ? "" : "s"} · newest first` : "No recordings on the SD card for this day.";
  state.recFiles.forEach((f, i) => {
    const thumb = el("div", { class: "thumb" });
    thumb.dataset.key = clipKey(f);
    const src = state.thumbs[clipKey(f)] || f.coverPic;
    if (src) thumb.style.backgroundImage = `url("${src}")`;
    const item = el("button", { class: "item" + (i === state.recIndex ? " playing" : ""), onclick: () => playFile(i) },
      thumb,
      el("div", {}, el("div", { class: "t" }, `${hhmm(f.startTime)} – ${hhmm(f.endTime)}`),
        el("div", { class: "s" }, dur(f.endTime - f.startTime) + " · SD card")),
      el("span", { class: "play" }, "▶"));
    list.append(item);
  });
}
/* ---------- SD clip thumbnails ----------
 * EZVIZ returns no cover image for SD clips. Use, in order:
 *   1. a cached frame (localStorage),
 *   2. the snapshot of a motion alert that falls inside the clip,
 *   3. a frame grabbed by briefly playing the clip in a hidden player. */
const clipKey = (f) => `${state.device ? state.device.deviceSerial : ""}_${f.startTime}_${f.endTime}`;
function setThumb(key, src) {
  state.thumbs[key] = src;
  const t = document.querySelector(`.thumb[data-key="${CSS.escape(key)}"]`);
  if (t) t.style.backgroundImage = `url("${src}")`;
}
function cachedThumb(key) { return store.get("thumb." + key, null); }
function cacheThumb(key, dataUrl) {
  store.set("thumb." + key, dataUrl);
  // Keep the cache bounded: drop the oldest entries past 300 thumbnails.
  try {
    const keys = Object.keys(localStorage).filter((k) => k.startsWith("liza.thumb.")).sort();
    keys.slice(0, Math.max(0, keys.length - 300)).forEach((k) => localStorage.removeItem(k));
  } catch { /* storage unavailable */ }
}
function shrink(dataUrl) { // scale a captured frame to a small JPEG
  return new Promise((resolve) => {
    const img = new Image();
    img.onload = () => {
      const c = document.createElement("canvas");
      c.width = 320; c.height = Math.round(320 * img.height / img.width) || 180;
      c.getContext("2d").drawImage(img, 0, 0, c.width, c.height);
      resolve(c.toDataURL("image/jpeg", 0.72));
    };
    img.onerror = () => resolve(null);
    img.src = dataUrl;
  });
}
function cancelScan() { state.scanId += 1; }

async function loadThumbs(dayStart, dayEnd) {
  const scan = ++state.scanId;
  const files = state.recFiles.slice();
  // 1. Cache.
  for (const f of files) {
    const k = clipKey(f);
    const c = cachedThumb(k);
    if (c) setThumb(k, c);
  }
  // 2. Alert snapshots for the same day.
  let alarms = [];
  try {
    for (let page = 0; page < 6; page++) {
      const d = (await api("alarmList", { startTime: dayStart, endTime: dayEnd, status: 2, alarmType: -1, pageStart: page, pageSize: 50 })) || [];
      alarms = alarms.concat(d);
      if (d.length < 50) break;
    }
  } catch { /* fall through to frame grabs */ }
  if (scan !== state.scanId) return;
  const sn = state.device.deviceSerial;
  alarms = alarms.filter((a) => a.deviceSerial === sn && a.alarmPicUrl);
  for (const f of files) {
    const k = clipKey(f);
    if (state.thumbs[k]) continue;
    const a = alarms.find((x) => x.alarmTime >= f.startTime - 15000 && x.alarmTime <= f.endTime + 5000);
    if (a) setThumb(k, a.alarmPicUrl);
  }
  // 3. Grab frames for the rest, newest first, one at a time.
  const todo = files.filter((f) => !state.thumbs[clipKey(f)]);
  for (let i = 0; i < todo.length; i++) {
    if (scan !== state.scanId || currentTab() !== "recordings") return;
    $("#recMsg").textContent = `${files.length} recordings · newest first · preparing previews ${i + 1}/${todo.length}`;
    const src = await grabFrame(todo[i], scan);
    if (scan !== state.scanId) return;
    if (src) { cacheThumb(clipKey(todo[i]), src); setThumb(clipKey(todo[i]), src); }
  }
  if (scan === state.scanId) renderRecordings();
}

function grabFrame(f, scan) {
  return new Promise((resolve) => {
    const box = el("div", { id: "thumbGrab", style: "position:fixed;left:-4000px;top:0;width:480px;height:270px" });
    document.body.append(box);
    let player = null;
    let done = false;
    const finish = (src) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      try { player && player.destroy(); } catch { /* ignore */ }
      box.remove();
      resolve(src);
    };
    const timer = setTimeout(() => finish(null), 16000);
    state.lookupWindow = { begin: f.startTime, end: f.endTime };
    player = new EZUIKit.EZUIKitPlayer({
      id: "thumbGrab",
      accessToken: state.session.accessToken,
      url: `ezopen://open.ezviz.com/${state.device.deviceSerial}/1.rec?begin=${wallStamp(f.startTime)}&end=${wallStamp(f.endTime)}`,
      template: "simple", width: 480, height: 270,
      env: { domain: state.session.domain },
      staticPath: location.origin + "/vendor/ezuikit_static",
      handleError: () => finish(null),
      handleSuccess: () => {
        // Give the decoder a moment to show a real frame, then capture it.
        setTimeout(() => {
          if (done || scan !== state.scanId) return finish(null);
          try {
            player.closeSound && player.closeSound();
            // capturePicture(name, ?, download): the third argument must be
            // false, or the player also saves the frame as a file download.
            Promise.resolve(player.capturePicture("thumb", false, false)).then(async (res) => {
              const raw = res && ((res.data && res.data.base64) || res.base64);
              finish(raw ? await shrink(raw) : null);
            }).catch(() => finish(null));
          } catch { finish(null); }
        }, 1800);
      },
    });
  });
}

function playFile(i) {
  const f = state.recFiles[i];
  if (!f) return;
  state.recIndex = i;
  renderRecordings();
  playRecording(f.startTime, f.endTime, hhmm(f.startTime));
}

/* ---------- alerts ---------- */
$("#alertsRefresh").addEventListener("click", loadAlerts);
async function loadAlerts() {
  const grid = $("#alertList");
  grid.replaceChildren();
  $("#alertsMsg").textContent = "Loading alerts…";
  const end = wallNow();
  try {
    const alarms = (await api("alarmList", {
      startTime: end - 86400000, endTime: end, status: 2, alarmType: -1, pageStart: 0, pageSize: 50,
    })) || [];
    const mine = state.device ? alarms.filter((a) => a.deviceSerial === state.device.deviceSerial) : alarms;
    $("#alertsMsg").textContent = mine.length ? `${mine.length}${mine.length === 50 ? "+" : ""} events` : "No motion alerts in the last 24 hours.";
    for (const a of mine) {
      const thumb = el("div", { class: "thumb" });
      if (a.alarmPicUrl) thumb.style.backgroundImage = `url("${a.alarmPicUrl}")`;
      grid.append(el("button", { class: "item", onclick: () => openAlert(a) },
        thumb,
        el("div", { class: "t" }, "Motion detected"),
        el("div", { class: "s" }, wallDateTime(a.alarmTime))));
    }
  } catch (e) {
    $("#alertsMsg").textContent = "Could not load alerts. " + e.message;
  }
}
function openAlert(a) {
  if (state.device && a.deviceSerial !== state.device.deviceSerial) {
    const d = state.devices.find((x) => x.deviceSerial === a.deviceSerial);
    if (d) state.device = d;
  }
  const pre = Math.max(5, a.preTime || 5) * 1000;
  state.recIndex = -1;
  playRecording(a.alarmTime - pre - 2000, a.alarmTime + 60000, "for alert at " + hhmmss(a.alarmTime));
}

/* ---------- settings ---------- */
function setMsg(m) { $("#setMsg").textContent = m; }
async function loadSettings() {
  if (!state.device) return;
  const sn = state.device.deviceSerial;
  $("#nameInput").value = state.device.deviceName || "";
  try {
    const info = await api("deviceInfo", { deviceSerial: sn });
    Object.assign(state.device, { defence: info.defence, status: info.status, deviceName: info.deviceName });
    $("#setInfo").textContent = `${info.model || "EZVIZ camera"} · ${info.status === 1 ? "Online" : "Offline"}${info.isEncrypt ? " · Video encrypted" : ""}`;
    $("#defenceInfo").textContent = info.defence === 1 ? "Motion alerts are armed." : "Motion alerts are disarmed.";
    $("#defenceBtn").textContent = info.defence === 1 ? "Disarm door alerts" : "Arm door alerts";
    renderDevices();
  } catch (e) { setMsg("Could not load device settings. " + e.message); }
}
$("#renameBtn").addEventListener("click", async () => {
  const name = $("#nameInput").value.trim();
  if (!name || !state.device) return;
  try {
    await api("nameUpdate", { deviceSerial: state.device.deviceSerial, deviceName: name });
    state.device.deviceName = name;
    $("#deviceTitle").textContent = name;
    renderDevices();
    setMsg("Device name updated.");
  } catch (e) { setMsg("Rename failed. " + e.message); }
});
$("#defenceBtn").addEventListener("click", async () => {
  if (!state.device) return;
  const next = state.device.defence === 1 ? 0 : 1;
  if (!confirm(next ? "Arm door alerts on this camera?" : "Disarm door alerts on this camera?")) return;
  try {
    await api("defenceSet", { deviceSerial: state.device.deviceSerial, isDefence: next });
    setMsg(next ? "Door alerts armed." : "Door alerts disarmed.");
    loadSettings();
  } catch (e) { setMsg("Arm/disarm failed. " + e.message); }
});
$("#storageBtn").addEventListener("click", async () => {
  if (!state.device) return;
  $("#storageInfo").textContent = "Checking…";
  const names = { 0: "Normal", 1: "Wrong storage medium", 2: "Unformatted", 3: "Formatting" };
  try {
    const data = await api("storageStatus", { deviceSerial: state.device.deviceSerial });
    const items = (data && data.storageStatus) || [];
    $("#storageInfo").textContent = items.length
      ? items.map((s) => `SD card ${s.index}: ${names[s.status] || "Unknown (" + s.status + ")"}${s.capacity ? ` · ${(s.capacity / 1024).toFixed(0)} GB` : ""}`).join(" · ")
      : "No storage information returned.";
  } catch (e) { $("#storageInfo").textContent = "Storage check failed. " + e.message + " If the camera is asleep, wake it and retry."; }
});
$("#flipBtn").addEventListener("click", async () => {
  if (!state.device) return;
  if (!confirm("Flip the camera image 180°? This changes the camera's setting.")) return;
  try {
    await api("mirror", { deviceSerial: state.device.deviceSerial, channelNo: 1, command: 2 });
    setMsg("Flip command sent. The image may take a moment to update.");
  } catch (e) { setMsg("Flip failed. " + e.message); }
});

/* ---------- SD card reminder ----------
 * EZVIZ reports the SD card's capacity, but not how much of it is used
 * (recordings come back with fileSize 0), and the space a camera uses is not
 * proportional to clip length: on a DP2C, clip length × bitrate predicted
 * about 950 days for a 128 GB card that really filled in about 90. So Liza
 * plans with what can be observed:
 *   1. how long a full card lasts, from the user's experience, or measured:
 *   2. once a card is full the camera overwrites its oldest recordings, so
 *      if the oldest recording on the card is newer than the day the card
 *      was emptied, the card is full, and today − oldest recording is the
 *      number of days a full card holds. Liza saves that as the card's life.
 * Without either, it suggests a first check after 30 days. */
const DAY = 86400000;
const FIRST_CHECK_DAYS = 30;
const dayMs = (iso) => { const [y, m, d] = iso.split("-").map(Number); return Date.UTC(y, m - 1, d); };
const isoDay = (ms) => new Date(ms).toISOString().slice(0, 10);
const longDate = (iso) => new Date(dayMs(iso)).toLocaleDateString([], { dateStyle: "medium", timeZone: "UTC" });
const minutesText = (ms) => { const m = Math.round(ms / 60000); return m >= 120 ? `${(m / 60).toFixed(1)} hours` : `${m} min`; };

/* Recorded time in one day, with overlapping clips merged. */
function recordedMs(files, from, to) {
  const spans = files.map((f) => [Math.max(from, f.startTime), Math.min(to, f.endTime)])
    .filter(([a, b]) => b > a).sort((x, y) => x[0] - y[0]);
  let total = 0, curA = -1, curB = -1;
  for (const [a, b] of spans) {
    if (a > curB) { if (curB > curA) total += curB - curA; curA = a; curB = b; }
    else curB = Math.max(curB, b);
  }
  if (curB > curA) total += curB - curA;
  return total;
}
async function dayRecords(sn, start) {
  const end = Math.min(wallNow(), start + DAY - 1);
  return (await api("recordsByTime", { deviceSerial: sn, channelNo: 1, startTime: start, endTime: end, recType: 2 })) || [];
}

/* The day of the oldest recording still on the card: probe one day a week
 * going back, stop after three empty weeks, then find the exact day inside
 * the last week that had recordings. */
async function findOldestDay(sn, progress) {
  const today = dayMs(todayInput());
  let hit = null, misses = 0;
  for (let back = 0; back <= 400; back += 7) {
    progress(`Looking for the oldest recording on the card, ${back} days back…`);
    let n;
    try { n = (await dayRecords(sn, today - back * DAY)).length; } catch { continue; }
    if (n > 0) { hit = back; misses = 0; } else if (++misses >= (hit === null ? 5 : 3)) break;
  }
  if (hit === null) return null;
  for (let back = hit + 6; back > hit; back--) {
    progress(`Narrowing it down, ${back} days back…`);
    try { if ((await dayRecords(sn, today - back * DAY)).length) return today - back * DAY; } catch { /* skip */ }
  }
  return today - hit * DAY;
}

function sdPlan(sn) { return store.get("sdplan." + sn, null); }
function saveSdPlan(sn, plan) { if (plan) store.set("sdplan." + sn, plan); else { try { localStorage.removeItem("liza.sdplan." + sn); } catch { /* ignore */ } } }
function sdLife(sn) { const n = Number(store.get("sdlife." + sn, 0)); return n >= 1 && n <= 3650 ? n : null; }
function setSdLife(sn, days) { store.set("sdlife." + sn, days ? Math.round(days) : 0); }
function sdMsg(m) { $("#sdMsg").textContent = m; }
function sdReset() { $("#sdResult").hidden = true; state.sdEstimate = null; }

function loadSdPlan() {
  if (!state.device) return;
  const sn = state.device.deviceSerial;
  const plan = sdPlan(sn);
  const today = todayInput();
  $("#sdStart").max = today;
  $("#sdStart").value = (plan && plan.start) || today;
  $("#sdLife").value = sdLife(sn) || "";
  $("#sdCheck").min = $("#sdStart").value;
  $("#sdCheck").value = (plan && plan.check) || "";
  sdReset();
  sdMsg(plan && plan.check ? `Reminder saved for ${longDate(plan.check)}.` : "");
  updateSdBanner();
}

/* Find the oldest recording, decide whether the card is overwriting, and
 * suggest a check date. */
async function checkSd() {
  if (!state.device) return;
  const sn = state.device.deviceSerial;
  const start = $("#sdStart").value;
  if (!start) { sdMsg("Choose the date the card was emptied."); return; }
  const btn = $("#sdEstimate");
  btn.disabled = true;
  sdReset();
  try {
    sdMsg("Reading the card…");
    const st = await api("storageStatus", { deviceSerial: sn });
    const cards = ((st && st.storageStatus) || []).filter((c) => Number(c.capacity) > 0);
    if (!cards.length) { sdMsg("The camera didn't report an SD card. Check that a card is inserted, then try again."); return; }
    const capGb = cards.reduce((n, c) => n + Number(c.capacity), 0) / 1024; // capacity is in MB

    const today = dayMs(todayInput());
    const startMs = dayMs(start);
    const oldest = await findOldestDay(sn, sdMsg);

    sdMsg("Reading the last 7 days of recordings…");
    let recMs = 0, clips = 0, days = 0;
    for (let i = 1; i <= 7; i++) {
      const from = today - i * DAY;
      try { const f = await dayRecords(sn, from); recMs += recordedMs(f, from, from + DAY); clips += f.length; days += 1; }
      catch { /* skip a day the camera didn't answer */ }
    }

    const lines = [`Card size: <strong>${capGb.toFixed(capGb >= 10 ? 0 : 1)} GB</strong>, status ${cards.every((c) => String(c.status) === "0") ? "normal" : "not normal: check it in Device settings"}.`];
    if (days) lines.push(`Recorded: about ${minutesText(recMs / days)} of clips a day (${clips} clips in the last ${days} days).`);

    // Is the camera overwriting? Allow a day of slack for a quiet first day.
    const overwriting = oldest !== null && oldest > startMs + DAY;
    let life = sdLife(sn);
    if (oldest === null) {
      lines.push("No recordings found on the card.");
    } else if (overwriting) {
      const held = Math.round((today - oldest) / DAY);
      lines.push(`<strong>The card is full and overwriting.</strong> The oldest recording is from ${longDate(isoDay(oldest))}, after the card was emptied on ${longDate(start)}. A full card holds about <strong>${held} days</strong> of recordings, and Liza will use that from now on.`);
      if (held >= 1) { life = held; setSdLife(sn, held); $("#sdLife").value = held; }
    } else {
      lines.push(`The oldest recording is from ${longDate(isoDay(oldest))}, so nothing has been overwritten yet.`);
    }

    let check, full = null;
    if (overwriting) {
      check = todayInput();
    } else if (life) {
      full = isoDay(startMs + life * DAY);
      check = isoDay(Math.max(today, startMs + Math.max(1, Math.floor(life * 0.9)) * DAY));
      const pct = Math.min(100, Math.round(((today - startMs) / DAY / life) * 100));
      lines.push(full <= todayInput()
        ? `A full card lasts about ${life} days, so <strong>it is probably full by now</strong> (around ${longDate(full)}). Check it now.`
        : `A full card lasts about ${life} days: about <strong>${pct}%</strong> used, full around <strong>${longDate(full)}</strong>. Suggested check: <strong>${longDate(check)}</strong>, a little early in case the camera is busier than usual.`);
    } else {
      check = isoDay(Math.max(today, startMs + FIRST_CHECK_DAYS * DAY));
      lines.push(`Liza can't tell how full the card is yet: EZVIZ doesn't report free space, and the space a camera uses isn't proportional to clip length. Enter how many days a full card lasts if you know, or check on <strong>${longDate(check)}</strong>. On that day press this button again: if the camera has started overwriting, Liza measures how many days the card holds and plans from then on.`);
    }

    $("#sdResult").innerHTML = lines.map((l) => `<div>${l}</div>`).join("");
    $("#sdResult").hidden = false;
    $("#sdCheck").value = check;
    state.sdEstimate = { start, full, check };
    sdMsg(overwriting ? "Empty the card, set today as the emptied date, then check again to plan the next reminder." : "Adjust the check date if you like, then save the reminder.");
  } catch (e) {
    sdMsg("Check failed. " + e.message + " If the camera is asleep, press the doorbell and try again.");
  } finally { btn.disabled = false; }
}

function saveSdReminder() {
  if (!state.device) return null;
  const start = $("#sdStart").value, check = $("#sdCheck").value;
  if (!start || !check) { sdMsg("Choose both dates first, or press Check the card for a suggestion."); return null; }
  if (check < start) { sdMsg("The check date must be after the date the card was emptied."); return null; }
  const est = state.sdEstimate && state.sdEstimate.start === start ? state.sdEstimate : null;
  const plan = { start, check, full: est ? est.full : null, saved: Date.now() };
  saveSdPlan(state.device.deviceSerial, plan);
  sdMsg(`Reminder saved for ${longDate(check)}. Liza shows it when you open it on or after that day. Add it to your calendar to be reminded even when Liza is closed.`);
  updateSdBanner();
  return plan;
}

function downloadIcs() {
  const plan = saveSdReminder();
  if (!plan) return;
  const name = (state.device.deviceName || "camera").replace(/[\r\n]/g, " ");
  const esc = (t) => t.replace(/[\\,;]/g, (c) => "\\" + c).replace(/\n/g, "\\n");
  const d = plan.check.replaceAll("-", "");
  const stamp = new Date().toISOString().replace(/[-:]/g, "").replace(/\.\d+/, "");
  const uid = (crypto.randomUUID ? crypto.randomUUID() : String(Date.now())) + "@liza.local";
  const desc = `The SD card in ${name} was emptied on ${longDate(plan.start)}.`
    + (plan.full ? ` Liza estimated it fills around ${longDate(plan.full)}.` : "")
    + " When the card is full the camera overwrites its oldest recordings. Open Liza > Device settings > SD card reminder and press Check the card.";
  const ics = [
    "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Liza//SD card reminder//EN", "CALSCALE:GREGORIAN",
    "BEGIN:VEVENT", "UID:" + uid, "DTSTAMP:" + stamp,
    `DTSTART:${d}T090000`, `DTEND:${d}T091500`,
    "SUMMARY:" + esc(`Check the SD card in ${name}`),
    "DESCRIPTION:" + esc(desc),
    "BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:" + esc(`Check the SD card in ${name}`), "TRIGGER:PT0M", "END:VALARM",
    "END:VEVENT", "END:VCALENDAR",
  ].map((l) => l.replace(/(.{74})(?=.)/g, "$1\r\n ")).join("\r\n") + "\r\n"; // fold long lines (RFC 5545)
  const url = URL.createObjectURL(new Blob([ics], { type: "text/calendar" }));
  const a = el("a", { href: url, download: "Liza-SD-card-check.ics" });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 5000);
}

function updateSdBanner() {
  const plan = state.device && sdPlan(state.device.deviceSerial);
  const due = !!(plan && plan.check && plan.check <= todayInput());
  $("#sdBanner").hidden = !due;
  if (!due) return;
  $("#sdBannerText").textContent = `Time to check the SD card in ${state.device.deviceName || "this camera"}. It was emptied on ${longDate(plan.start)}`
    + (plan.full ? ` and is estimated to fill around ${longDate(plan.full)}.` : ".");
}

function openSdCard() { showTab("settings"); $("#sdCard").scrollIntoView({ behavior: "smooth", block: "start" }); }
$("#sdStart").addEventListener("change", () => { $("#sdCheck").min = $("#sdStart").value; sdReset(); });
$("#sdLife").addEventListener("change", () => {
  if (!state.device) return;
  const n = Number($("#sdLife").value);
  setSdLife(state.device.deviceSerial, n >= 1 && n <= 3650 ? n : 0);
  sdReset();
});
$("#sdEstimate").addEventListener("click", checkSd);
$("#sdFind").addEventListener("click", async () => {
  if (!state.device) return;
  $("#sdFind").disabled = true;
  try {
    const day = await findOldestDay(state.device.deviceSerial, sdMsg);
    if (day === null) { sdMsg("No recordings found on the card in the last 400 days. If the camera is asleep, press the doorbell and try again."); return; }
    $("#sdStart").value = isoDay(day);
    $("#sdCheck").min = isoDay(day);
    sdReset();
    sdMsg(`The oldest recording on the card is from ${longDate(isoDay(day))}. If you emptied the card that day, keep this date and press Check the card.`);
  } finally { $("#sdFind").disabled = false; }
});
$("#sdSave").addEventListener("click", saveSdReminder);
$("#sdIcs").addEventListener("click", downloadIcs);
$("#sdClear").addEventListener("click", () => {
  if (!state.device) return;
  saveSdPlan(state.device.deviceSerial, null);
  $("#sdCheck").value = "";
  sdMsg("Reminder removed.");
  updateSdBanner();
});
$("#sdBannerOpen").addEventListener("click", openSdCard);
$("#sdBannerDone").addEventListener("click", () => {
  if (!state.device) return;
  const sn = state.device.deviceSerial;
  const old = sdPlan(sn);
  // If the card was full when emptied, the time since it was last emptied is (at most) its life.
  if (old && old.start && confirm("Was the card full when you emptied it? Liza uses the time since it was last emptied to plan the next reminder.")) {
    const days = Math.round((dayMs(todayInput()) - dayMs(old.start)) / DAY);
    if (days >= 1) setSdLife(sn, days);
  }
  saveSdPlan(sn, { start: todayInput(), check: null, full: null, saved: Date.now() });
  loadSdPlan();
  openSdCard();
  sdMsg("Marked as emptied today. Press Check the card to plan the next reminder.");
});

/* ---------- tabs ---------- */
function currentTab() { return ($(".tab.active") || {}).dataset?.tab || "live"; }
function showTab(name) {
  $$(".tab").forEach((t) => t.classList.toggle("active", t.dataset.tab === name));
  $$("[data-panel]").forEach((p) => { p.hidden = p.dataset.panel !== name; });
  store.set("tab", name);
  if (name === "alerts") loadAlerts();
  if (name === "settings") loadSettings();
  if (name === "recordings") {
    if (!state.recFiles.length) searchRecordings();
    else if (state.recDay && state.recFiles.some((f) => !state.thumbs[clipKey(f)])) loadThumbs(state.recDay.start, state.recDay.end);
  }
  if (name === "live" && state.mode !== "live") startLive();
}
$$(".tab").forEach((t) => t.addEventListener("click", () => showTab(t.dataset.tab)));
$("#refreshDevices").addEventListener("click", loadDevices);

/* Keep the player sized to its frame. */
/* Fit the player inside its frame at 16:9 (letterboxed), so full screen on a
 * differently shaped window doesn't stretch the picture. */
function fitPlayer() {
  const w = $("#playerWrap");
  const W = w.clientWidth, H = w.clientHeight;
  if (!W || !H) return;
  const ratio = 16 / 9;
  const pw = Math.min(W, Math.round(H * ratio));
  const ph = Math.min(H, Math.round(W / ratio));
  Object.assign($("#player").style, { width: pw + "px", height: ph + "px",
    left: Math.round((W - pw) / 2) + "px", top: Math.round((H - ph) / 2) + "px", right: "auto", bottom: "auto" });
  if (state.player && state.player.resize) { try { state.player.resize(pw, ph); } catch { /* ignore */ } }
}
new ResizeObserver(fitPlayer).observe($("#playerWrap"));

/* Release the camera when the tab is hidden for a while (saves the doorbell's battery). */
document.addEventListener("visibilitychange", () => {
  clearTimeout(state.hideTimer);
  if (document.hidden) state.hideTimer = setTimeout(() => { if (document.hidden) { destroyPlayer(); state.mode = "idle"; $("#playerIdle").hidden = false; $("#playerIdle").textContent = "Paused while the tab was hidden. Click Live view to resume."; } }, 60000);
});

/* ---------- boot ---------- */
async function boot() {
  const cfg = await getConfig().catch(() => null);
  if (!cfg) { document.body.textContent = "Can't reach the Liza server. Is it running?"; return; }
  if (!cfg.configured) return showSetup();
  const s = await fetch("/api/session").then((r) => r.json()).catch(() => ({}));
  if (!s.signedIn) {
    if (cfg.hasSecret) { showSetup(); $("#setupForm .form-msg").textContent = "EZVIZ didn't accept the saved AppSecret. Check it, or clear it to sign in instead."; return; }
    return showSignIn();
  }
  state.session = s;
  $("#setup").hidden = true;
  $("#signin").hidden = true;
  $("#app").hidden = false;
  renderConnInfo();
  $("#sessionInfo").textContent = s.method === "appSecret"
    ? "Connected with AppSecret · renews automatically"
    : "Signed in · session until " + new Date(s.expires).toLocaleDateString();
  syncButtons();
  await loadDevices();
  const tab = store.get("tab", "live");
  if (tab !== "live") showTab(tab);
}
boot();
