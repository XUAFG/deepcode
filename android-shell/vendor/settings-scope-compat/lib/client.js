window.__ModuleLoader__.load({id:"@dsh-android/dsh-settings-scope-compat",factory:(require)=>{var module={exports:{}};var exports=module.exports;
'use strict';
/* Legacy settingsScope service for engine 0.2.0+.
 * The upstream settings rewrite (settingsSchema/configForms + remote.settings
 * RPC) removed the per-namespace client store that fork plugins inject as
 * "settingsScope". This shim re-registers it with a localStorage backend.
 * Snapshot shape matches the legacy contract: {status, writable, value}. */
var PREFIX = 'dsh.settings-scope.compat.';
function readNS(ns) {
  try { return JSON.parse(localStorage.getItem(PREFIX + ns) || '{}') || {}; }
  catch (e) { return {}; }
}
function Scope(ns) {
  this.ns = ns;
  this.listeners = new Set();
  this.snapshot = { status: 'ready', writable: true, value: readNS(ns) };
}
Scope.prototype.getSnapshot = function () { return this.snapshot; };
Scope.prototype.subscribe = function (fn) {
  var self = this;
  self.listeners.add(fn);
  return function () { self.listeners.delete(fn); };
};
Scope.prototype.set = async function (key, value) {
  var data = readNS(this.ns);
  data[key] = value;
  try { localStorage.setItem(PREFIX + this.ns, JSON.stringify(data)); }
  catch (e) { throw new Error('settings persist failed: ' + ((e && e.message) || e)); }
  this.snapshot = Object.assign({}, this.snapshot, { value: Object.assign({}, data) });
  this.listeners.forEach(function (fn) { try { fn(); } catch (e) {} });
};
function Compat() { this.scopes = new Map(); }
Compat.prototype.bind = function (spec) {
  var ns = (spec && spec.namespace) || 'default';
  if (!this.scopes.has(ns)) this.scopes.set(ns, new Scope(ns));
  return this.scopes.get(ns);
};
Compat.prototype.describe = function () {
  var all = {};
  this.scopes.forEach(function (sc, ns) { all[ns] = sc.snapshot.value; });
  return { status: 'ready', writable: true, value: all };
};
var inject = [];
function apply(ctx) {
  if (ctx.settingsScope) return;
  ctx.provide('settingsScope', new Compat());
}
exports.inject = inject;
exports.apply = apply;
return module.exports;}});
