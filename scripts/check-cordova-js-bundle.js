/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

const fs = require('fs');
const path = require('path');
const { build } = require('cordova-js/build-tools');

const root = path.resolve(__dirname, '..');
const bundle = path.join(root, 'templates/project/assets/www/cordova.js');

async function main () {
    const current = fs.readFileSync(bundle);
    const fresh = Buffer.from(await build({ platformRoot: root }));
    if (!current.equals(fresh)) {
        console.error('Cordova JS bundle is stale; run npm run prepare.');
        process.exitCode = 1;
    }
}

main().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
