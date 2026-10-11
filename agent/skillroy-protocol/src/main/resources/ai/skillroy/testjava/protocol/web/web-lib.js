/*
 * Copyright 2026 Mike Mills.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * The script library of the web verbs (phase4-contracts.md sections 4.2, 4.3 and 4.6; phase7-contracts.md section 4.6).
 * Both web paths evaluate this one text, the agent through the embedded browser's own channel and the CDP path through
 * Playwright, so that a verb returns the same shape on either path for the same page; WebLib.hash() names the version
 * in run.jsonl.
 *
 * Evaluating the text installs the library under window[Symbol.for("skillroy")] (non-enumerable, non-writable) and
 * yields "installed"; when the slot already holds this version or a later one it changes nothing, not even an armed
 * tap, and yields "present". A slot that holds an older library is never replaced (a replacement would lose a tap's
 * buffer): the functions it lacks are installed into it, once per document, its version is raised where it can be,
 * and the text yields "upgraded" (D300). Nothing else is added to the window: the tap, its buffer and deferred results
 * live in this closure. The library is per document; a frame has its own.
 *
 * Functions of the library object (WebLib's Javadoc gives every shape): stringify, aria, find, click, fill, text,
 * domClick, domFill, domText, dispatch, tap, drain, defer, collect, hit (version 2); and the string L.version. A
 * failure the caller
 * can act on is returned, never thrown, as {__error: "NOT_FOUND" | "AMBIGUOUS" | "INVALID_PARAMS", data: {...}};
 * the Java side of both paths maps it to the RPC error of that name. What is thrown comes from the page.
 *
 * Form, checked by WebLibTest: one strict IIFE expression without a trailing semicolon (WebLib parenthesises it),
 * ASCII only, no template or regular-expression literal and no division (the test's lexer strips comments and
 * strings only), and every Object.defineProperty with an inline "enumerable: false" except the one restore of a
 * descriptor the tap saved.
 */
(function () {
  "use strict";

  var KEY = Symbol.for("skillroy");
  var VERSION = "2";
  // The functions version 2 added (D300): a slot that holds a version-1 library gets them installed into it.
  var ADDED_IN_2 = ["hit"];
  var slot = window[KEY];
  if (slot && missingIn(slot).length === 0) {
    return "present";
  }

  var MAX_JSON = 1048576;         // stringify: the inner JSON text is cut here (1 MiB in UTF-16 code units)
  var MAX_DETAIL = 65536;         // tap: the JSON text of an event's detail is cut here (64 KiB)
  var MAX_EVENTS = 1000;          // tap: events buffered between drains; the oldest is dropped and counted
  var MAX_CANDIDATES = 20;        // find: candidates listed beside the full count
  var MAX_DEFERRED = 100;         // defer: results kept until collected; the oldest is forgotten
  var DEFAULT_MAX_NODES = 2000;   // aria
  var VISITS_PER_NODE = 100;      // aria: elements visited per node allowed, so that pruning stays bounded
  var DEFAULT_MAX_CHARS = 10000;  // text, domText
  var MAX_HIT_TEXT = 1000;        // hit: an element's name and id are cut here (the receiving side cuts again)
  var MAX_SELECTOR_STEPS = 8;     // hit: the steps of a selector path, from the element up
  var MAX_FRAME_URL = 4096;       // hit: a frame element's URL longer than this is not reported

  // Captured at install, so that a page that later replaces them does not change the library's results.
  var jsonStringify = JSON.stringify;
  var jsonParse = JSON.parse;
  var NativePromise = Promise;
  var NativeMap = Map;

  var WS = new RegExp("\\s+", "g");
  var INVISIBLE = new RegExp("[\\u200b\\u00ad]", "g");
  var GLOB_SPECIAL = new RegExp("[.+?^${}()|[\\]\\\\]", "g");
  var STAR = new RegExp("\\*", "g");
  var PLAIN_IDENT = new RegExp("^[A-Za-z_][A-Za-z0-9_-]*$");
  var BREAK = {};                 // aria: the boundary of a block-level box in a list of child items

  function setOf(names) {
    var set = Object.create(null);
    for (var i = 0; i < names.length; i++) {
      set[names[i]] = true;
    }
    return set;
  }

  function mapOf(pairs) {
    var map = Object.create(null);
    for (var i = 0; i < pairs.length; i += 2) {
      map[pairs[i]] = pairs[i + 1];
    }
    return map;
  }

  // The ARIA 1.2 roles an author may write ("image" is the ARIA 1.3 synonym of "img"); abstract roles are ignored.
  var ROLES = setOf(("alert alertdialog application article banner blockquote button caption cell checkbox code "
      + "columnheader combobox complementary contentinfo definition deletion dialog directory document emphasis "
      + "feed figure form generic grid gridcell group heading img image insertion link list listbox listitem log "
      + "main mark marquee math menu menubar menuitem menuitemcheckbox menuitemradio meter navigation none note "
      + "option paragraph presentation progressbar radio radiogroup region row rowgroup rowheader scrollbar "
      + "search searchbox separator slider spinbutton status strong subscript superscript switch tab table "
      + "tablist tabpanel term textbox time timer toolbar tooltip tree treegrid treeitem").split(" "));
  // Never rendered: neither a node nor text, and not searched.
  var SKIPPED = setOf(["script", "style", "noscript", "template", "head", "title", "desc", "meta", "link", "base"]);
  // Rendered without rendered children: their child nodes are not walked.
  var LEAVES = setOf(["iframe", "frame", "object", "embed", "video", "audio", "img", "input", "textarea", "hr"]);
  // Dropped by interestingOnly, their children taking their place.
  var PRUNED = setOf(["generic", "none", "presentation"]);
  var FROM_CONTENT = setOf(["button", "cell", "checkbox", "columnheader", "gridcell", "heading", "link", "menuitem",
      "menuitemcheckbox", "menuitemradio", "option", "radio", "row", "rowheader", "switch", "tab", "tooltip",
      "treeitem"]);
  var CHECKABLE = setOf(["checkbox", "radio", "switch", "menuitemcheckbox", "menuitemradio"]);
  var VALUED = setOf(["textbox", "searchbox", "spinbutton", "combobox", "slider", "progressbar", "meter",
      "scrollbar"]);
  // Roles that an aria-disabled ancestor disables as well (the widget roles).
  var WIDGETS = setOf(["application", "button", "checkbox", "columnheader", "combobox", "grid", "gridcell", "group",
      "link", "listbox", "menu", "menubar", "menuitem", "menuitemcheckbox", "menuitemradio", "option", "radio",
      "radiogroup", "row", "rowheader", "scrollbar", "searchbox", "separator", "slider", "spinbutton", "switch",
      "tab", "tablist", "textbox", "toolbar", "tree", "treegrid", "treeitem"]);
  // Block-level when no computed style can be read.
  var BLOCKS = setOf(["address", "article", "aside", "blockquote", "caption", "dd", "details", "dialog", "div", "dl",
      "dt", "fieldset", "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr",
      "li", "main", "nav", "ol", "option", "p", "pre", "section", "summary", "table", "tbody", "td", "tfoot", "th",
      "thead", "tr", "ul"]);
  var LEVELS = mapOf(["h1", 1, "h2", 2, "h3", 3, "h4", 4, "h5", 5, "h6", 6]);
  var INPUT_ROLES = mapOf(["button", "button", "checkbox", "checkbox", "file", "button", "image", "button",
      "number", "spinbutton", "radio", "radio", "range", "slider", "reset", "button", "submit", "button"]);
  var PLACEHOLDER_TYPES = setOf(["text", "search", "email", "tel", "url", "password", "number"]);
  var FILLABLE_TYPES = setOf(["text", "search", "email", "tel", "url", "password", "number", "date",
      "datetime-local", "month", "time", "week", "color", "range"]);

  // ---- small helpers ----

  // The names of ADDED_IN_2 that the slot's library lacks; a slot whose members cannot even be read lacks none (it is
  // left as it is).
  function missingIn(target) {
    var out = [];
    for (var i = 0; i < ADDED_IN_2.length; i++) {
      var present;
      try {
        present = typeof target[ADDED_IN_2[i]] === "function";
      } catch (e) {
        present = true;
      }
      if (!present) {
        out.push(ADDED_IN_2[i]);
      }
    }
    return out;
  }

  function err(code, data) {
    return {__error: code, data: data};
  }

  function isError(value) {
    return value !== null && typeof value === "object" && typeof value.__error === "string";
  }

  function messageOf(e) {
    try {
      return e !== null && typeof e === "object" && typeof e.message === "string" ? e.message : String(e);
    } catch (ignored) {
      return "unprintable error";
    }
  }

  function stackOf(e) {
    try {
      return e !== null && typeof e === "object" && typeof e.stack === "string" ? e.stack : "";
    } catch (ignored) {
      return "";
    }
  }

  // Trimmed, whitespace runs collapsed to one space, zero-width space and soft hyphen removed.
  function collapse(text) {
    return text === undefined || text === null ? "" : String(text).replace(INVISIBLE, "").replace(WS, " ").trim();
  }

  // The first max code units of a longer text, one fewer when that would split a surrogate pair.
  function cut(text, max) {
    var end = max;
    var last = text.charCodeAt(end - 1);
    if (last >= 0xd800 && last <= 0xdbff) {
      end--;
    }
    return text.slice(0, end);
  }

  function positiveInt(value, fallback) {
    return typeof value === "number" && value >= 1 && value < Infinity ? Math.floor(value) : fallback;
  }

  function attr(el, name) {
    return el.getAttribute(name);
  }

  function isElement(value) {
    return value !== null && typeof value === "object" && value.nodeType === 1
        && typeof value.getAttribute === "function";
  }

  function isNode(value) {
    try {
      if (typeof Node === "function" && value instanceof Node) {
        return true;
      }
      return typeof value.nodeType === "number" && typeof value.nodeName === "string"
          && typeof value.cloneNode === "function";
    } catch (e) {
      return false;
    }
  }

  function isWindow(value) {
    try {
      return value === window || (value.window === value && value.self === value);
    } catch (e) {
      return true; // a cross-origin window refuses most reads
    }
  }

  function viewOf(node) {
    var doc = node.nodeType === 9 ? node : node.ownerDocument;
    return (doc && doc.defaultView) || window;
  }

  function typeOf(el) {
    return String(el.type || "").toLowerCase();
  }

  // ---- stringify (the web.evaluate wrapper of section 4.2) ----

  function nodeSummary(node) {
    var className = node.className;
    if (typeof className !== "string") { // an SVG element's className is an SVGAnimatedString
      className = typeof node.getAttribute === "function" ? node.getAttribute("class") || "" : "";
    }
    return {nodeName: String(node.nodeName), id: typeof node.id === "string" ? node.id : "", className: className};
  }

  // A fresh replacer per call: undefined becomes null, functions, symbols (and bigints, which JSON.stringify would
  // refuse) their String(), windows their String(), DOM nodes {nodeName, id, className}, and a value that is its
  // own ancestor "[cycle]". A value seen twice on different branches is not a cycle and is written twice.
  function jsonReplacer() {
    var ancestors = [];
    return function (key, value) {
      while (ancestors.length > 0 && ancestors[ancestors.length - 1] !== this) {
        ancestors.pop();
      }
      if (value === undefined) {
        return null;
      }
      var type = typeof value;
      if (type === "function" || type === "symbol" || type === "bigint") {
        return String(value);
      }
      if (type !== "object" || value === null) {
        return value;
      }
      if (isWindow(value)) {
        return String(value);
      }
      var out = isNode(value) ? nodeSummary(value) : value;
      if (ancestors.indexOf(out) >= 0) {
        return "[cycle]";
      }
      ancestors.push(out);
      return out;
    };
  }

  function stringify(value) {
    var json = jsonStringify(value, jsonReplacer());
    if (typeof json !== "string") {
      json = "null";
    }
    var truncated = json.length > MAX_JSON;
    if (truncated) {
      json = cut(json, MAX_JSON);
    }
    return jsonStringify({json: json, typeofResult: typeof value, truncated: truncated});
  }

  // ---- the walk context: computed styles read once per call ----

  function deepActive(doc) {
    var active = doc.activeElement;
    while (active && active.shadowRoot && active.shadowRoot.activeElement) {
      active = active.shadowRoot.activeElement;
    }
    return active;
  }

  function newContext(doc) {
    var styles = new NativeMap();
    return {
      doc: doc,
      active: deepActive(doc),
      style: function (el) {
        var style = styles.get(el);
        if (style === undefined) {
          var computed = null;
          try {
            var view = el.ownerDocument && el.ownerDocument.defaultView;
            computed = view ? view.getComputedStyle(el) : null;
          } catch (e) {
            computed = null;
          }
          style = computed ? {display: computed.display, visibility: computed.visibility}
              : {display: "", visibility: "visible"};
          styles.set(el, style);
        }
        return style;
      }
    };
  }

  // The rendered children: a shadow host's (open) shadow root, a slot's assigned nodes, else the child nodes.
  function childrenOf(node) {
    if (node.shadowRoot) {
      return node.shadowRoot.childNodes;
    }
    if (node.localName === "slot" && typeof node.assignedNodes === "function") {
      var assigned = node.assignedNodes({flatten: true});
      if (assigned.length > 0) {
        return assigned;
      }
    }
    return node.childNodes;
  }

  function parentOf(node) {
    if (node.assignedSlot) {
      return node.assignedSlot;
    }
    var parent = node.parentNode;
    return parent && parent.nodeType === 11 && parent.host ? parent.host : parent;
  }

  function isBlock(el, ctx) {
    if (el.localName === "br") {
      return true;
    }
    var display = ctx.style(el).display;
    if (!display) {
      return BLOCKS[el.localName] === true;
    }
    return display !== "contents" && display.indexOf("inline") !== 0;
  }

  function invisible(el, ctx) {
    var visibility = ctx.style(el).visibility;
    return visibility === "hidden" || visibility === "collapse";
  }

  function inClosedDetails(el) {
    var parent = el.parentNode;
    if (!parent || parent.localName !== "details" || parent.hasAttribute("open")) {
      return false;
    }
    if (el.localName !== "summary") {
      return true;
    }
    for (var child = parent.firstElementChild; child; child = child.nextElementSibling) {
      if (child.localName === "summary") {
        return child !== el;
      }
    }
    return true;
  }

  // Excluded with its whole subtree: never rendered, aria-hidden, display:none, or a closed details' content.
  function excluded(el, ctx) {
    var tag = el.localName;
    if (SKIPPED[tag] || (tag === "input" && typeOf(el) === "hidden")) {
      return true;
    }
    if (attr(el, "aria-hidden") === "true") {
      return true;
    }
    return ctx.style(el).display === "none" || inClosedDetails(el);
  }

  function hiddenForAria(el, ctx) {
    if (invisible(el, ctx)) {
      return true;
    }
    for (var node = el; node && node.nodeType === 1; node = parentOf(node)) {
      if (excluded(node, ctx)) {
        return true;
      }
    }
    return false;
  }

  // ---- roles ----

  function isFocusable(el) {
    if (attr(el, "tabindex") !== null) {
      return true;
    }
    var tag = el.localName;
    if (tag === "a" || tag === "area") {
      return el.hasAttribute("href");
    }
    if (tag === "button" || tag === "select" || tag === "textarea") {
      return !el.disabled;
    }
    if (tag === "input") {
      return typeOf(el) !== "hidden" && !el.disabled;
    }
    return el.isContentEditable === true;
  }

  function explicitRole(el) {
    var value = attr(el, "role");
    if (!value) {
      return null;
    }
    var tokens = collapse(value).toLowerCase().split(" ");
    for (var i = 0; i < tokens.length; i++) {
      var role = tokens[i];
      if (!ROLES[role]) {
        continue;
      }
      if ((role === "none" || role === "presentation")
          && (isFocusable(el) || attr(el, "aria-label") !== null || attr(el, "aria-labelledby") !== null)) {
        return null; // the presentational-role conflict: the element keeps its own role
      }
      return role === "image" ? "img" : role;
    }
    return null;
  }

  function withinSection(el) {
    for (var parent = el.parentElement; parent; parent = parent.parentElement) {
      var tag = parent.localName;
      if (tag === "article" || tag === "aside" || tag === "main" || tag === "nav" || tag === "section") {
        return true;
      }
      var role = explicitRole(parent);
      if (role === "article" || role === "complementary" || role === "main" || role === "navigation"
          || role === "region") {
        return true;
      }
    }
    return false;
  }

  function hasAuthorName(el, ctx) {
    if (collapse(attr(el, "aria-label")) || collapse(attr(el, "title"))) {
      return true;
    }
    var ids = attr(el, "aria-labelledby");
    return ids !== null && collapse(labelledbyText(el, ids, ctx)) !== "";
  }

  function svgTitle(el) {
    for (var child = el.firstElementChild; child; child = child.nextElementSibling) {
      if (child.localName === "title") {
        return child.textContent || "";
      }
    }
    return "";
  }

  function inGrid(cell) {
    var table = typeof cell.closest === "function" ? cell.closest("table") : null;
    var role = table ? explicitRole(table) : null;
    return role === "grid" || role === "treegrid";
  }

  function headerCellRole(th) {
    var scope = collapse(attr(th, "scope")).toLowerCase();
    if (scope === "col" || scope === "colgroup") {
      return "columnheader";
    }
    if (scope === "row" || scope === "rowgroup") {
      return "rowheader";
    }
    var row = th.parentElement;
    if (row && row.parentElement && row.parentElement.localName === "thead") {
      return "columnheader";
    }
    // A header cell followed only by data cells heads its row; any other heads its column.
    var following = 0;
    for (var cell = th.nextElementSibling; cell; cell = cell.nextElementSibling) {
      if (cell.localName === "th") {
        return "columnheader";
      }
      if (cell.localName === "td") {
        following++;
      }
    }
    return following > 0 ? "rowheader" : "columnheader";
  }

  function inputRole(el) {
    var type = typeOf(el) || "text";
    if (type === "hidden") {
      return null;
    }
    var list = el.list;
    var hasList = list === undefined ? el.hasAttribute("list") : !!list;
    if (type === "search") {
      return hasList ? "combobox" : "searchbox";
    }
    if (type === "text" || type === "email" || type === "tel" || type === "url") {
      return hasList ? "combobox" : "textbox";
    }
    return INPUT_ROLES[type] || "textbox";
  }

  function implicitRole(el, ctx) {
    var tag = el.localName;
    switch (tag) {
      case "a":
      case "area":
        return el.hasAttribute("href") ? "link" : null;
      case "article":
        return "article";
      case "aside":
        return "complementary";
      case "blockquote":
        return "blockquote";
      case "button":
        return "button";
      case "caption":
        return "caption";
      case "datalist":
        return "listbox";
      case "dd":
        return "definition";
      case "details":
      case "fieldset":
      case "optgroup":
      case "address":
      case "hgroup":
        return "group";
      case "dfn":
      case "dt":
        return "term";
      case "dialog":
        return "dialog";
      case "figure":
        return "figure";
      case "footer":
        return withinSection(el) ? null : "contentinfo";
      case "header":
        return withinSection(el) ? null : "banner";
      case "form":
        return hasAuthorName(el, ctx) ? "form" : null;
      case "section":
        return hasAuthorName(el, ctx) ? "region" : null;
      case "h1":
      case "h2":
      case "h3":
      case "h4":
      case "h5":
      case "h6":
        return "heading";
      case "hr":
        return "separator";
      case "iframe":
        return "iframe";
      case "img":
        return attr(el, "alt") === "" && attr(el, "aria-label") === null && attr(el, "aria-labelledby") === null
            ? "none" : "img";
      case "input":
        return inputRole(el);
      case "li":
        return "listitem";
      case "main":
        return "main";
      case "math":
        return "math";
      case "menu":
      case "ol":
      case "ul":
        return "list";
      case "meter":
        return "meter";
      case "nav":
        return "navigation";
      case "option":
        return "option";
      case "output":
        return "status";
      case "p":
        return "paragraph";
      case "progress":
        return "progressbar";
      case "search":
        return "search";
      case "select":
        return el.multiple || el.size > 1 ? "listbox" : "combobox";
      case "svg":
        return hasAuthorName(el, ctx) || collapse(svgTitle(el)) ? "img" : null;
      case "table":
        return "table";
      case "tbody":
      case "thead":
      case "tfoot":
        return "rowgroup";
      case "td":
        return inGrid(el) ? "gridcell" : "cell";
      case "th":
        return headerCellRole(el);
      case "textarea":
        return "textbox";
      case "tr":
        return "row";
      default:
        return null;
    }
  }

  function roleOf(el, ctx) {
    return explicitRole(el) || implicitRole(el, ctx) || "generic";
  }

  // ---- accessible names: a working subset of accname 1.2 ----

  function optionText(option) {
    var label = option.label;
    return collapse(typeof label === "string" ? label : option.textContent);
  }

  function selectedText(select) {
    var parts = [];
    var options = select.options || [];
    for (var i = 0; i < options.length; i++) {
      if (options[i].selected) {
        parts.push(optionText(options[i]));
      }
    }
    return parts.join(", ");
  }

  function buttonValue(el, type) {
    if (el.hasAttribute("value")) {
      return attr(el, "value");
    }
    return type === "submit" ? "Submit" : type === "reset" ? "Reset" : "";
  }

  // What an embedded control contributes to the name of something that contains or references it, or null.
  function embeddedValue(el) {
    var tag = el.localName;
    if (tag === "input") {
      var type = typeOf(el) || "text";
      if (type === "hidden" || type === "checkbox" || type === "radio" || type === "file" || type === "image") {
        return null;
      }
      if (type === "button" || type === "submit" || type === "reset") {
        return buttonValue(el, type);
      }
      return type === "password" ? "" : String(el.value);
    }
    if (tag === "textarea") {
      return String(el.value);
    }
    if (tag === "select") {
      return selectedText(el);
    }
    return null;
  }

  // The text of el's rendered content, each child by its own text alternative; exclude is skipped (the control a
  // label names); block-level children are set apart by spaces.
  function contentOf(el, ctx, exclude, includeHidden, depth) {
    var out = "";
    var kids = childrenOf(el);
    for (var i = 0; i < kids.length; i++) {
      var kid = kids[i];
      if (kid.nodeType === 3) {
        out += kid.nodeValue;
      } else if (kid.nodeType === 1 && kid !== exclude && !SKIPPED[kid.localName]) {
        if (!includeHidden && (excluded(kid, ctx) || invisible(kid, ctx))) {
          continue;
        }
        var part = alternativeOf(kid, ctx, exclude, includeHidden, depth + 1);
        out += isBlock(kid, ctx) ? " " + part + " " : part;
      }
    }
    return out;
  }

  function alternativeOf(el, ctx, exclude, includeHidden, depth) {
    if (depth > 64) {
      return "";
    }
    var label = attr(el, "aria-label");
    if (label !== null && collapse(label)) {
      return label;
    }
    var embedded = embeddedValue(el);
    if (embedded !== null) {
      return embedded;
    }
    var tag = el.localName;
    if ((tag === "img" || tag === "area") && attr(el, "alt") !== null) {
      return attr(el, "alt");
    }
    var content = contentOf(el, ctx, exclude, includeHidden, depth);
    if (collapse(content)) {
      return content;
    }
    var title = attr(el, "title");
    return title !== null ? title : "";
  }

  function labelledbyText(el, ids, ctx) {
    var root = typeof el.getRootNode === "function" ? el.getRootNode() : el.ownerDocument;
    if (typeof root.getElementById !== "function") {
      root = el.ownerDocument;
    }
    var list = collapse(ids).split(" ");
    var parts = [];
    for (var i = 0; i < list.length; i++) {
      var ref = list[i] ? root.getElementById(list[i]) : null;
      if (ref) {
        var label = attr(ref, "aria-label");
        var embedded = embeddedValue(ref);
        var text = label !== null && collapse(label) ? label
            : embedded !== null ? embedded
            : contentOf(ref, ctx, null, hiddenForAria(ref, ctx), 0);
        if (!collapse(text) && attr(ref, "title") !== null) {
          text = attr(ref, "title");
        }
        parts.push(collapse(text));
      }
    }
    return parts.join(" ");
  }

  function labelsText(el, ctx) {
    var labels = el.labels;
    if (!labels || labels.length === 0) {
      return "";
    }
    var parts = [];
    for (var i = 0; i < labels.length; i++) {
      parts.push(collapse(contentOf(labels[i], ctx, el, false, 0)));
    }
    return parts.join(" ");
  }

  function childText(el, tag, ctx) {
    for (var child = el.firstElementChild; child; child = child.nextElementSibling) {
      if (child.localName === tag) {
        return contentOf(child, ctx, null, false, 0);
      }
    }
    return "";
  }

  // The element's own naming source: associated labels, button values, alt, legend, caption, figcaption, SVG title.
  function nativeName(el, ctx) {
    var tag = el.localName;
    if (tag === "input") {
      var type = typeOf(el) || "text";
      var labels = labelsText(el, ctx);
      if (collapse(labels)) {
        return labels;
      }
      if (type === "button" || type === "submit" || type === "reset") {
        return buttonValue(el, type);
      }
      if (type === "image") {
        return collapse(attr(el, "alt")) ? attr(el, "alt") : collapse(attr(el, "value")) ? attr(el, "value")
            : "Submit";
      }
      return "";
    }
    switch (tag) {
      case "textarea":
      case "select":
      case "button":
      case "meter":
      case "progress":
      case "output":
        return labelsText(el, ctx);
      case "img":
      case "area":
        return attr(el, "alt") || "";
      case "fieldset":
        return childText(el, "legend", ctx);
      case "table":
        return childText(el, "caption", ctx);
      case "figure":
        return childText(el, "figcaption", ctx);
      case "svg":
        return svgTitle(el);
      case "optgroup":
      case "option":
        return attr(el, "label") || "";
      default:
        return "";
    }
  }

  function placeholderOf(el) {
    var tag = el.localName;
    if (tag === "textarea" || (tag === "input" && PLACEHOLDER_TYPES[typeOf(el) || "text"])) {
      var placeholder = attr(el, "placeholder");
      if (placeholder !== null && collapse(placeholder)) {
        return placeholder;
      }
    }
    return attr(el, "aria-placeholder") || "";
  }

  // aria-labelledby, aria-label, the native source, the content (for roles named from content), title, placeholder.
  function nameOf(el, role, ctx) {
    var ids = attr(el, "aria-labelledby");
    if (ids !== null) {
      var referenced = labelledbyText(el, ids, ctx);
      if (collapse(referenced)) {
        return collapse(referenced);
      }
    }
    var label = attr(el, "aria-label");
    if (label !== null && collapse(label)) {
      return collapse(label);
    }
    var own = nativeName(el, ctx);
    if (collapse(own)) {
      return collapse(own);
    }
    if (FROM_CONTENT[role]) {
      var content = contentOf(el, ctx, null, false, 0);
      if (collapse(content)) {
        return collapse(content);
      }
    }
    var title = attr(el, "title");
    if (title !== null && collapse(title)) {
      return collapse(title);
    }
    return collapse(placeholderOf(el));
  }

  // ---- states ----

  function checkedOf(el) {
    if (el.localName === "input") {
      var type = typeOf(el);
      if (type === "checkbox" || type === "radio") {
        return type === "checkbox" && el.indeterminate ? "mixed" : el.checked === true;
      }
    }
    var checked = attr(el, "aria-checked");
    return checked === "mixed" ? "mixed" : checked === "true";
  }

  function disabledOf(el, role) {
    try {
      if (el.matches(":disabled")) {
        return true;
      }
    } catch (e) {
      if (el.disabled === true) {
        return true;
      }
    }
    if (attr(el, "aria-disabled") === "true") {
      return true;
    }
    if (!WIDGETS[role]) {
      return false;
    }
    for (var node = parentOf(el); node && node.nodeType === 1; node = parentOf(node)) {
      if (attr(node, "aria-disabled") === "true") {
        return true;
      }
    }
    return false;
  }

  // The value of a value-bearing role; never a password field's.
  function valueOf(el) {
    var tag = el.localName;
    if (tag === "input") {
      return typeOf(el) === "password" ? null : String(el.value);
    }
    if (tag === "textarea") {
      return String(el.value);
    }
    if (tag === "select") {
      return selectedText(el);
    }
    if (tag === "progress") {
      return el.hasAttribute("value") ? String(el.value) : null;
    }
    if (tag === "meter") {
      return String(el.value);
    }
    var text = attr(el, "aria-valuetext");
    if (text !== null && collapse(text)) {
      return text;
    }
    return attr(el, "aria-valuenow");
  }

  function levelOf(el) {
    var level = parseInt(attr(el, "aria-level"), 10);
    return level >= 1 ? level : LEVELS[el.localName] || 2;
  }

  function describeNode(el, role, ctx) {
    var node = {role: role, name: nameOf(el, role, ctx)};
    if (VALUED[role]) {
      var value = valueOf(el);
      if (value !== null && value !== "") {
        node.value = value;
      }
    }
    if (CHECKABLE[role]) {
      node.checked = checkedOf(el);
    }
    if (disabledOf(el, role)) {
      node.disabled = true;
    }
    if (el === ctx.active && el !== ctx.doc.body && el !== ctx.doc.documentElement) {
      node.focused = true;
    }
    if (role === "heading") {
      node.level = levelOf(el);
    }
    return node;
  }

  // ---- aria(root, maxNodes, interestingOnly) ----

  // Text runs between nodes and block boundaries become {role: "text", name} nodes.
  function finish(items, walk) {
    var out = [];
    var text = "";
    for (var i = 0; i <= items.length; i++) {
      var item = i < items.length ? items[i] : BREAK;
      if (typeof item === "string") {
        text += item;
        continue;
      }
      var name = collapse(text);
      text = "";
      if (name !== "") {
        if (walk.count >= walk.limit) {
          walk.truncated = true;
          walk.stopped = true;
        } else {
          walk.count++;
          out.push({role: "text", name: name});
        }
      }
      if (item !== BREAK) {
        out.push(item);
      }
    }
    return out;
  }

  function walkChildren(el, walk, textShown) {
    var out = [];
    var kids = childrenOf(el);
    for (var i = 0; i < kids.length && !walk.stopped; i++) {
      var kid = kids[i];
      if (kid.nodeType === 3) {
        if (textShown) {
          out.push(kid.nodeValue);
        }
      } else if (kid.nodeType === 1) {
        var items = walkElement(kid, walk);
        for (var j = 0; j < items.length; j++) {
          out.push(items[j]);
        }
      }
    }
    return out;
  }

  // The items an element contributes to its parent: its node, or (pruned or hidden by visibility) its children's
  // items in its place; set apart by boundaries when it is block-level.
  function walkElement(el, walk) {
    if (walk.stopped) {
      return [];
    }
    if (++walk.visits > walk.maxVisits) {
      walk.truncated = true;
      walk.stopped = true;
      return [];
    }
    var ctx = walk.ctx;
    if (excluded(el, ctx)) {
      return [];
    }
    if (el.localName === "br") {
      return [BREAK];
    }
    var block = isBlock(el, ctx);
    var shown = !invisible(el, ctx);
    var role = shown ? roleOf(el, ctx) : null;
    if (role === null || (walk.interesting && PRUNED[role])) {
      var inner = LEAVES[el.localName] ? [] : walkChildren(el, walk, shown);
      return block ? [BREAK].concat(inner, [BREAK]) : inner;
    }
    if (walk.count >= walk.limit) {
      walk.truncated = true;
      walk.stopped = true;
      return [];
    }
    walk.count++;
    var node = describeNode(el, role, ctx);
    if (!LEAVES[el.localName]) {
      var children = [];
      var items = finish(walkChildren(el, walk, true), walk);
      for (var i = 0; i < items.length; i++) {
        if (items[i].role === "text" && items[i].name === node.name) {
          walk.count--; // the text is already the node's name
        } else {
          children.push(items[i]);
        }
      }
      if (children.length > 0) {
        node.children = children;
      }
    }
    return block ? [BREAK, node, BREAK] : [node];
  }

  function query(selector) {
    if (typeof selector !== "string" || collapse(selector) === "") {
      return err("INVALID_PARAMS", {reason: "selector must be a non-empty string"});
    }
    try {
      return document.querySelector(selector);
    } catch (e) {
      return err("INVALID_PARAMS", {reason: "invalid selector", selector: selector, message: messageOf(e)});
    }
  }

  function aria(root, maxNodes, interestingOnly) {
    var limit = positiveInt(maxNodes, DEFAULT_MAX_NODES);
    var doc = root !== null && typeof root === "object" && root.nodeType === 9 ? root : document;
    var walk = {ctx: newContext(doc), limit: limit, count: 0, visits: 0, maxVisits: limit * VISITS_PER_NODE,
      truncated: false, stopped: false, interesting: interestingOnly !== false};
    var items;
    if (root === undefined || root === null || root === doc) {
      var start = doc.body || doc.documentElement;
      items = start ? walkChildren(start, walk, true) : [];
    } else {
      var el = root;
      if (typeof root === "string") {
        el = query(root);
        if (isError(el)) {
          return el;
        }
        if (el === null) {
          return err("NOT_FOUND", {selector: root});
        }
      } else if (!isElement(root)) {
        return err("INVALID_PARAMS", {reason: "root must be null, a selector or an element"});
      }
      items = walkElement(el, walk);
    }
    return {nodes: finish(items, walk), truncated: walk.truncated};
  }

  // ---- find(selectorOrRole, name, index) ----

  function nameTest(name) {
    if (name === undefined || name === null) {
      return function () {
        return true;
      };
    }
    if (typeof name === "string" || typeof name === "number" || typeof name === "boolean") {
      var wanted = collapse(name);
      return function (actual) {
        return actual === wanted;
      };
    }
    if (typeof name === "object" && typeof name.regex === "string") {
      var pattern;
      try {
        pattern = new RegExp(name.regex, name.ignoreCase === true ? "i" : "");
      } catch (e) {
        return err("INVALID_PARAMS", {reason: "invalid regex", regex: name.regex, message: messageOf(e)});
      }
      return function (actual) {
        return pattern.test(actual);
      };
    }
    return err("INVALID_PARAMS", {reason: "name must be a string or {regex, ignoreCase}"});
  }

  function collectRole(parent, role, test, ctx, found) {
    var kids = childrenOf(parent);
    for (var i = 0; i < kids.length; i++) {
      var kid = kids[i];
      if (kid.nodeType !== 1 || SKIPPED[kid.localName]) {
        continue;
      }
      if (roleOf(kid, ctx) === role && !hiddenForAria(kid, ctx) && test(nameOf(kid, role, ctx))) {
        found.push(kid);
      }
      if (!LEAVES[kid.localName]) {
        collectRole(kid, role, test, ctx, found);
      }
    }
  }

  // Every element the locator matches, in document order, or an error object. A selector matches as
  // querySelectorAll does (light DOM); a role matches the role aria() reports on the elements aria() shows (open
  // shadow roots included) and, when a name is given, the accessible name.
  function matchAll(spec, ctx) {
    var found = [];
    if (spec.selector !== undefined) {
      if (typeof spec.selector !== "string" || collapse(spec.selector) === "") {
        return err("INVALID_PARAMS", {reason: "selector must be a non-empty string"});
      }
      var list;
      try {
        list = document.querySelectorAll(spec.selector);
      } catch (e) {
        return err("INVALID_PARAMS", {reason: "invalid selector", selector: spec.selector, message: messageOf(e)});
      }
      for (var i = 0; i < list.length; i++) {
        found.push(list[i]);
      }
      return found;
    }
    if (typeof spec.role !== "string" || collapse(spec.role) === "") {
      return err("INVALID_PARAMS", {reason: "role must be a non-empty string"});
    }
    var test = nameTest(spec.name);
    if (isError(test)) {
      return test;
    }
    var start = document.body || document.documentElement;
    if (start) {
      collectRole(start, collapse(spec.role).toLowerCase(), test, ctx, found);
    }
    return found;
  }

  function toIndex(index) {
    if (index === undefined || index === null) {
      return null;
    }
    if (typeof index === "number" && index >= 0 && index < Infinity && Math.floor(index) === index) {
      return index;
    }
    return err("INVALID_PARAMS", {reason: "index must be a non-negative integer"});
  }

  function describeElement(el, ctx) {
    var role = roleOf(el, ctx);
    var described = {role: role, name: nameOf(el, role, ctx), tag: el.localName};
    if (el.id) {
      described.id = el.id;
    }
    return described;
  }

  function candidates(list, ctx) {
    var out = [];
    for (var i = 0; i < list.length && i < MAX_CANDIDATES; i++) {
      out.push(describeElement(list[i], ctx));
    }
    return out;
  }

  function find(selectorOrRole, name, index) {
    var ctx = newContext(document);
    var at = toIndex(index);
    if (isError(at)) {
      return at;
    }
    var spec = name === undefined || name === null ? {selector: selectorOrRole} : {role: selectorOrRole, name: name};
    var list = matchAll(spec, ctx);
    if (isError(list)) {
      return list;
    }
    if (at === null ? list.length === 1 : at < list.length) {
      return list[at === null ? 0 : at];
    }
    return {count: list.length, candidates: candidates(list, ctx)};
  }

  function notFound(spec, at, count) {
    var data = spec.selector !== undefined ? {selector: spec.selector}
        : {role: spec.role, name: spec.name === undefined ? null : spec.name};
    if (at !== null) {
      data.index = at;
      data.count = count;
    }
    return err("NOT_FOUND", data);
  }

  // {element, matched} for the options of domClick and domFill, or an error object.
  function resolve(opts, ctx) {
    var hasSelector = opts.selector !== undefined && opts.selector !== null;
    var hasRole = opts.role !== undefined && opts.role !== null;
    if (hasSelector === hasRole) {
      return err("INVALID_PARAMS", {reason: hasSelector ? "give a selector or a role, not both"
          : "a selector or a role is required"});
    }
    var at = toIndex(opts.index);
    if (isError(at)) {
      return at;
    }
    var spec = hasSelector ? {selector: opts.selector} : {role: opts.role, name: opts.name};
    var list = matchAll(spec, ctx);
    if (isError(list)) {
      return list;
    }
    if (at === null && list.length === 1) {
      return {element: list[0], matched: 1};
    }
    if (at !== null && at < list.length) {
      return {element: list[at], matched: list.length};
    }
    if (at !== null || list.length === 0) {
      return notFound(spec, at, list.length);
    }
    return err("AMBIGUOUS", {candidates: candidates(list, ctx), count: list.length});
  }

  function options(opts) {
    if (opts === undefined || opts === null) {
      return {};
    }
    return typeof opts === "object" ? opts : err("INVALID_PARAMS", {reason: "options must be an object"});
  }

  // ---- DOM actions ----

  // Rendered with a non-empty box and not hidden by visibility (display:contents: one of its children is).
  function visibleForAction(el, ctx, depth) {
    var style = ctx.style(el);
    if (style.display === "contents") {
      for (var child = el.firstElementChild; child && depth < 32; child = child.nextElementSibling) {
        if (visibleForAction(child, ctx, depth + 1)) {
          return true;
        }
      }
      return false;
    }
    if (invisible(el, ctx)) {
      return false;
    }
    var rects;
    try {
      rects = el.getClientRects();
    } catch (e) {
      return false;
    }
    for (var i = 0; i < rects.length; i++) {
      if (rects[i].width > 0 && rects[i].height > 0) {
        return true;
      }
    }
    return false;
  }

  function clickElement(el, ctx, expectVisible) {
    if (expectVisible && !visibleForAction(el, ctx, 0)) {
      return err("INVALID_PARAMS", {reason: "not visible"});
    }
    var described = describeElement(el, ctx);
    if (typeof el.click === "function") {
      el.click();
    } else { // an SVG element has no click()
      var view = viewOf(el);
      el.dispatchEvent(new view.MouseEvent("click", {bubbles: true, cancelable: true, composed: true, view: view}));
    }
    return described;
  }

  function editableHost(el) {
    var host = typeof el.closest === "function" ? el.closest("[contenteditable]") : null;
    if (!host) {
      return el.isContentEditable === true;
    }
    var value = collapse(attr(host, "contenteditable")).toLowerCase();
    return value === "" || value === "true" || value === "plaintext-only";
  }

  function fillKind(el) {
    var tag = el.localName;
    if (tag === "input") {
      return FILLABLE_TYPES[typeOf(el) || "text"] ? "input" : null;
    }
    if (tag === "textarea" || tag === "select") {
      return tag;
    }
    return editableHost(el) ? "editable" : null;
  }

  function setValue(el, ctorName, value) {
    var ctor = viewOf(el)[ctorName];
    var descriptor = ctor && ctor.prototype ? Object.getOwnPropertyDescriptor(ctor.prototype, "value") : null;
    if (descriptor && typeof descriptor.set === "function") {
      descriptor.set.call(el, value); // the prototype's setter, so that a framework's own value tracker notices
    } else {
      el.value = value;
    }
  }

  function optionFor(select, text) {
    var opts = select.options;
    for (var i = 0; i < opts.length; i++) {
      if (opts[i].value === text) {
        return opts[i];
      }
    }
    var wanted = collapse(text);
    for (var j = 0; j < opts.length; j++) {
      if (optionText(opts[j]) === wanted) {
        return opts[j];
      }
    }
    return null;
  }

  function fire(el, type) {
    var view = viewOf(el);
    var event = type === "input" && typeof view.InputEvent === "function"
        ? new view.InputEvent("input", {bubbles: true, cancelable: false, composed: true, inputType: "insertText"})
        : new view.Event(type, {bubbles: true});
    el.dispatchEvent(event);
  }

  function pressEnter(el, type) {
    var view = viewOf(el);
    var Keyboard = typeof view.KeyboardEvent === "function" ? view.KeyboardEvent : view.Event;
    el.dispatchEvent(new Keyboard(type, {key: "Enter", code: "Enter", keyCode: 13, which: 13, bubbles: true,
      cancelable: true, composed: true}));
  }

  function fillElement(el, ctx, text, clear, enter, expectVisible) {
    if (expectVisible && !visibleForAction(el, ctx, 0)) {
      return err("INVALID_PARAMS", {reason: "not visible"});
    }
    var kind = fillKind(el);
    if (kind === null) {
      return err("INVALID_PARAMS", {reason: "not fillable", tag: el.localName, type: typeOf(el)});
    }
    var locked = false;
    if (kind !== "editable") {
      try {
        locked = el.matches(":disabled");
      } catch (e) {
        locked = el.disabled === true;
      }
      locked = locked || (kind !== "select" && el.readOnly === true);
    }
    if (locked) {
      return err("INVALID_PARAMS", {reason: "not editable"});
    }
    var option = null;
    if (kind === "select") {
      option = optionFor(el, text);
      if (option === null) {
        return err("INVALID_PARAMS", {reason: "no such option"});
      }
    }
    if (typeof el.focus === "function") {
      el.focus();
    }
    if (kind === "select") {
      setValue(el, "HTMLSelectElement", option.value);
    } else if (kind === "editable") {
      if (clear) {
        el.textContent = text;
      } else {
        el.appendChild(el.ownerDocument.createTextNode(text));
      }
    } else {
      var ctorName = kind === "textarea" ? "HTMLTextAreaElement" : "HTMLInputElement";
      var before = String(el.value);
      var wanted = clear ? text : before + text;
      setValue(el, ctorName, wanted);
      if (wanted !== "" && String(el.value) === "") { // sanitised away: a number field given letters, say
        setValue(el, ctorName, before);
        return err("INVALID_PARAMS", {reason: "value rejected"});
      }
    }
    fire(el, "input");
    fire(el, "change");
    if (enter) {
      pressEnter(el, "keydown");
      pressEnter(el, "keyup");
    }
    var filled = {tag: el.localName, type: kind === "editable" ? "contenteditable" : typeOf(el)};
    if (el.id) {
      filled.id = el.id;
    }
    filled.typed = text.length;
    return filled;
  }

  function textOf(el, maxChars) {
    var max = positiveInt(maxChars, DEFAULT_MAX_CHARS);
    var text = typeof el.innerText === "string" ? el.innerText : el.textContent || "";
    return text.length > max ? {text: cut(text, max), truncated: true} : {text: text, truncated: false};
  }

  function notAnElement() {
    return err("INVALID_PARAMS", {reason: "not an element"});
  }

  function click(el) {
    return isElement(el) ? clickElement(el, newContext(el.ownerDocument), true) : notAnElement();
  }

  function textParam(text) {
    return typeof text === "number" || typeof text === "boolean" ? String(text) : text;
  }

  function fill(el, text, clear, enter) {
    if (!isElement(el)) {
      return notAnElement();
    }
    var value = textParam(text);
    if (typeof value !== "string") {
      return err("INVALID_PARAMS", {reason: "text must be a string"});
    }
    return fillElement(el, newContext(el.ownerDocument), value, clear !== false, enter === true, true);
  }

  function text(el, maxChars) {
    return isElement(el) ? textOf(el, maxChars) : notAnElement();
  }

  function domClick(opts) {
    var o = options(opts);
    if (isError(o)) {
      return o;
    }
    var ctx = newContext(document);
    var resolved = resolve(o, ctx);
    if (isError(resolved)) {
      return resolved;
    }
    var clicked = clickElement(resolved.element, ctx, o.expectVisible !== false);
    return isError(clicked) ? clicked : {matched: resolved.matched, clicked: clicked, fidelity: "DOM"};
  }

  function domFill(opts) {
    var o = options(opts);
    if (isError(o)) {
      return o;
    }
    var value = textParam(o.text);
    if (typeof value !== "string") {
      return err("INVALID_PARAMS", {reason: "text must be a string"});
    }
    var ctx = newContext(document);
    var resolved = resolve(o, ctx);
    if (isError(resolved)) {
      return resolved;
    }
    var done = fillElement(resolved.element, ctx, value, o.clear !== false, o.enter === true,
        o.expectVisible !== false);
    if (isError(done)) {
      return done;
    }
    var filled = {tag: done.tag, type: done.type};
    if (done.id !== undefined) {
      filled.id = done.id;
    }
    return {matched: resolved.matched, filled: filled, typed: done.typed};
  }

  function domText(opts) {
    var o = options(opts);
    if (isError(o)) {
      return o;
    }
    var selector = o.selector === undefined || o.selector === null ? "body" : o.selector;
    var el = query(selector);
    if (isError(el)) {
      return el;
    }
    return el === null ? err("NOT_FOUND", {selector: selector}) : textOf(el, o.maxChars);
  }

  // ---- dispatch ----

  function eventTarget(target) {
    if (target === undefined || target === null || target === "window") {
      return window;
    }
    if (target === "document") {
      return document;
    }
    if (typeof target === "string") {
      var el = query(target);
      if (isError(el)) {
        return el;
      }
      return el === null ? err("NOT_FOUND", {selector: target}) : el;
    }
    if (isNode(target) || isWindow(target)) {
      return target;
    }
    return err("INVALID_PARAMS", {reason: "target must be a selector, \"window\" or \"document\""});
  }

  // dispatch({target?, name, detail?}) or dispatch(target, name, detail).
  function dispatch(target, name, detail) {
    var args = arguments.length === 1 && target !== null && typeof target === "object" && !isNode(target)
        && !isWindow(target) ? target : {target: target, name: name, detail: detail};
    if (typeof args.name !== "string" || args.name === "") {
      return err("INVALID_PARAMS", {reason: "name must be a non-empty string"});
    }
    var on = eventTarget(args.target);
    if (isError(on)) {
      return on;
    }
    var view = isWindow(on) ? on : viewOf(on);
    var Custom = view && typeof view.CustomEvent === "function" ? view.CustomEvent : CustomEvent;
    on.dispatchEvent(new Custom(args.name, {detail: args.detail, bubbles: true}));
    return {dispatched: true};
  }

  // ---- tap and drain ----

  var tapState = null;
  var buffer = [];
  var sizes = [];
  var dropped = 0;

  function record(event) {
    var entry = {name: String(event.type)};
    var detail;
    try {
      detail = event.detail;
    } catch (e) {
      detail = undefined;
    }
    if (detail !== undefined && detail !== null) {
      var json;
      try {
        json = jsonStringify(detail, jsonReplacer());
      } catch (e) {
        json = jsonStringify("[unserialisable detail: " + messageOf(e) + "]");
      }
      if (json.length > MAX_DETAIL) {
        entry.detail = cut(json, MAX_DETAIL); // the cut JSON text, as a string
        entry.truncated = true;
      } else {
        entry.detail = jsonParse(json);
      }
    }
    buffer.push(entry);
    sizes.push(jsonStringify(entry).length);
    if (buffer.length > MAX_EVENTS) {
      buffer.shift();
      sizes.shift();
      dropped++;
    }
  }

  function globRegex(pattern) {
    return new RegExp("^" + pattern.replace(GLOB_SPECIAL, "\\$&").replace(STAR, ".*") + "$");
  }

  function matchesGlob(globs, type) {
    for (var i = 0; i < globs.length; i++) {
      if (globs[i].test(type)) {
        return true;
      }
    }
    return false;
  }

  function untap() {
    var state = tapState;
    tapState = null;
    if (state === null) {
      return;
    }
    state.active = false;
    for (var i = 0; i < state.listeners.length; i++) {
      state.target.removeEventListener(state.listeners[i].name, state.listeners[i].listener, true);
    }
    if (state.wrapper !== null) {
      var current = Object.getOwnPropertyDescriptor(state.target, "dispatchEvent");
      if (current && current.value === state.wrapper) {
        var saved = state.saved;
        if (saved) {
          Object.defineProperty(state.target, "dispatchEvent", saved);
        } else {
          delete state.target.dispatchEvent;
        }
      }
      // Otherwise the page chained its own wrapper over ours: ours stays in its chain, inactive, and only forwards.
    }
  }

  // The wrapper chains to the target's own dispatchEvent when it had one, else to the inherited method as it is at
  // the time of the call, so that a page that patches EventTarget.prototype later is not bypassed.
  function wrapDispatch(state) {
    var on = state.target;
    var saved = Object.getOwnPropertyDescriptor(on, "dispatchEvent");
    state.saved = saved;
    state.wrapper = function dispatchEvent(event) {
      if (state.active) {
        try {
          var type = event.type;
          if (typeof type === "string" && !state.exact[type] && matchesGlob(state.globs, type)) {
            record(event);
          }
        } catch (ignored) {
          // the tap never breaks the page's own dispatch
        }
      }
      var next = !saved ? Object.getPrototypeOf(on).dispatchEvent : saved.get ? saved.get.call(on) : saved.value;
      return next.apply(this === undefined || this === null ? on : this, arguments);
    };
    Object.defineProperty(on, "dispatchEvent", {value: state.wrapper, enumerable: false, writable: true,
      configurable: true});
  }

  // Replaces the current tap. An exact name is heard by a capturing listener on the target, so every event of that
  // name whose path reaches it; a glob ("app.*") by a wrapper of the target's own dispatchEvent, so the events
  // dispatched on the target itself. tap([], target) removes everything. The buffer is kept until drained.
  function tap(patterns, target) {
    var list = patterns === undefined || patterns === null ? [] : patterns;
    var valid = Array.isArray(list);
    for (var i = 0; valid && i < list.length; i++) {
      valid = typeof list[i] === "string" && list[i] !== "";
    }
    if (!valid) {
      return err("INVALID_PARAMS", {reason: "patterns must be an array of non-empty event names"});
    }
    var targetName = target === undefined || target === null ? "window" : target;
    if (targetName !== "window" && targetName !== "document") {
      return err("INVALID_PARAMS", {reason: "target must be \"window\" or \"document\""});
    }
    untap();
    var on = targetName === "window" ? window : document;
    var state = {target: on, listeners: [], exact: Object.create(null), globs: [], wrapper: null, saved: undefined,
      active: true};
    var seen = Object.create(null);
    var installed = 0;
    for (var j = 0; j < list.length; j++) {
      var pattern = list[j];
      if (seen[pattern]) {
        continue;
      }
      seen[pattern] = true;
      installed++;
      if (pattern.indexOf("*") >= 0) {
        state.globs.push(globRegex(pattern));
      } else {
        var listener = recorder(state);
        state.exact[pattern] = true;
        state.listeners.push({name: pattern, listener: listener});
        on.addEventListener(pattern, listener, true);
      }
    }
    if (state.globs.length > 0) {
      wrapDispatch(state);
    }
    tapState = installed > 0 ? state : null;
    return {installed: installed};
  }

  function recorder(state) {
    return function (event) {
      if (state.active) {
        record(event);
      }
    };
  }

  // Returns and clears the buffered events. With maxChars, stops before the event that would take the events' JSON
  // past it (always returning at least one), keeps the rest buffered and says more: true.
  function drain(maxChars) {
    var take = buffer.length;
    if (typeof maxChars === "number" && maxChars >= 1) {
      var used = 0;
      for (take = 0; take < buffer.length; take++) {
        if (take > 0 && used + sizes[take] > maxChars) {
          break;
        }
        used += sizes[take];
      }
    }
    var result = {events: buffer.slice(0, take), dropped: dropped};
    buffer = buffer.slice(take);
    sizes = sizes.slice(take);
    dropped = 0;
    if (buffer.length > 0) {
      result.more = true;
    }
    return result;
  }

  // ---- hit(x, y): the element under a point of the viewport (version 2; phase7-contracts.md section 4.2) ----

  // The element drawn at the point, through open shadow roots.
  function deepElementAt(doc, x, y) {
    var el = doc.elementFromPoint(x, y);
    for (var depth = 0; el && el.shadowRoot && depth < 32; depth++) {
      var inner = el.shadowRoot.elementFromPoint(x, y);
      if (!inner || inner === el) {
        break;
      }
      el = inner;
    }
    return el;
  }

  // The element's client rectangle in whole CSS pixels.
  function rectOf(el) {
    var r = el.getBoundingClientRect();
    return {x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), h: Math.round(r.height)};
  }

  function bounded(text) {
    var s = String(text);
    return s.length > MAX_HIT_TEXT ? cut(s, MAX_HIT_TEXT) : s;
  }

  // The library's find answers exactly this element for the locator.
  function only(el, selectorOrRole, name) {
    try {
      return find(selectorOrRole, name, null) === el;
    } catch (e) {
      return false;
    }
  }

  function idSelector(el) {
    return typeof el.id === "string" && PLAIN_IDENT.test(el.id) ? "#" + el.id : null;
  }

  function firstClass(el) {
    var list = el.classList;
    for (var i = 0; list && i < list.length; i++) {
      if (PLAIN_IDENT.test(list[i])) {
        return list[i];
      }
    }
    return null;
  }

  // One step of a selector path: the tag, its first plain class, and its position among its siblings of that tag.
  function stepOf(el, nth) {
    var cls = firstClass(el);
    var step = el.localName + (cls === null ? "" : "." + cls);
    if (nth) {
      var index = 1;
      for (var sib = el.previousElementSibling; sib; sib = sib.previousElementSibling) {
        if (sib.localName === el.localName) {
          index++;
        }
      }
      step += ":nth-of-type(" + index + ")";
    }
    return step;
  }

  // An id selector, else the shortest path of steps up from the element (anchored at an ancestor's unique id when
  // one is met) that the library's find answers with exactly this element; null when none is found within
  // MAX_SELECTOR_STEPS.
  function selectorOf(el) {
    var id = idSelector(el);
    if (id !== null && only(el, id, null)) {
      return id;
    }
    var parts = [];
    for (var node = el; node && node.nodeType === 1 && parts.length < MAX_SELECTOR_STEPS; node = node.parentElement) {
      if (node !== el) {
        var anchor = idSelector(node);
        if (anchor !== null && only(node, anchor, null)) {
          var anchored = [anchor].concat(parts).join(" > ");
          return only(el, anchored, null) ? anchored : null;
        }
      }
      if (!PLAIN_IDENT.test(node.localName)) {
        return null;
      }
      var plain = [stepOf(node, false)].concat(parts).join(" > ");
      if (only(el, plain, null)) {
        return plain;
      }
      parts.unshift(stepOf(node, true));
      if (only(el, parts.join(" > "), null)) {
        return parts.join(" > ");
      }
    }
    return null;
  }

  // The locators that name exactly this element: {role, name} (not for a generic or presentational role, nor an empty
  // or overlong name), then {selector}.
  function locatorsOf(el, role, name) {
    var out = [];
    if (role && !PRUNED[role] && name !== "" && name.length <= MAX_HIT_TEXT && only(el, role, name)) {
      out.push({role: role, name: name});
    }
    var selector = selectorOf(el);
    if (selector !== null) {
      out.push({selector: selector});
    }
    return out;
  }

  // For a frame element: the point in the child document's viewport (inside the element's border and padding), and
  // the element's name and URL, by which the caller finds the child frame.
  function childOf(frame, x, y) {
    var r = frame.getBoundingClientRect();
    var left = r.left;
    var top = r.top;
    try {
      var cs = viewOf(frame).getComputedStyle(frame);
      left += (parseFloat(cs.borderLeftWidth) || 0) + (parseFloat(cs.paddingLeft) || 0);
      top += (parseFloat(cs.borderTopWidth) || 0) + (parseFloat(cs.paddingTop) || 0);
    } catch (e) {
      // the border box then
    }
    var url = "";
    try {
      url = String(frame.src || "");
    } catch (e) {
      url = "";
    }
    return {x: Math.round(x - left), y: Math.round(y - top), name: bounded(frame.name || ""),
      url: url.length > MAX_FRAME_URL ? "" : url};
  }

  // {element: {role, name, tag, id?, rect: {x, y, w, h}}, locators: [{role, name} | {selector}], unique, child?}, or
  // NOT_FOUND {reason: "outside", x, y} when the point lies outside the viewport or hits no element. child is set for
  // a frame element: the caller evaluates hit there with child's point.
  function hit(x, y) {
    if (typeof x !== "number" || typeof y !== "number" || x !== x || y !== y) {
      return err("INVALID_PARAMS", {reason: "x and y must be numbers"});
    }
    var view = document.defaultView || window;
    if (x < 0 || y < 0 || x >= view.innerWidth || y >= view.innerHeight) {
      return err("NOT_FOUND", {reason: "outside", x: x, y: y});
    }
    var el = deepElementAt(document, x, y);
    if (!isElement(el)) {
      return err("NOT_FOUND", {reason: "outside", x: x, y: y});
    }
    var ctx = newContext(document);
    var role = roleOf(el, ctx);
    var name = collapse(nameOf(el, role, ctx));
    var element = {role: role, name: bounded(name), tag: el.localName};
    if (el.id) {
      element.id = bounded(el.id);
    }
    element.rect = rectOf(el);
    var locators = locatorsOf(el, role, name);
    var out = {element: element, locators: locators, unique: locators.length > 0};
    if (el.localName === "iframe" || el.localName === "frame") {
      out.child = childOf(el, x, y);
    }
    return out;
  }

  // ---- defer and collect: awaiting a promise over a channel that cannot (section 4.5, the jxbrowser adapter) ----

  var deferred = Object.create(null);
  var deferredIds = [];
  var nextDeferred = 1;

  function defer(value) {
    var id = "d" + nextDeferred++;
    var entry = {state: "pending"};
    deferred[id] = entry;
    deferredIds.push(id);
    while (deferredIds.length > MAX_DEFERRED) {
      delete deferred[deferredIds.shift()];
    }
    NativePromise.resolve(value).then(function (settled) {
      try {
        entry.text = stringify(settled);
        entry.state = "fulfilled";
      } catch (e) {
        entry.message = messageOf(e);
        entry.stack = stackOf(e);
        entry.state = "rejected";
      }
    }, function (reason) {
      entry.message = messageOf(reason);
      entry.stack = stackOf(reason);
      entry.state = "rejected";
    });
    return id;
  }

  // The JSON text of {state: "pending" | "fulfilled" | "rejected" | "unknown", text?, message?, stack?}; a settled
  // result is forgotten once collected.
  function collect(id) {
    var entry = typeof id === "string" ? deferred[id] : undefined;
    if (entry === undefined) {
      return jsonStringify({state: "unknown"});
    }
    if (entry.state !== "pending") {
      delete deferred[id];
      deferredIds.splice(deferredIds.indexOf(id), 1);
    }
    return jsonStringify(entry);
  }

  // ---- install ----

  // An older library in the slot (D300): the functions it lacks are installed into it, once, and its version is raised
  // where it can be (a version-1 slot's version is not configurable, so it stays "1" and the functions it now has mark
  // the upgrade). A slot that refuses them (a page froze it, or put something else there) is left as it is: a call of a
  // function it lacks then fails as a script error that names the function.
  function upgrade(target) {
    var names = missingIn(target);
    var mine = {hit: hit};
    for (var i = 0; i < names.length; i++) {
      try {
        Object.defineProperty(target, names[i], {value: mine[names[i]], enumerable: false, writable: false,
          configurable: false});
      } catch (e) {
        return "present";
      }
    }
    try {
      Object.defineProperty(target, "version", {value: VERSION, enumerable: false, writable: false,
        configurable: true});
    } catch (e) {
      // version 1 made it non-configurable: the version stays, the functions mark the upgrade
    }
    return "upgraded";
  }

  if (slot) {
    return upgrade(slot);
  }

  var L = {};

  // The version is configurable, so that a later library can raise it when it installs what it adds (D300).
  function expose(name, value, raisable) {
    Object.defineProperty(L, name, {value: value, enumerable: false, writable: false, configurable: raisable === true});
  }

  expose("version", VERSION, true);
  expose("stringify", stringify);
  expose("aria", aria);
  expose("find", find);
  expose("click", click);
  expose("fill", fill);
  expose("text", text);
  expose("domClick", domClick);
  expose("domFill", domFill);
  expose("domText", domText);
  expose("dispatch", dispatch);
  expose("tap", tap);
  expose("drain", drain);
  expose("defer", defer);
  expose("collect", collect);
  expose("hit", hit);
  Object.defineProperty(window, KEY, {value: L, enumerable: false, writable: false, configurable: true});
  return "installed";
})()
