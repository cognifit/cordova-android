/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0. */
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

describe('secondary web view inline media create config', () => {
    let api;
    let exec;
    beforeEach(() => {
        exec = jasmine.createSpy('exec');
        const sandbox = { module: { exports: {} }, require: () => exec };
        const source = fs.readFileSync(path.join(__dirname, '../../cordova-js-src/plugin/android/secondarywebview.js'), 'utf8');
        vm.runInNewContext(source, sandbox);
        api = sandbox.module.exports;
    });

    for (const option of [undefined, false, true]) {
        it(`forwards ${option === undefined ? 'the omitted native default' : option} independently of autoplay`, async () => {
            const config = { url: 'www/player.html', allowMediaAutoplay: false };
            if (option !== undefined) config.allowInlineMediaPlayback = option;
            exec.and.callFake((success, failure, service, action, args) => {
                expect(service).toBe('SecondaryWebView');
                expect(action).toBe('create');
                expect(args[0]).toBe(config);
                expect(args[0].allowMediaAutoplay).toBeFalse();
                expect(Object.hasOwn(args[0], 'allowInlineMediaPlayback')).toBe(option !== undefined);
                expect(args[0].allowInlineMediaPlayback).toBe(option);
                success({ sessionId: 'inline-test', storageIsolation: 'origin' });
            });
            expect((await api.create(config)).sessionId).toBe('inline-test');
        });
    }

    it('propagates INVALID_CONFIG from native validation without retaining a session', async () => {
        const error = { code: 'INVALID_CONFIG', message: 'allowInlineMediaPlayback must be boolean' };
        exec.and.callFake((success, failure) => failure(error));
        await expectAsync(api.create({ url: 'www/player.html', allowInlineMediaPlayback: 'true' })).toBeRejectedWith(error);
        exec.and.callFake(success => success({ sessionId: 'after-rejection', storageIsolation: 'origin' }));
        expect((await api.create({ url: 'www/player.html', allowInlineMediaPlayback: true })).sessionId).toBe('after-rejection');
    });
});
