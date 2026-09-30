/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
*/

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');

function bootstrap () {
    const source = fs.readFileSync(path.join(__dirname, '../../framework/src/org/apache/cordova/SecondaryWebViewManager.java'), 'utf8');
    const method = source.slice(source.indexOf('private String script(String id) {'));
    const body = method.slice(0, method.indexOf('+ (heartbeatMs > 0 ?'));
    const fragments = [...body.matchAll(/"(?:\\.|[^"\\])*"/g)].map(match => JSON.parse(match[0]));
    return fragments[0] + '"test"' + fragments.slice(1).join('') + 'window.__testPost=post;})();';
}

async function verify (binary) {
    const sent = [];
    const warnings = [];
    const unhandled = [];
    process.on('unhandledRejection', error => unhandled.push(error));
    const native = { post: json => { sent.push(JSON.parse(json)); throw new Error('bridge destroyed'); } };
    const binaryBridge = {
        postMessage: packet => {
            const size = new DataView(packet).getUint32(0);
            sent.push(JSON.parse(new TextDecoder().decode(new Uint8Array(packet, 4, size))));
            throw new Error('bridge destroyed');
        }
    };
    const window = { addEventListener () {} };
    if (binary) window._secondaryBinary = binaryBridge;
    const context = {
        window,
        document: { readyState: 'loading' },
        _secondaryNative: native,
        _secondaryBinary: binaryBridge,
        TextEncoder,
        TextDecoder,
        Uint8Array,
        DataView,
        ArrayBuffer,
        console: { warn: value => warnings.push(value) }
    };
    vm.runInNewContext(bootstrap(), context);
    assert.equal(window.secondaryWebView.post('app', null), undefined);
    assert.equal(await window.__testPost({ v: 1, id: 'direct', kind: 'evt', name: 'app', payload: null }), false);
    window.secondaryWebView.unsubscribe('one');
    await new Promise(resolve => setTimeout(resolve, 10));
    assert.equal(sent.length, 3);
    assert.equal(sent.every(envelope => envelope.name !== '__secondaryChannelError'), true);
    assert.deepEqual(warnings, []);
    assert.deepEqual(unhandled, []);
}

if (process.argv[2] === '--transport-child') {
    verify(process.argv[3] === 'binary').catch(error => { console.error(error); process.exitCode = 1; });
} else {
    describe('secondary WebView bootstrap transport failure', () => {
        for (const binary of [false, true]) {
            it(`swallows ${binary ? 'binary' : 'string'} bridge throws without channelError or unhandled rejection`, () => {
                execFileSync(process.execPath, [__filename, '--transport-child', binary ? 'binary' : 'string']);
            });
        }
    });
}
