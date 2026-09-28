/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0. */
'use strict';

var exec = require('cordova/exec');
var sessionId = null;
var version = 1;
var nextId = 0;
var listeners = new Set();
var pending = new Map();

function command (name, args) {
    return new Promise(function (resolve, reject) {
        exec(resolve, reject, 'SecondaryWebView', name, args || []);
    });
}
function encode (envelope) {
    if (envelope && envelope.payload instanceof ArrayBuffer) {
        var bytes = new Uint8Array(envelope.payload);
        var data = '';
        for (var i = 0; i < bytes.length; i++) data += String.fromCharCode(bytes[i]);
        return Object.assign({}, envelope, { payload: { __secondaryArrayBuffer: btoa(data) } });
    }
    return envelope;
}
function decode (envelope) {
    if (envelope && envelope.payload && envelope.payload.__secondaryArrayBuffer) {
        envelope.payload = Uint8Array.from(atob(envelope.payload.__secondaryArrayBuffer), function (c) { return c.charCodeAt(0); }).buffer;
    }
    return envelope;
}
function dispatch (event) {
    if (!event || event.sessionId !== sessionId) return;
    if (event.type === 'message' && event.detail) decode(event.detail);
    if (event.type === 'message' && event.detail && event.detail.kind === 'res') {
        var p = pending.get(event.detail.id);
        if (p) {
            pending.delete(event.detail.id);
            event.detail.err ? p.reject(event.detail.err) : p.resolve(event.detail.payload);
        }
    }
    listeners.forEach(function (listener) { listener(event); });
    if (event.type === 'destroyed') {
        sessionId = null;
        pending.forEach(function (p) { p.reject({ code: 'DESTROYED' }); });
        pending.clear();
    }
}
var api = {
    getCapabilities: function () { return command('getCapabilities'); },
    create: function (config) {
        return new Promise(function (resolve, reject) {
            if (sessionId) { var error = new Error('Destroy the current secondary web view first'); error.code = 'ALREADY_EXISTS'; reject(error); return; }
            var settled = false;
            var earlyEvents = [];
            exec(function (value) {
                if (!settled && value && value.sessionId && !value.type) {
                    sessionId = value.sessionId;
                    settled = true;
                    resolve({ sessionId: sessionId, storageIsolation: value.storageIsolation });
                    earlyEvents.forEach(dispatch);
                    earlyEvents = [];
                } else if (!settled && value && value.type === 'destroyed') {
                    settled = true;
                    earlyEvents = [];
                    var error = new Error('Secondary web view destroyed during create'); error.code = 'DESTROYED'; reject(error);
                } else if (!settled) earlyEvents.push(value);
                else dispatch(value);
            }, function (error) {
                if (settled) return;
                settled = true;
                earlyEvents = [];
                reject(error);
            }, 'SecondaryWebView', 'create', [config || {}]);
        });
    },
    destroy: function () { return command('destroy').then(function (value) { sessionId = null; return value; }); },
    setTouchRegions: function (regions) { return command('setTouchRegions', [regions]); },
    send: function (envelope) { return command('send', [encode(Object.assign({ sessionId: sessionId }, envelope))]); },
    post: function (name, payload) { return api.send({ v: version, id: String(++nextId), kind: 'evt', name: name, payload: payload === undefined ? null : payload }); },
    request: function (name, payload) {
        var id = String(++nextId);
        return new Promise(function (resolve, reject) {
            pending.set(id, { resolve: resolve, reject: reject });
            api.send({ v: version, id: id, kind: 'req', name: name, payload: payload === undefined ? null : payload }).catch(function (error) { pending.delete(id); reject(error); });
        });
    },
    onEvent: function (listener) { listeners.add(listener); return function () { listeners.delete(listener); }; },
    getMetrics: function () { return command('getMetrics'); }
};
module.exports = api;
