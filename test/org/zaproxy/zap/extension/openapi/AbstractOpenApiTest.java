/*
 * Zed Attack Proxy (ZAP) and its related class files.
 *
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 *
 * Copyright 2018 The ZAP Development Team
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
package org.zaproxy.zap.extension.openapi;

import org.junit.After;
import org.junit.Before;
import org.zaproxy.zap.testutils.TestUtils;

/**
 * Base class for OpenAPI tests.
 * <p>
 * It's responsible for {@link #startServer() starting} and {@link #stopServer() stopping} the HTTP test server and
 * {@link #setUpZap() set up ZAP} for each test method.
 */
public abstract class AbstractOpenApiTest extends TestUtils {

    @Override
    protected void setUpMessages() {
        mockMessages(new ExtensionOpenApi());
    }

    @Before
    public void setUp() throws Exception {
        setUpZap();
        startServer();
    }

    @After
    public void teardown() throws Exception {
        stopServer();
    }
}