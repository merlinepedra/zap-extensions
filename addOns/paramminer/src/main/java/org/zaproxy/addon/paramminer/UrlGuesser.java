/*
 * Zed Attack Proxy (ZAP) and its related class files.
 *
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 *
 * Copyright 2022 The ZAP Development Team
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
package org.zaproxy.addon.paramminer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.network.HttpMessage;
import org.parosproxy.paros.network.HttpSender;
import org.zaproxy.addon.commonlib.http.ComparableResponse;

public class UrlGuesser implements Runnable {

    public static enum Mode {
        VERIFY,
        BRUTEFORCE,
    }

    public static enum Method {
        GET,
        POST,
        XML,
        JSON,
    }

    public static enum Status {
        OK,
        RETRY,
        KILL,
    }

    private HttpSender httpSender;
    private int id;
    // TODO should pass id to bruteforce task
    private ParamMinerConfig config;
    private GuesserScan scan;

    private static final String DEFAULTWORDLISTPATH =
            Constant.getZapHome() + "/wordlists/small_list.txt";
    private Path defaultWordListFile;
    private List<String> defaultWordList;

    private Path customWordListFile;
    private List<String> customWordList;

    private List<String> wordlist;
    private List<ParamGuessResult> guessedParams;
    private final ExecutorService executor;
    private List<ParamGuessResult> paramGuessResults;
    private final String INIT_PARAM = "zap";
    private final String INIT_VALUE = "123";
    private static final Logger logger = LogManager.getLogger(UrlGuesser.class);

    public UrlGuesser(
            int id,
            ParamMinerConfig config,
            GuesserScan scan,
            HttpSender httpSender,
            ExecutorService executor) {
        this.id = id;
        this.config = config;
        this.scan = scan;
        this.httpSender = httpSender;
        this.executor = executor;

        if (config.getUsePredefinedUrlWordlists()) {
            defaultWordListFile = Paths.get(DEFAULTWORDLISTPATH);
            defaultWordList = UrlUtils.read(defaultWordListFile);
        }
        if (config.getUseCustomUrlWordlists()) {
            customWordListFile = Paths.get(config.getCustomUrlWordlistPath());
            customWordList = UrlUtils.read(customWordListFile);
        }

        if (defaultWordList != null && customWordList != null) {
            Set<String> list = new HashSet<String>();
            list.addAll(defaultWordList);
            list.addAll(customWordList);
            wordlist = new ArrayList<String>();
            for (String param : list) {
                wordlist.add(param);
            }
        } else if (customWordList == null && defaultWordList != null) {
            wordlist = defaultWordList;
        } else {
            wordlist = customWordList;
        }
    }

    @Override
    public void run() {
        try {
            if (config.getUrlGetRequest()) {
                startGuess(Method.GET, wordlist);
            }
            if (config.getUrlPostRequest()) {
                startGuess(Method.POST, wordlist);
            }
            if (config.getUrlXmlRequest()) {
                startGuess(Method.XML, wordlist);
            }
            if (config.getUrlJsonRequest()) {
                startGuess(Method.JSON, wordlist);
            }
            // TODO show paramGuessResults in GUI(OutputTab)
        } catch (Exception e) {
            // TODO Add exception message using Constants
            logger.debug(e);
        }
    }

    private void startGuess(Method method, List<String> wordlist) {
        // TODO Add right options so that http message can be loaded using right click menu.
        HttpMessage msg = new HttpMessage();
        Map<String, String> initialParam = new HashMap<String, String>();
        initialParam.put(INIT_PARAM, INIT_VALUE);
        UrlBruteForce initialBruter =
                new UrlBruteForce(
                        null,
                        INIT_VALUE,
                        method,
                        initialParam,
                        Mode.BRUTEFORCE,
                        scan,
                        this,
                        this.httpSender,
                        wordlist,
                        null);

        String valueSent = initialBruter.requester(msg, method, initialParam);
        // TODO Add heuristic method to mine parameters from base response.

        ComparableResponse base = new ComparableResponse(msg, valueSent);
        List<Map<String, String>> paramGroups = UrlUtils.slice(UrlUtils.populate(wordlist), 2);
        List<Map<String, String>> usableParams = new ArrayList<Map<String, String>>();
        while (true) {
            paramGroups = narrowDownParams(base, method, paramGroups);
            paramGroups = UrlUtils.confirmUsableParameters(paramGroups, usableParams);
            if (paramGroups.isEmpty()) {
                break;
            }
        }
        paramGuessResults = new ArrayList<ParamGuessResult>();
        for (Map<String, String> paramVerify : usableParams) {
            executor.submit(
                    new UrlBruteForce(
                            base,
                            INIT_VALUE,
                            method,
                            paramVerify,
                            Mode.VERIFY,
                            scan,
                            this,
                            this.httpSender,
                            wordlist,
                            guessedParams));
        }
    }

    private List<Map<String, String>> narrowDownParams(
            ComparableResponse base, Method method, List<Map<String, String>> paramGroups) {
        List<Map<String, String>> narrowedParamGroups = new ArrayList<Map<String, String>>();
        for (Map<String, String> param : paramGroups) {
            Future<ParamReasons> future =
                    executor.submit(
                            new UrlBruteForce(
                                    base,
                                    INIT_VALUE,
                                    method,
                                    param,
                                    Mode.BRUTEFORCE,
                                    scan,
                                    this,
                                    this.httpSender,
                                    wordlist,
                                    guessedParams));
            try {
                ParamReasons narrowedParam = future.get();
                if (narrowedParam != null) {
                    List<Map<String, String>> slices = UrlUtils.slice(narrowedParam.getParams(), 2);
                    for (Map<String, String> slice : slices) {
                        narrowedParamGroups.add(slice);
                    }
                }
            } catch (InterruptedException | ExecutionException e) {
                // TODO Display proper error message to user
                logger.debug(e);
            }
        }
        return narrowedParamGroups;
    }
}