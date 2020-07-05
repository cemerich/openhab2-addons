/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.verisure.internal;

import static org.openhab.binding.verisure.internal.VerisureBindingConstants.*;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.apache.commons.lang.StringUtils;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.openhab.binding.verisure.internal.model.VerisureAlarms;
import org.openhab.binding.verisure.internal.model.VerisureBroadbandConnections;
import org.openhab.binding.verisure.internal.model.VerisureClimates;
import org.openhab.binding.verisure.internal.model.VerisureDoorWindows;
import org.openhab.binding.verisure.internal.model.VerisureInstallations;
import org.openhab.binding.verisure.internal.model.VerisureInstallations.Owainstallation;
import org.openhab.binding.verisure.internal.model.VerisureMiceDetection;
import org.openhab.binding.verisure.internal.model.VerisureMiceDetection.Mouse;
import org.openhab.binding.verisure.internal.model.VerisureSmartLock;
import org.openhab.binding.verisure.internal.model.VerisureSmartLocks;
import org.openhab.binding.verisure.internal.model.VerisureSmartPlugs;
import org.openhab.binding.verisure.internal.model.VerisureThing;
import org.openhab.binding.verisure.internal.model.VerisureUserPresences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * This class performs the communication with Verisure My Pages.
 *
 * @author Jarle Hjortland - Initial contribution
 * @author Jan Gustafsson - Re-design and support for several sites and update to new Verisure API
 *
 */
@NonNullByDefault
public class VerisureSession {

    @NonNullByDefault
    private final class VerisureInstallation {
        private @Nullable String installationName;
        private @Nullable BigDecimal installationId;
        private @Nullable String pinCode;

        public @Nullable String getPinCode() {
            return pinCode;
        }

        public void setPinCode(@Nullable String pinCode) {
            this.pinCode = pinCode;
        }

        public VerisureInstallation() {
        }

        public @Nullable BigDecimal getInstallationId() {
            return installationId;
        }

        public @Nullable String getInstallationName() {
            return installationName;
        }

        public void setInstallationId(@Nullable BigDecimal installationId) {
            this.installationId = installationId;
        }

        public void setInstallationName(@Nullable String installationName) {
            this.installationName = installationName;
        }
    }

    private final HashMap<String, VerisureThing> verisureThings = new HashMap<String, VerisureThing>();
    private final Logger logger = LoggerFactory.getLogger(VerisureSession.class);
    private final Gson gson = new GsonBuilder().create();
    private final List<DeviceStatusListener> deviceStatusListeners = new CopyOnWriteArrayList<>();
    private final Hashtable<@Nullable BigDecimal, @Nullable VerisureInstallation> verisureInstallations = new Hashtable<@Nullable BigDecimal, @Nullable VerisureInstallation>();
    private static final List<String> APISERVERLIST = new ArrayList<>(
            Arrays.asList("https://m-api01.verisure.com", "https://m-api02.verisure.com"));
    private int apiServerInUseIndex = 0;
    private String apiServerInUse = APISERVERLIST.get(apiServerInUseIndex);
    private boolean areWeLoggedOut = true;
    private String authstring = "";
    private @Nullable String csrf;
    private @Nullable String pinCode;
    private HttpClient httpClient;
    private @Nullable String userName = "";
    private String passwordName = "vid";
    private @Nullable String password = "";

    public VerisureSession(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public boolean initialize(@Nullable String authstring, @Nullable String pinCode, @Nullable String userName) {
        logger.debug("VerisureSession:initialize");
        if (authstring != null) {
            this.authstring = authstring.substring(0);
            this.pinCode = pinCode;
            this.userName = userName;
            // Try to login to Verisure
            if (logIn()) {
                getInstallations();
                return true;
            } else {
                logger.warn("Failed to login to Verisure!");
                return false;
            }
        }
        return false;
    }

    public boolean refresh() {
        logger.debug("VerisureSession:refresh");
        int statusCode = areWeLoggedIn();
        if (statusCode == 503) {
            return false;
        } else if (!areWeLoggedOut && (statusCode == 1)) {
            updateStatus();
            return true;
        } else {
            if (logIn()) {
                updateStatus();
                areWeLoggedOut = false;
                return true;
            } else {
                areWeLoggedOut = true;
                return false;
            }
        }
    }

    public int sendCommand(String url, String data, @Nullable BigDecimal installationId) {
        logger.debug("Sending command with URL {} and data {}", url, data);
        configureInstallationInstance(installationId);
        int httpResultCode = setSessionCookieAuthLogin();
        if (httpResultCode == HttpStatus.OK_200) {
            return postVerisureAPI(url, data);
        } else {
            return httpResultCode;
        }
    }

    public boolean unregisterDeviceStatusListener(DeviceStatusListener deviceStatusListener) {
        logger.debug("unregisterDeviceStatusListener for listener {}", deviceStatusListener);
        return deviceStatusListeners.remove(deviceStatusListener);
    }

    public boolean registerDeviceStatusListener(DeviceStatusListener deviceStatusListener) {
        logger.debug("registerDeviceStatusListener for listener {}", deviceStatusListener);
        return deviceStatusListeners.add(deviceStatusListener);
    }

    public void dispose() {
    }

    public @Nullable VerisureThing getVerisureThing(String deviceId) {
        return verisureThings.get(deviceId);
    }

    public HashMap<String, VerisureThing> getVerisureThings() {
        return verisureThings;
    }

    public @Nullable String getCsrf() {
        return csrf;
    }

    public @Nullable String getPinCode() {
        return pinCode;
    }

    public String getApiServerInUse() {
        return apiServerInUse;
    }

    public void setApiServerInUse(String apiServerInUse) {
        this.apiServerInUse = apiServerInUse;
    }

    public String getNextApiServer() {
        apiServerInUseIndex++;
        if (apiServerInUseIndex > (APISERVERLIST.size() - 1)) {
            apiServerInUseIndex = 0;
        }
        return APISERVERLIST.get(apiServerInUseIndex);
    }

    public void configureInstallationInstance(@Nullable BigDecimal installationId) {
        logger.debug("Attempting to fetch CSRF and configure installation instance");
        try {
            csrf = getCsrfToken(installationId);
            logger.debug("Got CSRF: {}", csrf);
            // Set installation
            String url = SET_INSTALLATION + installationId.toString();
            logger.debug("Set installation URL: {}", url);
            httpClient.GET(url);
        } catch (ExecutionException e) {
            logger.warn("Caught ExecutionException {}", e.getMessage(), e);
        } catch (InterruptedException e) {
            logger.warn("Caught InterruptedException {}", e.getMessage(), e);
        } catch (TimeoutException e) {
            logger.warn("Caught TimeoutException {}", e.getMessage(), e);
        }
    }

    public @Nullable String getCsrfToken(@Nullable BigDecimal installationId) {
        String subString = null;
        String html = null;
        String url = SETTINGS + installationId.toString();
        logger.debug("Settings URL: {}", url);

        try {
            ContentResponse resp = httpClient.GET(url);
            html = resp.getContentAsString();
            logger.trace("{} html: {}", url, html);
        } catch (ExecutionException e) {
            logger.warn("Caught ExecutionException {}", e.getMessage(), e);
            return null;
        } catch (InterruptedException e) {
            logger.warn("Caught InterruptedException {}", e.getMessage(), e);
            return null;
        } catch (TimeoutException e) {
            logger.warn("Caught TimeoutException {}", e.getMessage(), e);
            return null;
        }

        Document htmlDocument = Jsoup.parse(html);
        Element nameInput = htmlDocument.select("input[name=_csrf]").first();
        return nameInput.attr("value");
    }

    public @Nullable String getPinCode(@Nullable BigDecimal installationId) {
        return verisureInstallations.get(installationId).getPinCode();
    }

    private int areWeLoggedIn() {
        logger.debug("areWeLoggedIn() - Checking if we are logged in");
        String url = STATUS;
        try {
            logger.debug("Check for login status, url: {}", url);
            ContentResponse response = httpClient.newRequest(url).method(HttpMethod.GET).send();
            logger.trace("HTTP status response: {}", response.getContentAsString());
            switch (response.getStatus()) {
                case HttpStatus.OK_200:
                    if (response.getContentAsString().contains("<title>MyPages</title>")) {
                        logger.debug("Status code 200 and on MyPages!");
                        CookieStore c = httpClient.getCookieStore();
                        List<HttpCookie> cookies = c.get(URI.create("http://verisure.com"));
                        cookies.forEach(cookie -> {
                            logger.debug("Response Cookie: {}", cookie);
                            if (cookie.getName().equals(passwordName)) {
                                password = cookie.getValue();
                                logger.debug("Fetching vid {} from cookie", password);
                            }
                        });
                        return 1;
                    } else {
                        logger.debug("Not on MyPages, we need to login again!");
                        return 0;
                    }
                case HttpStatus.MOVED_TEMPORARILY_302:
                    // Redirection
                    logger.debug("Status code 302. Redirected. Probably not logged in");
                    return 0;
                case HttpStatus.INTERNAL_SERVER_ERROR_500:
                    // Verisure service temporarily down
                    logger.debug("Status code 500. Verisure service temporarily down");
                    return HttpStatus.INTERNAL_SERVER_ERROR_500;
                case HttpStatus.SERVICE_UNAVAILABLE_503:
                    // Verisure service temporarily down
                    logger.debug("Status code 503. Verisure service temporarily down");
                    return HttpStatus.SERVICE_UNAVAILABLE_503;
                default:
                    logger.info("Status code {} body {}", response.getStatus(), response.getContentAsString());
                    break;
            }
        } catch (ExecutionException e) {
            logger.warn("ExecutionException: {}", e.getMessage(), e);
        } catch (InterruptedException e) {
            logger.warn("InterruptedException: {}", e.getMessage(), e);
        } catch (TimeoutException e) {
            logger.warn("TimeoutException: {}", e.getMessage(), e);
        }
        return 0;
    }

    private @Nullable <T> T getJSONVerisureAPI(String url, Class<T> jsonClass) {
        T result = null;
        logger.debug("HTTP GET: {}", BASEURL + url);
        try {
            ContentResponse httpResult = httpClient.GET(BASEURL + url + "?_=" + System.currentTimeMillis());
            logger.debug("HTTP Response ({}) Body:{}", httpResult.getStatus(),
                    httpResult.getContentAsString().replaceAll("\n+", "\n"));
            if (httpResult.getStatus() == HttpStatus.OK_200) {
                result = gson.fromJson(httpResult.getContentAsString(), jsonClass);
            }
            return result;
        } catch (ExecutionException e) {
            logger.warn("Caught ExecutionException {} for URL string {}", e, url);
        } catch (InterruptedException e) {
            logger.warn("Caught InterruptedException {} for URL string {}", e, url);
        } catch (TimeoutException e) {
            logger.warn("Caught TimeoutException {} for URL string {}", e, url);
        }
        return null;
    }

    private @Nullable ContentResponse postVerisureAPI(String url, String data, Boolean isJSON) {
        try {
            logger.debug("postVerisureAPI URL: {} Data:{}", url, data);
            Request request = httpClient.newRequest(url).method(HttpMethod.POST);
            if (isJSON) {
                request.header("content-type", "application/json");
            } else {
                if (csrf != null) {
                    request.header("X-CSRF-TOKEN", csrf);
                }
            }
            request.header("Accept", "application/json");
            if (!data.equals("empty")) {
                request.content(new BytesContentProvider(data.getBytes("UTF-8")),
                        "application/x-www-form-urlencoded; charset=UTF-8");
            } else {
                logger.debug("Setting cookie with username {} and vid {}", userName, password);
                request.cookie(new HttpCookie("username", userName));
                request.cookie(new HttpCookie("vid", password));
            }
            logger.debug("HTTP POST Request {}.", request.toString());
            return request.send();
        } catch (ExecutionException e) {
            logger.warn("Caught ExecutionException {}", e.getMessage(), e);
        } catch (UnsupportedEncodingException e) {
            logger.warn("Caught UnsupportedEncodingException {}", e.getMessage(), e);
        } catch (InterruptedException e) {
            logger.warn("Caught InterruptedException {}", e.getMessage(), e);
        } catch (TimeoutException e) {
            logger.warn("Caught TimeoutException {}", e.getMessage(), e);
        } catch (RuntimeException e) {
            logger.warn("Caught RuntimeException {}", e.getMessage(), e);
        }
        return null;
    }

    private @Nullable <T> T postJSONVerisureAPI(String url, String data, Class<T> jsonClass) {
        for (int cnt = 0; cnt < APISERVERLIST.size(); cnt++) {
            ContentResponse response = postVerisureAPI(apiServerInUse + url, data, Boolean.TRUE);
            if (response != null) {
                logger.debug("HTTP Response ({})", response.getStatus());
                if (response.getStatus() == HttpStatus.OK_200) {
                    String content = response.getContentAsString();
                    if (content.contains("\"message\":\"Request Failed") && content.contains("503")) {
                        // Maybe Verisure has switched PAI server in use
                        setApiServerInUse(getNextApiServer());
                    } else {
                        String contentChomped = StringUtils.chomp(content);
                        logger.trace("Response body: {}", content);
                        return gson.fromJson(contentChomped, jsonClass);
                    }
                } else {
                    logger.debug("Failed to send POST, Http status code: {}", response.getStatus());
                }
            }
        }
        return null;
    }

    private int postVerisureAPI(String urlString, String data) {
        String url;
        if (urlString.contains("https://mypages")) {
            url = urlString;
        } else {
            url = apiServerInUse + urlString;
        }

        for (int cnt = 0; cnt < APISERVERLIST.size(); cnt++) {
            ContentResponse response = postVerisureAPI(url, data, Boolean.FALSE);
            if (response != null) {
                logger.debug("HTTP Response ({})", response.getStatus());
                if (response.getStatus() == HttpStatus.OK_200) {
                    String content = response.getContentAsString();
                    if (content.contains("\"message\":\"Request Failed. Code 503 from")) {
                        if (url.contains("https://mypages")) {
                            // Not an API URL
                            return HttpStatus.SERVICE_UNAVAILABLE_503;
                        } else {
                            // Maybe Verisure has switched API server in use
                            setApiServerInUse(getNextApiServer());
                            url = apiServerInUse + urlString;
                        }
                    } else {
                        logger.trace("Response body: {}", content);
                        return response.getStatus();
                    }
                } else {
                    logger.debug("Failed to send POST, Http status code: {}", response.getStatus());
                }
            }
        }
        return 999;
    }

    private int setSessionCookieAuthLogin() {
        // URL to set status which will give us 2 cookies with username and password used for the session
        String url = STATUS;

        try {
            ContentResponse response = httpClient.GET(url);
            logger.trace("HTTP Response ({}) Body:{}", response.getStatus(),
                    response.getContentAsString().replaceAll("\n+", "\n"));
            CookieStore c = httpClient.getCookieStore();
            List<HttpCookie> cookies = c.get(URI.create("http://verisure.com"));
            Iterator<HttpCookie> cookiesIterator = cookies.iterator();
            while (cookiesIterator.hasNext()) {
                HttpCookie theCookie = cookiesIterator.next();
                logger.debug("Response Cookie: name: {}, value: {} ", theCookie.getName(), theCookie.getValue());
                if (theCookie.getName().equals(passwordName)) {
                    password = theCookie.getValue();
                    logger.debug("Fetching vid {} from cookie", password);
                }
            }
        } catch (ExecutionException e) {
            logger.warn("ExecutionException: {}", e.getMessage(), e);
        } catch (InterruptedException e) {
            logger.warn("InterruptedException: {}", e.getMessage(), e);
        } catch (TimeoutException e) {
            logger.warn("TimeoutException: {}", e.getMessage(), e);
        }

        url = AUTH_LOGIN;
        return postVerisureAPI(url, "empty");
    }

    private void getInstallations() {
        logger.debug("Attempting to get all installations");

        int httpResultCode = setSessionCookieAuthLogin();
        if (httpResultCode == HttpStatus.OK_200) {
            String url = START_GRAPHQL;

            String queryQLAccountInstallations = "[{\"operationName\":\"AccountInstallations\",\"variables\":{\"email\":\""
                    + userName
                    + "\"},\"query\":\"query AccountInstallations($email: String!) {\\n  account(email: $email) {\\n    owainstallations {\\n      giid\\n      alias\\n      type\\n      subsidiary\\n      dealerId\\n      __typename\\n    }\\n    __typename\\n  }\\n}\\n\"}]";
            Class<VerisureInstallations> jsonClass = VerisureInstallations.class;
            VerisureInstallations installations = postJSONVerisureAPI(url, queryQLAccountInstallations, jsonClass);

            if (installations == null) {
                logger.debug("Failed to get installations");
            } else {
                logger.debug("Installation: {}", installations.toString());
                if (installations.getData() != null) {
                    List<Owainstallation> owaInstList = installations.getData().getAccount().getOwainstallations();
                    Boolean pinCodesMatchInstallations = true;
                    List<String> pinCodes = null;
                    if (pinCode != null) {
                        pinCodes = Arrays.asList(pinCode.split(","));
                        if (owaInstList != null && owaInstList.size() != pinCodes.size()) {
                            logger.debug("Number of installations {} does not match number of pin codes configured {}",
                                    owaInstList.size(), pinCodes.size());
                            pinCodesMatchInstallations = false;
                        } else if (owaInstList == null) {
                            logger.warn("Failed to get Verisure installations");
                            return;
                        }
                    } else {
                        logger.debug("No pin-code defined for user {}", userName);
                    }
                    for (int i = 0; i < owaInstList.size(); i++) {
                        VerisureInstallation vInst = new VerisureInstallation();
                        if (owaInstList.get(i).getAlias() != null && owaInstList.get(i).getGiid() != null) {
                            vInst.setInstallationId(new BigDecimal(owaInstList.get(i).getGiid()));
                            vInst.setInstallationName(owaInstList.get(i).getAlias());
                            if (pinCode != null) {
                                if (pinCodesMatchInstallations) {
                                    vInst.setPinCode(pinCodes.get(i));
                                    logger.debug("Setting pincode {} to installation ID {}", pinCodes.get(i),
                                            owaInstList.get(i).getGiid());
                                } else {
                                    vInst.setPinCode(pinCodes.get(0));
                                    logger.debug("Setting pincode {} to installation ID {}", pinCodes.get(0),
                                            owaInstList.get(i).getGiid());
                                }
                            }
                            verisureInstallations.put(new BigDecimal(owaInstList.get(i).getGiid()), vInst);
                        } else {
                            logger.warn("Failed to get alias and/or giid");
                        }
                    }
                } else {
                    logger.warn("Failed to fetch installations: {}", installations.toString());
                }
            }
        } else {
            logger.warn("Failed to set session cookie and auth login, HTTP result code: {}", httpResultCode);
        }
    }

    private synchronized boolean logIn() {
        logger.debug("Attempting to log in to mypages.verisure.com");
        String url = LOGON_SUF;
        logger.debug("Login URL: {}", url);
        int httpStatusCode = postVerisureAPI(url, authstring);
        if (httpStatusCode != HttpStatus.OK_200) {
            logger.debug("Failed to login, HTTP status code: {}", httpStatusCode);
            return false;
        }
        return true;
    }

    private void notifyListeners(VerisureThing thing) {
        for (DeviceStatusListener listener : deviceStatusListeners) {
            listener.onDeviceStateChanged(thing);
        }
    }

    private void updateStatus() {
        logger.debug("VerisureSession:updateStatus");

        VerisureInstallation vInst = null;
        for (Enumeration<@Nullable VerisureInstallation> num = verisureInstallations.elements(); num
                .hasMoreElements();) {
            vInst = num.nextElement();
            if (vInst != null) {
                configureInstallationInstance(vInst.getInstallationId());
                int httpResultCode = setSessionCookieAuthLogin();
                if (httpResultCode == HttpStatus.OK_200) {
                    updateAlarmStatus(VerisureAlarms.class, vInst);
                    updateSmartLockStatus(VerisureSmartLocks.class, vInst);
                    updateMiceDetectionStatus(VerisureMiceDetection.class, vInst);
                    updateClimateStatus(VerisureClimates.class, vInst);
                    updateDoorWindowStatus(VerisureDoorWindows.class, vInst);
                    updateUserPresenceStatus(VerisureUserPresences.class, vInst);
                    updateSmartPlugStatus(VerisureSmartPlugs.class, vInst);
                    updateBroadbandConnectionStatus(VerisureBroadbandConnections.class, vInst);
                } else {
                    logger.warn("Failed to set session cookie and auth login, HTTP result code: {}", httpResultCode);
                }
            }
        }
    }

    private synchronized void updateAlarmStatus(Class<? extends VerisureThing> jsonClass,
            @Nullable VerisureInstallation installation) {
        if (installation != null) {
            BigDecimal installationId = installation.getInstallationId();
            String url = START_GRAPHQL;

            String queryQLAlarmStatus = "[{\"operationName\":\"ArmState\",\"variables\":{\"giid\":\"" + installationId
                    + "\"},\"query\":\"query ArmState($giid: String!) {\\n  installation(giid: $giid) {\\n    armState {\\n      type\\n      statusType\\n      date\\n      name\\n      changedVia\\n      allowedForFirstLine\\n      allowed\\n      errorCodes {\\n        value\\n        message\\n        __typename\\n      }\\n      __typename\\n    }\\n    __typename\\n  }\\n}\\n\"}]";
            logger.debug("Trying to get alarm status with URL {} and data {}", url, queryQLAlarmStatus);
            VerisureThing thing = postJSONVerisureAPI(url, queryQLAlarmStatus, jsonClass);
            logger.debug("REST Response ({})", thing);

            if (thing != null) {
                // Set unique deviceID
                String deviceId = "alarm" + installationId.toString();
                thing.setDeviceId(deviceId);
                VerisureThing oldObj = verisureThings.get(thing.getDeviceId());
                if (oldObj == null || !oldObj.equals(thing)) {
                    thing.setSiteId(installation.getInstallationId());
                    thing.setSiteName(installation.getInstallationName());
                    verisureThings.put(deviceId, thing);
                    notifyListeners(thing);
                }
            } else {
                logger.debug("Failed to update alarm status!");
            }
        }
    }

    private void notifyListenersIfChanged(VerisureThing thing, VerisureInstallation installation, String deviceId) {
        deviceId = deviceId.replaceAll("[^a-zA-Z0-9]+", "");
        thing.setDeviceId(deviceId);
        VerisureThing oldObj = verisureThings.get(deviceId);
        if (oldObj == null || !oldObj.equals(thing)) {
            thing.setSiteId(installation.getInstallationId());
            thing.setSiteName(installation.getInstallationName());
            verisureThings.put(thing.getDeviceId(), thing);
            notifyListeners(thing);
        }
    }

    private synchronized void updateSmartLockStatus(Class<? extends VerisureThing> jsonClass,
            @Nullable VerisureInstallation installation) {
        if (installation != null) {
            BigDecimal installationId = installation.getInstallationId();
            String url = START_GRAPHQL;

            String queryQLSmartLock = "[{\"operationName\":\"DoorLock\",\"variables\":{\"giid\":\"" + installationId
                    + "\"},\"query\":\"query DoorLock($giid: String!) {\\n  installation(giid: $giid) {\\n    doorlocks {\\n      device {\\n        deviceLabel\\n        area\\n        __typename\\n      }\\n      currentLockState\\n      eventTime\\n      secureModeActive\\n      motorJam\\n      userString\\n      method\\n      __typename\\n    }\\n    __typename\\n  }\\n}\\n\"}]\n"
                    + "";
            logger.debug("Trying to get smart lock status with URL {} and data {}", url, queryQLSmartLock);
            VerisureSmartLocks thing = (VerisureSmartLocks) postJSONVerisureAPI(url, queryQLSmartLock, jsonClass);
            logger.debug("REST Response ({})", thing);

            if (thing != null && thing.getData() != null) {
                List<VerisureSmartLocks.Doorlock> doorLockList = thing.getData().getInstallation().getDoorlocks();
                for (VerisureSmartLocks.Doorlock doorLock : doorLockList) {
                    VerisureSmartLocks slThing = new VerisureSmartLocks();
                    VerisureSmartLocks.Installation inst = new VerisureSmartLocks.Installation();
                    List<VerisureSmartLocks.Doorlock> list = new ArrayList<VerisureSmartLocks.Doorlock>();
                    list.add(doorLock);
                    inst.setDoorlocks(list);
                    VerisureSmartLocks.Data data = new VerisureSmartLocks.Data();
                    data.setInstallation(inst);
                    slThing.setData(data);
                    // Set unique deviceID
                    String deviceId = doorLock.getDevice().getDeviceLabel();
                    if (deviceId != null) {
                        // Set location
                        slThing.setLocation(doorLock.getDevice().getArea());
                        slThing.setDeviceId(deviceId);
                        // Fetch more info from old endpoint
                        VerisureSmartLock smartLockThing = getJSONVerisureAPI(SMARTLOCK_PATH + slThing.getDeviceId(),
                                VerisureSmartLock.class);
                        logger.debug("REST Response ({})", smartLockThing);
                        slThing.setSmartLockJSON(smartLockThing);
                        notifyListenersIfChanged(slThing, installation, deviceId);
                    }
                }
            } else {
                logger.debug("Failed to update SmartLockStatus thing: {}, thing.getData: {}", thing, thing.getData());
            }
        }
    }

    private synchronized void updateSmartPlugStatus(Class<? extends VerisureThing> jsonClass,
            @Nullable VerisureInstallation installation) {
        if (installation != null) {
            BigDecimal installationId = installation.getInstallationId();
            String url = START_GRAPHQL;

            String queryQLSmartPlug = "{\"operationName\":\"SmartPlug\",\"variables\":{\"giid\":\"" + installationId
                    + "\"},\"query\":\"query SmartPlug($giid: String!) {\\n installation(giid: $giid) {\\n smartplugs {\\n device {\\n deviceLabel\\n area\\n __typename\\n }\\n currentState\\n icon\\n isHazardous\\n __typename\\n }\\n __typename\\n }\\n}\\n\"}";
            logger.debug("Trying to get smart plug status with URL {} and data {}", url, queryQLSmartPlug);
            VerisureSmartPlugs thing = (VerisureSmartPlugs) postJSONVerisureAPI(url, queryQLSmartPlug, jsonClass);
            logger.debug("REST Response ({})", thing);

            if (thing != null && thing.getData() != null) {
                List<VerisureSmartPlugs.Smartplug> smartPlugList = thing.getData().getInstallation().getSmartplugs();
                for (VerisureSmartPlugs.Smartplug smartPlug : smartPlugList) {
                    VerisureSmartPlugs spThing = new VerisureSmartPlugs();
                    VerisureSmartPlugs.Installation inst = new VerisureSmartPlugs.Installation();
                    List<VerisureSmartPlugs.Smartplug> list = new ArrayList<VerisureSmartPlugs.Smartplug>();
                    list.add(smartPlug);
                    inst.setSmartplugs(list);
                    VerisureSmartPlugs.Data data = new VerisureSmartPlugs.Data();
                    data.setInstallation(inst);
                    spThing.setData(data);
                    // Set unique deviceID
                    String deviceId = smartPlug.getDevice().getDeviceLabel();
                    if (deviceId != null) {
                        // Set location
                        spThing.setLocation(smartPlug.getDevice().getArea());
                        notifyListenersIfChanged(spThing, installation, deviceId);
                    }
                }
            } else {
                logger.debug("Failed to update SmartPlugStatus thing: {}, thing.getData: {}", thing, thing.getData());
            }
        }
    }

    private synchronized void updateClimateStatus(Class<? extends VerisureThing> jsonClass,
            @Nullable VerisureInstallation installation) {
        if (installation != null) {
            BigDecimal installationId = installation.getInstallationId();
            String url = START_GRAPHQL;

            String queryQLClimates = "{\"operationName\":\"Climate\",\"variables\":{\"giid\":\"" + installationId
                    + "\"},\"query\":\"query Climate($giid: String!) {\\n installation(giid: $giid) {\\n climates {\\n device {\\n deviceLabel\\n area\\n gui {\\n label\\n __typename\\n }\\n __typename\\n }\\n humidityEnabled\\n humidityTimestamp\\n humidityValue\\n temperatureTimestamp\\n temperatureValue\\n __typename\\n }\\n __typename\\n }\\n}\\n\"}";
            logger.debug("Trying to get climate status with URL {} and data {}", url, queryQLClimates);
            VerisureClimates thing = (VerisureClimates) postJSONVerisureAPI(url, queryQLClimates, jsonClass);
            logger.debug("REST Response ({})", thing);

            if (thing != null && thing.getData() != null) {
                List<VerisureClimates.Climate> climateList = thing.getData().getInstallation().getClimates();
                for (VerisureClimates.Climate climate : climateList) {
                    // If thing is Mouse detection, then skip it
                    String type = climate.getDevice().getGui().getLabel();
                    if ("MOUSE".equals(type)) {
                        logger.debug("Mouse detection device!");
                        String deviceId = climate.getDevice().getDeviceLabel();
                        deviceId = deviceId.replaceAll("[^a-zA-Z0-9]+", "");
                        VerisureThing mouseThing = verisureThings.get(deviceId);
                        if (mouseThing != null && mouseThing instanceof VerisureMiceDetection) {
                            VerisureMiceDetection miceDetectorThing = (VerisureMiceDetection) mouseThing;
                            miceDetectorThing.setTemperatureValue(climate.getTemperatureValue());
                            miceDetectorThing.setTemperatureTime(climate.getTemperatureTimestamp());
                            logger.debug("Found climate thing for a Verisure Mouse Detector");
                        }
                        continue;
                    }
                    VerisureClimates cThing = new VerisureClimates();
                    VerisureClimates.Installation inst = new VerisureClimates.Installation();
                    List<VerisureClimates.Climate> list = new ArrayList<VerisureClimates.Climate>();
                    list.add(climate);
                    inst.setClimates(list);
                    VerisureClimates.Data data = new VerisureClimates.Data();
                    data.setInstallation(inst);
                    cThing.setData(data);
                    // Set unique deviceID
                    String deviceId = climate.getDevice().getDeviceLabel();
                    if (deviceId != null) {
                        // Set location
                        cThing.setLocation(climate.getDevice().getArea());
                        notifyListenersIfChanged(cThing, installation, deviceId);
                    }
                }
            } else {
                logger.debug("Failed to update ClimateStatus thing: {}, thing.getData: {}", thing, thing.getData());
            }
        }
    }

    private synchronized void updateDoorWindowStatus(Class<? extends VerisureThing> jsonClass,
            @Nullable VerisureInstallation installation) {
        if (installation != null) {
            BigDecimal installationId = installation.getInstallationId();
            String url = START_GRAPHQL;

            String queryQLDoorWindow = "{\"operationName\":\"DoorWindow\",\"variables\":{\"giid\":\"" + installationId
                    + "\"},\"query\":\"query DoorWindow($giid: String!) {\\n installation(giid: $giid) {\\n doorWindows {\\n device {\\n deviceLabel\\n area\\n __typename\\n }\\n type\\n state\\n wired\\n reportTime\\n __typename\\n }\\n __typename\\n }\\n}\\n\"}";
            logger.debug("Trying to get climate status with URL {} and data {}", url, queryQLDoorWindow);
            VerisureDoorWindows thing = (VerisureDoorWindows) postJSONVerisureAPI(url, queryQLDoorWindow, jsonClass);
            logger.debug("REST Response ({})", thing);

            if (thing != null && thing.getData() != null) {
                List<VerisureDoorWindows.DoorWindow> doorWindowList = thing.getData().getInstallation()
                        .getDoorWindows();
                for (VerisureDoorWindows.DoorWindow doorWindow : doorWindowList) {
                    VerisureDoorWindows dThing = new VerisureDoorWindows();
                    VerisureDoorWindows.Installation inst = new VerisureDoorWindows.Installation();
                    List<VerisureDoorWindows.DoorWindow> list = new ArrayList<VerisureDoorWindows.DoorWindow>();
                    list.add(doorWindow);
                    inst.setDoorWindows(list);
                    VerisureDoorWindows.Data data = new VerisureDoorWindows.Data();
                    data.setInstallation(inst);
                    dThing.setData(data);
                    // Set unique deviceID
                    String deviceId = doorWindow.getDevice().getDeviceLabel();
                    if (deviceId != null) {
                        // Set location
                        dThing.setLocation(doorWindow.getDevice().getArea());
                        notifyListenersIfChanged(dThing, installation, deviceId);
                    }
                }
            } else {
                logger.debug("Failed to update DoorWindowStatus thing: {}, thing.getData: {}", thing, thing.getData());
            }
        }
    }

    private synchronized void updateBroadbandConnectionStatus(Class<? extends VerisureThing> jsonClass,
            @Nullable VerisureInstallation inst) {
        if (inst != null) {
            BigDecimal installationId = inst.getInstallationId();
            String url = START_GRAPHQL;

            String queryQLBroadbandConnection = "{\"operationName\":\"Broadband\",\"variables\":{\"giid\":\""
                    + installationId
                    + "\"},\"query\":\"query Broadband($giid: String!) {\\n installation(giid: $giid) {\\n broadband {\\n testDate\\n isBroadbandConnected\\n __typename\\n }\\n __typename\\n }\\n}\\n\"}";
            logger.debug("Trying to get alarm status with URL {} and data {}", url, queryQLBroadbandConnection);
            VerisureThing thing = postJSONVerisureAPI(url, queryQLBroadbandConnection, jsonClass);
            logger.debug("REST Response ({})", thing);

            if (thing != null) {
                // Set unique deviceID
                String deviceId = "bc" + installationId.toString();
                notifyListenersIfChanged(thing, inst, deviceId);
            } else {
                logger.debug("Failed to update BroadbandConnection thing: {}", thing);
            }
        }
    }

    private synchronized void updateUserPresenceStatus(Class<? extends VerisureThing> jsonClass,
            @Nullable VerisureInstallation installation) {
        if (installation != null) {
            BigDecimal installationId = installation.getInstallationId();
            String url = START_GRAPHQL;

            String queryQLUserPresence = "{\"operationName\":\"userTrackings\",\"variables\":{\"giid\":\""
                    + installationId
                    + "\"},\"query\":\"query userTrackings($giid: String!) {\\n  installation(giid: $giid) {\\n    userTrackings {\\n      isCallingUser\\n      webAccount\\n      status\\n      xbnContactId\\n      currentLocationName\\n      deviceId\\n      name\\n      currentLocationTimestamp\\n      deviceName\\n      currentLocationId\\n      __typename\\n    }\\n    __typename\\n  }\\n}\\n\"}";
            logger.debug("Trying to get user presence status with URL {} and data {}", url, queryQLUserPresence);
            VerisureUserPresences thing = (VerisureUserPresences) postJSONVerisureAPI(url, queryQLUserPresence,
                    jsonClass);
            logger.debug("REST Response ({})", thing);

            if (thing != null && thing.getData() != null) {
                List<VerisureUserPresences.UserTracking> userTrackingList = thing.getData().getInstallation()
                        .getUserTrackings();
                for (VerisureUserPresences.UserTracking userTracking : userTrackingList) {
                    if (userTracking.getStatus().equals("ACTIVE")) {
                        VerisureUserPresences upThing = new VerisureUserPresences();
                        VerisureUserPresences.Installation inst = new VerisureUserPresences.Installation();
                        List<VerisureUserPresences.UserTracking> list = new ArrayList<VerisureUserPresences.UserTracking>();
                        list.add(userTracking);
                        inst.setUserTrackings(list);
                        VerisureUserPresences.Data data = new VerisureUserPresences.Data();
                        data.setInstallation(inst);
                        upThing.setData(data);
                        // Set unique deviceID
                        String deviceId = "up" + userTracking.getWebAccount() + installationId.toString();
                        notifyListenersIfChanged(upThing, installation, deviceId);
                    }
                }
            } else {
                logger.debug("Failed to update UserPresenceStatus thing: {}, thing.getData: {}", thing,
                        thing.getData());
            }
        }
    }

    private synchronized void updateMiceDetectionStatus(Class<? extends VerisureThing> jsonClass,
            @Nullable VerisureInstallation installation) {
        if (installation != null) {
            BigDecimal installationId = installation.getInstallationId();
            String url = START_GRAPHQL;

            String queryQLMiceDetection = "{\"operationName\":\"Mouse\",\"variables\":{\"giid\":\"" + installationId
                    + "\"},\"query\":\"query Mouse($giid: String!) {\\n installation(giid: $giid) {\\n mice {\\n device {\\n deviceLabel\\n area\\n gui {\\n support\\n __typename\\n}\\n __typename\\n}\\n type\\n detections {\\n count\\n gatewayTime\\n nodeTime\\n duration\\n __typename\\n}\\n __typename\\n}\\n __typename\\n}\\n}\\n\"}";
            logger.debug("Trying to get mice detection status with URL {} and data {}", url, queryQLMiceDetection);
            VerisureMiceDetection thing = (VerisureMiceDetection) postJSONVerisureAPI(url, queryQLMiceDetection,
                    jsonClass);
            logger.debug("REST Response ({})", thing);

            if (thing != null && thing.getData() != null) {
                List<VerisureMiceDetection.Mouse> miceList = thing.getData().getInstallation().getMice();
                for (Mouse mouse : miceList) {
                    VerisureMiceDetection miceThing = new VerisureMiceDetection();
                    VerisureMiceDetection.Installation inst = new VerisureMiceDetection.Installation();
                    List<VerisureMiceDetection.Mouse> list = new ArrayList<VerisureMiceDetection.Mouse>();
                    list.add(mouse);
                    inst.setMice(list);
                    VerisureMiceDetection.Data data = new VerisureMiceDetection.Data();
                    data.setInstallation(inst);
                    miceThing.setData(data);
                    // Set unique deviceID
                    String deviceId = mouse.getDevice().getDeviceLabel();
                    logger.debug("Mouse id: {} for thing: {}", deviceId, mouse);
                    if (deviceId != null) {
                        // Set location
                        miceThing.setLocation(mouse.getDevice().getArea());
                        notifyListenersIfChanged(miceThing, installation, deviceId);
                    }
                }
            } else {
                logger.debug("Failed to update Mice Detection Status thing: {}, thing.getData: {}", thing,
                        thing.getData());
            }
        }
    }
}
