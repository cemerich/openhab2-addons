/**
 * Copyright (c) 2010-2019 Contributors to the openHAB project
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
package org.openhab.binding.vwcarnet.internal;

import static org.openhab.binding.vwcarnet.internal.VWCarNetBindingConstants.*;

import java.math.BigDecimal;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.Fields;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.openhab.binding.vwcarnet.internal.model.BaseVehicle;
import org.openhab.binding.vwcarnet.internal.model.Details;
import org.openhab.binding.vwcarnet.internal.model.Location;
import org.openhab.binding.vwcarnet.internal.model.Status;
import org.openhab.binding.vwcarnet.internal.model.Trips;
import org.openhab.binding.vwcarnet.internal.model.Vehicle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

/**
 * This class performs the communication with VWCarNet My Pages.
 *
 * @author Jan Gustafsson - Initial contribution
 *
 */
@NonNullByDefault
public class VWCarNetSession {

    private final HashMap<String, BaseVehicle> vwCarNetThings = new HashMap<String, BaseVehicle>();
    private final Logger logger = LoggerFactory.getLogger(VWCarNetSession.class);
    private final Gson gson = new GsonBuilder().create();
    private final List<DeviceStatusListener> deviceStatusListeners = new CopyOnWriteArrayList<>();
    private static final List<String> APISERVERLIST = new ArrayList<>(
            Arrays.asList("https://m-api01.verisure.com", "https://m-api02.verisure.com"));
    private int apiServerInUseIndex = 0;
    private String apiServerInUse = APISERVERLIST.get(apiServerInUseIndex);
    private boolean areWeLoggedOut = true;
    private String authstring = "";

    private @Nullable String csrf;
    private @Nullable String xCsrfToken;
    private @Nullable String referer;
    private @Nullable String authRefUrl;
    private @Nullable String guestLanguageId;
    private @Nullable String pinCode;
    private HttpClient httpClient;
    private @Nullable String userName = "";
    private String passwordName = "vid";
    private @Nullable String password = "";

    public VWCarNetSession(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public void initialize(@Nullable String userName, @Nullable String password, @Nullable String pinCode) {
        logger.debug("VWCarNetSession:initialize");
        this.userName = userName;
        this.password = password;
        this.pinCode = pinCode;
        // Try to login to VWCarNet
        if (!logIn()) {
            logger.warn("Failed to login to VWCarNet!");
        }
    }

    public boolean refresh() {
        logger.debug("VWCarNetSession:refresh");

        if (!areWeLoggedOut && areWeLoggedIn()) {
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

    public @Nullable BaseVehicle getVWCarNetThing(String deviceId) {
        return vwCarNetThings.get(deviceId);
    }

    public HashMap<String, BaseVehicle> getVWCarNetThings() {
        return vwCarNetThings;
    }

    public @Nullable String getCsrf() {
        return csrf;
    }

    public @Nullable String getPinCode() {
        return pinCode;
    }

    public @Nullable String getPinCode(@Nullable BigDecimal installationId) {
        return pinCode;
    }

    private boolean isErrorCode(String jsonContent) {
        String errorCode = JsonPath.read(jsonContent, "$.errorCode");
        logger.debug("ErrorCode: {}", errorCode);
        if (errorCode.equals("0")) {
            return false;
        }
        return true;
    }

    private boolean areWeLoggedIn() {
        logger.debug("areWeLoggedIn() - Checking if we are logged in");
        String url = authRefUrl + LOGIN_CHECK;

        logger.debug("Check for login status, url: {}", url);
        Fields fields = null;
        ContentResponse httpResponse = httpResponse = postJSONVWCarNetAPI(url, fields, referer, xCsrfToken);
        if (httpResponse == null) {
            logger.debug("We are not logged in, Exception caught!");
            return false;
        }
        String content = httpResponse.getContentAsString();
        switch (httpResponse.getStatus()) {
            case HttpStatus.OK_200:
                if (!isErrorCode(content)) {
                    return true;
                }
            default:
                logger.debug("Status code {} body {}", httpResponse.getStatus(), content);
                break;
        }
        return false;
    }

    private @Nullable ContentResponse getVWCarNetAPI(String url) {
        logger.debug("HTTP GET: {}", url);
        ContentResponse httpResult = null;

        try {
            httpResult = httpClient.GET(url);
            logger.debug("HTTP Response ({}) Body:{}", httpResult.getStatus(),
                    httpResult.getContentAsString().replaceAll("\n+", "\n"));
            return httpResult;
        } catch (ExecutionException e) {
            logger.warn("Caught ExecutionException {} for URL string {}", e, url);
        } catch (InterruptedException e) {
            logger.warn("Caught InterruptedException {} for URL string {}", e, url);
        } catch (TimeoutException e) {
            logger.warn("Caught TimeoutException {} for URL string {}", e, url);
        }
        return httpResult;
    }

    private @Nullable ContentResponse getVWCarNetAPI(String url, Boolean headers) {
        try {
            logger.debug("getVWCarNetAPI URL: {} headers: {}", url, headers);
            URL urlEncoded = new URL(url);
            /*
             * String correctEncodedURL = urlEncoded.getProtocol() + urlEncoded.getUserInfo() + urlEncoded.getHost()
             * + urlEncoded.getPort() + urlEncoded.getPath() + URLEncoder.encode(urlEncoded.getQuery(), "UTF-8")
             * + urlEncoded.getRef();
             */
            String correctEncodedURL = url.replace(" ", "%20");
            logger.warn("Encoded URL: {}", correctEncodedURL);

            httpClient.setFollowRedirects(false);
            Request request = httpClient.newRequest(correctEncodedURL).method(HttpMethod.GET);
            if (headers) {
                request.header("accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3");
                request.header("content-type", "application/x-www-form-urlencoded");
                request.header("user-agent",
                        "Mozilla/5.0 (Linux; Android 6.0.1; D5803 Build/23.5.A.1.291; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/63.0.3239.111 Mobile Safari/537.36");
            }
            logger.warn("HTTP GET Request {}.", request);
            return request.send();
        } catch (ExecutionException e) {
            logger.warn("Caught ExecutionException {}", e);
        } catch (InterruptedException e) {
            logger.warn("Caught InterruptedException {}", e);
        } catch (TimeoutException e) {
            logger.warn("Caught TimeoutException {}", e);
        } catch (RuntimeException e) {
            logger.warn("Caught RuntimeException {}", e);
        } catch (MalformedURLException e) {
            logger.warn("Caught MalformedURLException {}", e);
        }
        return null;
    }

    private @Nullable ContentResponse postVWCarNetAPI(String url, @Nullable Fields fields, @Nullable String referer,
            String csrf) {
        try {
            logger.debug("postVWCarNetAPI URL: {} Fields: {} Referer: {} CSRF:{}", url, fields, referer, csrf);
            Request request = httpClient.newRequest(url).method(HttpMethod.POST);

            request.header("accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3");
            request.header("content-type", "application/x-www-form-urlencoded");
            request.header("user-agent",
                    "Mozilla/5.0 (Linux; Android 6.0.1; D5803 Build/23.5.A.1.291; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/63.0.3239.111 Mobile Safari/537.36");
            request.header("referer", SESSION_BASE + referer);

            if (fields != null) {
                fields.forEach(f -> request.param(f.getName(), f.getValue()));
            } else {
                request.header("x-csrf-token", csrf);
            }
            logger.debug("HTTP POST Request {}.", request.toString());
            return request.send();
        } catch (ExecutionException e) {
            logger.warn("Caught ExecutionException {}", e);
        } catch (InterruptedException e) {
            logger.warn("Caught InterruptedException {}", e);
        } catch (TimeoutException e) {
            logger.warn("Caught TimeoutException {}", e);
        } catch (RuntimeException e) {
            logger.warn("Caught RuntimeException {}", e);
        }
        return null;
    }

    private @Nullable ContentResponse postJSONVWCarNetAPI(String url, @Nullable Fields fields, @Nullable String referer,
            @Nullable String xCsrf) {
        try {
            logger.debug("postJSONVWCarNetAPI URL: {} Fields: {} Referer: {} XCSRF:{}", url, fields, referer, xCsrf);
            Request request = httpClient.newRequest(url).method(HttpMethod.POST);

            request.header("accept", "application/json, text/plain, */*");
            request.header("content-type", "application/json;charset=UTF-8");
            request.header("user-agent",
                    "Mozilla/5.0 (Linux; Android 6.0.1; D5803 Build/23.5.A.1.291; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/63.0.3239.111 Mobile Safari/537.36");
            request.header("referer", referer);
            request.header("x-csrf-token", xCsrf);

            if (fields != null) {
                fields.forEach(f -> request.param(f.getName(), f.getValue()));
            }

            logger.debug("HTTP POST Request {}.", request.toString());
            return request.send();
        } catch (ExecutionException e) {
            logger.warn("Caught ExecutionException {}", e);
        } catch (InterruptedException e) {
            logger.warn("Caught InterruptedException {}", e);
        } catch (TimeoutException e) {
            logger.warn("Caught TimeoutException {}", e);
        } catch (RuntimeException e) {
            logger.warn("Caught RuntimeException {}", e);
        }
        return null;
    }

    private @Nullable <T> T postJSONVWCarNetAPI(String url, @Nullable Fields fields, Class<T> jsonClass) {
        try {
            logger.debug("postJSONVWCarNetAPI URL: {} Fields: {}", url, fields);
            Request request = httpClient.newRequest(url).method(HttpMethod.POST);

            request.header("accept", "application/json, text/plain, */*");
            request.header("content-type", "application/json;charset=UTF-8");
            request.header("user-agent",
                    "Mozilla/5.0 (Linux; Android 6.0.1; D5803 Build/23.5.A.1.291; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/63.0.3239.111 Mobile Safari/537.36");
            request.header("referer", referer);
            request.header("x-csrf-token", xCsrfToken);

            if (fields != null) {
                fields.forEach(f -> request.param(f.getName(), f.getValue()));
            }

            logger.debug("HTTP POST Request {}.", request.toString());
            ContentResponse httpResponse = request.send();
            if (httpResponse != null) {
                String content = httpResponse.getContentAsString();
                if (isErrorCode(content)) {
                    logger.warn("Error code on POST: {}", content);
                    return null;
                }
                return gson.fromJson(content, jsonClass);
            }
        } catch (ExecutionException e) {
            logger.warn("Caught ExecutionException {}", e);
        } catch (InterruptedException e) {
            logger.warn("Caught InterruptedException {}", e);
        } catch (TimeoutException e) {
            logger.warn("Caught TimeoutException {}", e);
        } catch (RuntimeException e) {
            logger.warn("Caught RuntimeException {}", e);
        }
        return null;
    }

    private synchronized boolean logIn() {
        logger.info("Attempting to log in to https://www.portal.volkswagen-we.com");
        // Request landing page and get CSRF:
        String url = SESSION_BASE + "/portal/en_GB/web/guest/home";
        logger.debug("Login URL: {}", url);
        ContentResponse httpResponse = getVWCarNetAPI(url);
        if (httpResponse == null) {
            logger.debug("Failed to login, Exception caught!");
            return false;
        } else if (httpResponse.getStatus() != HttpStatus.OK_200) {
            logger.debug("Failed to login, HTTP status code: {}", httpResponse.getStatus());
            return false;
        }
        Document htmlDocument = Jsoup.parse(httpResponse.getContentAsString());
        Element nameInput = htmlDocument.select("meta[name=_csrf]").first();
        String csrf = nameInput.attr("content");

        // Request login page and get login URL
        url = SESSION_BASE + "portal/web/guest/home/-/csrftokenhandling/get-login-url";
        httpResponse = postVWCarNetAPI(url, null, "portal", csrf);
        String json = httpResponse.getContentAsString();
        JsonObject jsonObject = new JsonParser().parse(json).getAsJsonObject();
        url = jsonObject.get("loginURL").getAsJsonObject().get("path").getAsString();

        httpResponse = getVWCarNetAPI(url, Boolean.TRUE);
        if (httpResponse == null) {
            logger.debug("Failed to login, Exception caught!");
            return false;
        } else if (httpResponse.getStatus() != HttpStatus.MOVED_TEMPORARILY_302) {
            logger.debug("Failed to login, HTTP status code: {}", httpResponse.getStatus());
            return false;
        }

        url = httpResponse.getHeaders().get("Location");
        httpResponse = getVWCarNetAPI(url, Boolean.TRUE);
        if (httpResponse == null) {
            logger.debug("Failed to login, Exception caught!");
            return false;
        } else if (httpResponse.getStatus() != HttpStatus.OK_200) {
            logger.debug("Failed to login, HTTP status code: {}", httpResponse.getStatus());
            return false;
        }

        String previousUrl = url;

        // Get actual login page and get session id and ViewState
        htmlDocument = Jsoup.parse(httpResponse.getContentAsString());
        nameInput = htmlDocument.select("input[name=_csrf]").first();
        String loginCsrf = nameInput.attr("value");
        nameInput = htmlDocument.select("input[name=relayState]").first();
        String loginToken = nameInput.attr("value");
        nameInput = htmlDocument.select("input[name=hmac]").first();
        String loginHmac = nameInput.attr("value");
        Element loginForm = htmlDocument.getElementById("emailPasswordForm");
        url = AUTH_BASE + loginForm.attr("action");

        Fields fields = new Fields(true);
        fields.put("email", userName);
        fields.put("password", password);
        fields.put("relayState", loginToken);
        fields.put("hmac", loginHmac);
        fields.put("_csrf", loginCsrf);

        httpResponse = postVWCarNetAPI(url, fields, previousUrl, csrf);
        if (httpResponse == null) {
            logger.debug("Failed to login, Exception caught!");
            return false;
        } else if (httpResponse.getStatus() != HttpStatus.SEE_OTHER_303) {
            logger.debug("Failed to login, HTTP status code: {}", httpResponse.getStatus());
            return false;
        }

        url = httpResponse.getHeaders().get("Location");
        url = AUTH_BASE + url;
        httpResponse = getVWCarNetAPI(url, Boolean.TRUE);
        if (httpResponse == null) {
            logger.debug("Failed to login, Exception caught!");
            return false;
        } else if (httpResponse.getStatus() != HttpStatus.OK_200) {
            logger.debug("Failed to login, HTTP status code: {}", httpResponse.getStatus());
            return false;
        }

        previousUrl = url;
        htmlDocument = Jsoup.parse(httpResponse.getContentAsString());
        nameInput = htmlDocument.select("input[name=_csrf]").first();
        String authCsrf = nameInput.attr("value");
        nameInput = htmlDocument.select("input[name=relayState]").first();
        String authToken = nameInput.attr("value");
        nameInput = htmlDocument.select("input[name=hmac]").first();
        String authHmac = nameInput.attr("value");
        Element authForm = htmlDocument.getElementById("credentialsForm");
        url = AUTH_BASE + authForm.attr("action");

        fields = new Fields(true);
        fields.put("email", userName);
        fields.put("password", password);
        fields.put("relayState", authToken);
        fields.put("hmac", authHmac);
        fields.put("_csrf", authCsrf);

        httpResponse = postVWCarNetAPI(url, fields, previousUrl, csrf);
        if (httpResponse == null) {
            logger.debug("Failed to login, Exception caught!");
            return false;
        } else if (httpResponse.getStatus() != HttpStatus.MOVED_TEMPORARILY_302) {
            logger.debug("Failed to login, HTTP status code: {}", httpResponse.getStatus());
            return false;
        }

        url = httpResponse.getHeaders().get("Location");
        httpResponse = getVWCarNetAPI(url, Boolean.TRUE);
        if (httpResponse == null) {
            logger.debug("Failed to login, Exception caught!");
            return false;
        } else if (httpResponse.getStatus() != HttpStatus.MOVED_TEMPORARILY_302) {
            logger.debug("Failed to login, HTTP status code: {}", httpResponse.getStatus());
            return false;
        }

        url = httpResponse.getHeaders().get("Location");
        httpResponse = getVWCarNetAPI(url, Boolean.TRUE);
        if (httpResponse == null) {
            logger.debug("Failed to login, Exception caught!");
            return false;
        } else if (httpResponse.getStatus() != HttpStatus.MOVED_TEMPORARILY_302) {
            logger.debug("Failed to login, HTTP status code: {}", httpResponse.getStatus());
            return false;
        }

        url = httpResponse.getHeaders().get("Location");
        httpResponse = getVWCarNetAPI(url, Boolean.TRUE);
        if (httpResponse == null) {
            logger.debug("Failed to login, Exception caught!");
            return false;
        } else if (httpResponse.getStatus() != HttpStatus.MOVED_TEMPORARILY_302) {
            logger.debug("Failed to login, HTTP status code: {}", httpResponse.getStatus());
            return false;
        }

        url = httpResponse.getHeaders().get("Location");
        previousUrl = url;
        URI uri;
        String state = "", code = "", path = "";

        try {
            uri = new URI(url);
            String[] queryStrings = uri.getQuery().split("&");
            for (String query : queryStrings) {
                String[] nameValuePair = query.split("=");
                if (nameValuePair[0].equals("state")) {
                    state = nameValuePair[1];
                } else if (nameValuePair[0].equals("code")) {
                    code = nameValuePair[1];
                }
            }
            path = uri.getPath();
        } catch (URISyntaxException e) {
            logger.warn("Failed to login, caught URISyntaxException {}", e);
            return false;
        }

        fields = new Fields(true);
        fields.put("_33_WAR_cored5portlet_code", code);
        fields.put("_33_WAR_cored5portlet_landingPageUrl", "");

        url = SESSION_BASE + path + "?p_auth=" + state
                + "&p_p_id=33_WAR_cored5portlet&p_p_lifecycle=1&p_p_state=normal&p_p_mode=view&p_p_col_id=column-1&p_p_col_count=1&_33_WAR_cored5portlet_javax.portlet.action=getLoginStatus";

        httpResponse = postVWCarNetAPI(url, fields, previousUrl, csrf);
        if (httpResponse == null) {
            logger.debug("Failed to login, Exception caught!");
            return false;
        } else if (httpResponse.getStatus() != HttpStatus.MOVED_TEMPORARILY_302) {
            logger.debug("Failed to login, HTTP status code: {}", httpResponse.getStatus());
            return false;
        }

        url = httpResponse.getHeaders().get("Location");
        httpResponse = getVWCarNetAPI(url, Boolean.TRUE);
        if (httpResponse == null) {
            logger.debug("Failed to login, Exception caught!");
            return false;
        } else if (httpResponse.getStatus() != HttpStatus.OK_200) {
            logger.debug("Failed to login, HTTP status code: {}", httpResponse.getStatus());
            return false;
        }

        htmlDocument = Jsoup.parse(httpResponse.getContentAsString());
        nameInput = htmlDocument.select("meta[name=_csrf]").first();
        xCsrfToken = nameInput.attr("content");
        referer = url;
        authRefUrl = url + "/";

        CookieStore cookieStore = httpClient.getCookieStore();
        List<HttpCookie> cookies = cookieStore.get(URI.create("www.portal.volkswagen-we.com"));
        cookies.forEach(cookie -> {
            logger.debug("Cookie: {}", cookie);
            if (cookie.getName().equals("GUEST_LANGUAGE_ID")) {
                guestLanguageId = cookie.getValue();
                logger.debug("Fetching guest language id {} from cookie", guestLanguageId);
            }
        });

        fields = null;
        String content = null;
        logger.warn("get-fully-loaded-cars");
        url = authRefUrl + "-/mainnavigation/get-fully-loaded-cars";
        httpResponse = postJSONVWCarNetAPI(url, fields, referer, xCsrfToken);
        String myVin = "";
        if (httpResponse != null) {
            content = httpResponse.getContentAsString();
            logger.warn(content);
            DocumentContext context = JsonPath.parse(content);
            String jsonpathVehiclesNotFullyLoadedPath = "$['fullyLoadedVehiclesResponse']['vehiclesNotFullyLoaded'][*]";
            List<Object> vehicleList = context.read(jsonpathVehiclesNotFullyLoadedPath);

            context = JsonPath.parse(vehicleList);
            List<Object> vinList = context.read("$[*]['vin']");
            for (Object vin : vinList) {
                logger.warn("VIN: {}", vin);
                myVin = (String) vin;
            }
        }

        logger.warn("get-status");
        url = authRefUrl + "-/rah/get-status";
        httpResponse = postJSONVWCarNetAPI(url, fields, referer, xCsrfToken);
        content = httpResponse.getContentAsString();
        logger.warn(content);

        logger.warn("get-vehicle-details");
        url = authRefUrl + "-/vehicle-info/get-vehicle-details";
        httpResponse = postJSONVWCarNetAPI(url, fields, referer, xCsrfToken);
        content = httpResponse.getContentAsString();
        logger.warn(content);

        logger.warn("load-car-details");
        url = authRefUrl + "-/mainnavigation/load-car-details/" + myVin;
        httpResponse = postJSONVWCarNetAPI(url, fields, referer, xCsrfToken);
        content = httpResponse.getContentAsString();
        Vehicle vehicle = gson.fromJson(content, Vehicle.class);
        logger.warn(content);

        logger.warn(VEHICLE_STATUS);
        url = authRefUrl + VEHICLE_STATUS;
        httpResponse = postJSONVWCarNetAPI(url, fields, referer, xCsrfToken);
        content = httpResponse.getContentAsString();
        Status status = gson.fromJson(content, Status.class);
        logger.warn(content);

        logger.warn(REQUEST_VEHICLE_STATUS_REPORT);
        url = authRefUrl + REQUEST_VEHICLE_STATUS_REPORT;
        httpResponse = postJSONVWCarNetAPI(url, fields, referer, xCsrfToken);
        content = httpResponse.getContentAsString();
        logger.warn(content);

        logger.warn(VEHICLE_STATUS);
        url = authRefUrl + VEHICLE_STATUS;
        httpResponse = postJSONVWCarNetAPI(url, fields, referer, xCsrfToken);
        content = httpResponse.getContentAsString();
        status = gson.fromJson(content, Status.class);
        logger.warn(content);

        while (status.getVehicleStatusData().getRequestStatus() != null
                && status.getVehicleStatusData().getRequestStatus().equals("REQUEST_IN_PROGRESS")) {
            try {
                logger.warn("Time: {}", new Timestamp(System.currentTimeMillis()));
                Thread.sleep(1000);
                httpResponse = postJSONVWCarNetAPI(url, fields, referer, xCsrfToken);
                content = httpResponse.getContentAsString();
                status = gson.fromJson(content, Status.class);
            } catch (InterruptedException e) {
                logger.warn("Exception caught: {}", e);
            }
        }

        areWeLoggedOut = false;

        return true;
    }

    private void notifyListeners(BaseVehicle thing) {
        for (DeviceStatusListener listener : deviceStatusListeners) {
            listener.onDeviceStateChanged(thing);
        }
    }

    private void updateStatus() {
        logger.debug("VWCarNetSession:updateStatus");
        updateVehicleStatus(Vehicle.class);
    }

    private synchronized void updateVehicleStatus(Class<? extends Vehicle> jsonClass) {
        Fields fields = null;
        String content = null;
        String url = authRefUrl + "-/mainnavigation/get-fully-loaded-cars";
        ContentResponse httpResponse = postJSONVWCarNetAPI(url, fields, referer, xCsrfToken);
        if (httpResponse != null) {
            content = httpResponse.getContentAsString();
            if (isErrorCode(content)) {
                logger.warn("Failed to update vehicle status.");
                return;
            }
            DocumentContext context = JsonPath.parse(content);
            String jsonpathVehiclesNotFullyLoadedPath = "$['fullyLoadedVehiclesResponse']['completeVehicles'][*]";
            List<Object> vehicleList = context.read(jsonpathVehiclesNotFullyLoadedPath);

            context = JsonPath.parse(vehicleList);
            List<Object> vinList = context.read("$[*]['vin']");
            List<Object> dashboarUrlList = context.read("$[*]['dashboardUrl']");

            for (int i = 0; i < vinList.size(); i++) {
                String vin = (String) vinList.get(i);
                String dashboardUrl = (String) dashboarUrlList.get(i);

                // Request a Vehicle Status report to be sent from vehicle
                url = authRefUrl + REQUEST_VEHICLE_STATUS_REPORT;
                httpResponse = postJSONVWCarNetAPI(url, fields, referer, xCsrfToken);
                content = httpResponse.getContentAsString();
                logger.warn("API Response ({})", content);

                // Query API for vehicle details for this VIN
                url = SESSION_BASE + dashboardUrl + VEHICLE_DETAILS + vin;
                Vehicle vehicle = postJSONVWCarNetAPI(url, fields, Vehicle.class);
                logger.warn("API Response ({})", vehicle);

                // Query API for more specific vehicle details
                url = SESSION_BASE + dashboardUrl + VEHICLE_DETAILS_SPECIFIC;
                Details vehicleDetails = postJSONVWCarNetAPI(url, fields, Details.class);
                logger.warn("API Response ({})", vehicleDetails);

                // Query API for trip statistics
                url = SESSION_BASE + dashboardUrl + TRIP_STATISTICS;
                Trips trips = postJSONVWCarNetAPI(url, fields, Trips.class);
                logger.trace("API Response ({})", trips);

                // Query API for homeLocation status
                url = SESSION_BASE + dashboardUrl + VEHICLE_LOCATION;
                Location location = postJSONVWCarNetAPI(url, fields, Location.class);
                logger.warn("API Response ({})", location);

                // Query API for vehicle status
                url = SESSION_BASE + dashboardUrl + VEHICLE_STATUS;
                Status vehicleStatus = postJSONVWCarNetAPI(url, fields, Status.class);
                logger.warn("API Response ({})", vehicleStatus);

                if (vehicle != null && vehicleDetails != null && vehicleStatus != null && trips != null
                        && location != null) {
                    vehicle.setVehicleDetails(vehicleDetails);
                    vehicle.setVehicleStatus(vehicleStatus);
                    vehicle.setTrips(trips);
                    vehicle.setVehicleLocation(location);

                    BaseVehicle oldObj = vwCarNetThings.get(vin);
                    if (oldObj == null || !oldObj.equals(vehicle)) {
                        vwCarNetThings.put(vin, vehicle);
                        notifyListeners(vehicle);
                    }
                } else {
                    logger.warn("Failed to update vehicle details for VIN: {}", vin);
                }
            }
        }
    }
}
