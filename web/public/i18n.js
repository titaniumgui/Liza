/* Liza web — translations.
 * English text stays in the code and is the lookup key. Other languages live
 * in /i18n/<lang>.json as { "English text": "translation" }. Placeholders are
 * {0}, {1}, … Run `python tools/check_i18n.py` to find untranslated strings. */
"use strict";

const LANGS = { en: { name: "English", dir: "ltr" }, ar: { name: "العربية", dir: "rtl" } };
// Arabic dates keep Western digits, to match the camera's own timestamps.
const LOCALES = { en: undefined, ar: "ar-u-nu-latn" };
const I18N = { lang: "en", locale: undefined, dict: {}, patterns: null };

function t(en, ...args) {
  const s = (I18N.lang !== "en" && I18N.dict[en]) || en;
  return args.length ? s.replace(/\{(\d+)\}/g, (m, i) => (args[i] ?? m)) : s;
}

/* Messages from the Liza server are English. Translate exact matches, and
 * messages that fill one of the dictionary's {0} templates. */
function tServer(msg) {
  if (!msg || I18N.lang === "en") return msg || "";
  if (I18N.dict[msg]) return I18N.dict[msg];
  if (!I18N.patterns) {
    const esc = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    I18N.patterns = Object.keys(I18N.dict).filter((k) => /\{\d\}/.test(k))
      .map((k) => [new RegExp("^" + esc(k).replace(/\\\{\d\\\}/g, "(.*?)") + "$"), k]);
  }
  for (const [re, key] of I18N.patterns) {
    const m = msg.match(re);
    if (m) return t(key, ...m.slice(1));
  }
  return msg;
}

/* Translate the page's static text. The English original is kept in a data
 * attribute, so this can run again. */
function applyStatic(root = document) {
  const each = (sel, fn) => root.querySelectorAll(sel).forEach(fn);
  each("[data-i18n]", (e) => { e.dataset.en ??= e.textContent.trim(); e.textContent = t(e.dataset.en); });
  each("[data-i18n-html]", (e) => { e.dataset.enHtml ??= e.innerHTML.trim().replace(/\s+/g, " "); e.innerHTML = t(e.dataset.enHtml); });
  each("[data-i18n-ph]", (e) => { e.dataset.enPh ??= e.placeholder; e.placeholder = t(e.dataset.enPh); });
  each("[data-i18n-title]", (e) => { e.dataset.enTitle ??= e.title; e.title = t(e.dataset.enTitle); });
  each("[data-i18n-aria]", (e) => { e.dataset.enAria ??= e.getAttribute("aria-label"); e.setAttribute("aria-label", t(e.dataset.enAria)); });
  // Each language switch offers the other language, named in that language.
  const other = I18N.lang === "ar" ? "en" : "ar";
  each(".lang-switch", (e) => { e.textContent = LANGS[other].name; e.lang = other; e.dataset.lang = other; });
  each(".lang-set", (e) => e.classList.toggle("accent", e.dataset.lang === I18N.lang));
}

async function loadLang(lang) {
  I18N.lang = LANGS[lang] ? lang : "en";
  I18N.locale = LOCALES[I18N.lang];
  I18N.patterns = null;
  I18N.dict = I18N.lang === "en" ? {}
    : await fetch(`/i18n/${I18N.lang}.json`).then((r) => r.json()).catch(() => ({}));
  document.documentElement.lang = I18N.lang;
  document.documentElement.dir = LANGS[I18N.lang].dir;
  applyStatic();
}
