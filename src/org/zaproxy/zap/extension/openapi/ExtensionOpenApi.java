/*
 * Zed Attack Proxy (ZAP) and its related class files.
 * 
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 * 
 * Copyright 2017 The ZAP Development Team
 *  
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0 
 *   
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License. 
 */
package org.zaproxy.zap.extension.openapi;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;

import org.apache.commons.httpclient.URI;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.control.Control;
import org.parosproxy.paros.extension.ExtensionAdaptor;
import org.parosproxy.paros.extension.ExtensionHook;
import org.parosproxy.paros.model.Model;
import org.parosproxy.paros.network.HttpSender;
import org.parosproxy.paros.view.View;
import org.zaproxy.zap.extension.api.API;
import org.zaproxy.zap.extension.openapi.converter.swagger.SwaggerConverter;
import org.zaproxy.zap.extension.openapi.network.Requestor;
import org.zaproxy.zap.extension.spider.ExtensionSpider;
import org.zaproxy.zap.spider.parser.SpiderParser;
import org.zaproxy.zap.view.ZapMenuItem;

public class ExtensionOpenApi extends ExtensionAdaptor {

    public static final String NAME = "ExtensionOpenApi";

    private static final String THREAD_PREFIX = "ZAP-Import-OpenAPI-";

    private ZapMenuItem menuImportLocalOpenApi = null;
    private ZapMenuItem menuImportUrlOpenApi = null;
    private int threadId = 1;

    private static final Logger LOG = Logger.getLogger(ExtensionOpenApi.class);

    public ExtensionOpenApi() {
        super(NAME);
    }

    @Override
    public void hook(ExtensionHook extensionHook) {
        super.hook(extensionHook);
        
        API.getInstance().registerApiImplementor(new OpenApiAPI(this));

        /* Custom spider is added in order to explore Open API definitions. */
        OpenApiSpider.enable();
        ExtensionSpider spider = (ExtensionSpider) Control.getSingleton()
                .getExtensionLoader()
                .getExtension(ExtensionSpider.NAME);
        SpiderParser customSpider = new OpenApiSpider();
        if (spider != null) {
            spider.addCustomParser(customSpider);
            LOG.debug("Added custom Open API spider.");
        } else {
            LOG.warn("Custom Open API spider could not be added.");
        }

        if (getView() != null) {
            extensionHook.getHookMenu().addToolsMenuItem(getMenuImportLocalOpenApi());
            extensionHook.getHookMenu().addToolsMenuItem(getMenuImportUrlOpenApi());
        }
    }

    @Override
    public void unload() {
        super.unload();
        /* Disables custom spider. */
        OpenApiSpider.disable();
    }

    /* Menu option to import a local OpenApi file. */
    private ZapMenuItem getMenuImportLocalOpenApi() {
        if (menuImportLocalOpenApi == null) {
            menuImportLocalOpenApi = new ZapMenuItem("openapi.topmenu.tools.importopenapi");
            menuImportLocalOpenApi.setToolTipText(Constant.messages.getString("openapi.topmenu.tools.importopenapi.tooltip"));

            menuImportLocalOpenApi.addActionListener(new java.awt.event.ActionListener() {

                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    // Prompt for a OpenApi file.
                    final JFileChooser chooser = new JFileChooser(Model.getSingleton().getOptionsParam().getUserDirectory());
                    int rc = chooser.showOpenDialog(View.getSingleton().getMainFrame());
                    if (rc == JFileChooser.APPROVE_OPTION) {
                        importOpenApiDefinition(chooser.getSelectedFile());
                    }

                }
            });
        }
        return menuImportLocalOpenApi;
    }

    /* Menu option to import a OpenApi file from a given URL. */
    private ZapMenuItem getMenuImportUrlOpenApi() {
        if (menuImportUrlOpenApi == null) {
            menuImportUrlOpenApi = new ZapMenuItem("openapi.topmenu.tools.importremoteopenapi");
            menuImportUrlOpenApi
                    .setToolTipText(Constant.messages.getString("openapi.topmenu.tools.importremoteopenapi.tooltip"));

            final ExtensionOpenApi shadowCopy = this;
            menuImportUrlOpenApi.addActionListener(new java.awt.event.ActionListener() {

                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    SwingUtilities.invokeLater(new Runnable() {

                        @Override
                        public void run() {
                            new ImportFromUrlDialog(View.getSingleton().getMainFrame(), shadowCopy);
                        }
                    });
                }
            });
        }
        return menuImportUrlOpenApi;
    }

    public void importOpenApiDefinition(final URI uri) {
        Requestor requestor = new Requestor(HttpSender.MANUAL_REQUEST_INITIATOR);
        requestor.addListener(new HistoryPersister());
        try {
            importOpenApiDefinition(requestor.getResponseBody(uri));
        } catch (IOException e) {
            if (View.isInitialised()) {
                View.getSingleton().showWarningDialog(Constant.messages.getString("openapi.io.error"));
            }
            LOG.warn(e.getMessage(), e);
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
        }
    }

    public void importOpenApiDefinition(final File file) {
        try {
            importOpenApiDefinition(FileUtils.readFileToString(file));
        } catch (IOException e) {
            if (View.isInitialised()) {
                View.getSingleton().showWarningDialog(Constant.messages.getString("openapi.io.error"));
            }
            LOG.warn(e.getMessage(), e);
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
        }
    }

    private void importOpenApiDefinition(final String defn) {
        Thread t = new Thread(THREAD_PREFIX + threadId++) {

            @Override
            public void run() {
                List<String> errors = null;
                try {
                    Requestor requestor = new Requestor(HttpSender.MANUAL_REQUEST_INITIATOR);
                    requestor.addListener(new HistoryPersister());
                    SwaggerConverter converter = new SwaggerConverter(defn);
                    errors = converter.getErrorMessages();
                    errors.addAll(requestor.run(converter.getRequestModels()));
                    if (errors.size() > 0) {
                        logErrors(errors);
                        if (View.isInitialised()) {
                            View.getSingleton().showWarningDialog(Constant.messages.getString("openapi.parse.warn"));
                        }
                    } else {
                        if (View.isInitialised()) {
                            View.getSingleton().showMessageDialog(Constant.messages.getString("openapi.parse.ok"));
                        }
                    }
                } catch (Exception e) {
                    if (View.isInitialised()) {
                        View.getSingleton().showWarningDialog(Constant.messages.getString("openapi.parse.error"));
                    }
                    logErrors(errors);
                    LOG.warn(e.getMessage(), e);
                }
            }

        };
        t.start();

    }
    
    private void logErrors(List<String> errors) {
        if (errors != null) {
            for (String error : errors) {
                if (View.isInitialised()) {
                    View.getSingleton().getOutputPanel().append(error + "\n");
                } else {
                    LOG.warn(error);
                }
            }
        }
    }

    @Override
    public boolean canUnload() {
        return false;
    }

    @Override
    public String getAuthor() {
        return Constant.ZAP_TEAM + " plus Joanna Bona, Artur Grzesica, Michal Materniak and Marcin Spiewak";
    }

    @Override
    public String getDescription() {
        return Constant.messages.getString("openapi.desc");
    }

    @Override
    public URL getURL() {
        try {
            return new URL(Constant.ZAP_HOMEPAGE);
        } catch (MalformedURLException e) {
            return null;
        }
    }

}
