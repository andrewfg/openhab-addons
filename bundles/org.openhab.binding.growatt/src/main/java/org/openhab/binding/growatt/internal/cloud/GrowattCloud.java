/**
 * Copyright (c) 2010-2023 Contributors to the openHAB project
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
package org.openhab.binding.growatt.internal.cloud;

import java.lang.reflect.Type;
import java.net.HttpCookie;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.FormContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.openhab.binding.growatt.internal.config.GrowattInverterConfiguration;
import org.openhab.binding.growatt.internal.dto.GrowattDevice;
import org.openhab.binding.growatt.internal.dto.GrowattPlant;
import org.openhab.binding.growatt.internal.dto.GrowattPlantList;
import org.openhab.binding.growatt.internal.dto.GrowattUser;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

/**
 * The {@link GrowattCloud} class allows the binding to access the inverter state and settings via HTTP calls to the
 * remote Growatt cloud API server (instead of receiving the data from the local Grott proxy server).
 * <p>
 * This class is necessary since the Grott proxy server does not (yet) support easy access to some inverter register
 * settings, such as the settings for the battery charging and discharging programs.
 *
 * @author Andrew Fiddian-Green - Initial contribution
 */
@NonNullByDefault
public class GrowattCloud implements AutoCloseable {

    // JSON field names for the battery charging program
    public static final String CHARGE_PROGRAM_POWER = "chargePowerCommand";
    public static final String CHARGE_PROGRAM_TARGET_SOC = "wchargeSOCLowLimit2";
    public static final String CHARGE_PROGRAM_ALLOW_AC_CHARGING = "acChargeEnable";
    public static final String CHARGE_PROGRAM_START_TIME = "forcedChargeTimeStart1";
    public static final String CHARGE_PROGRAM_STOP_TIME = "forcedChargeTimeStop1";
    public static final String CHARGE_PROGRAM_ENABLE = "forcedChargeStopSwitch1";

    // JSON field names for the battery discharging program
    public static final String DISCHARGE_PROGRAM_POWER = "disChargePowerCommand";
    public static final String DISCHARGE_PROGRAM_TARGET_SOC = "wdisChargeSOCLowLimit2";
    public static final String DISCHARGE_PROGRAM_START_TIME = "forcedDischargeTimeStart1";
    public static final String DISCHARGE_PROGRAM_STOP_TIME = "forcedDischargeTimeStop1";
    public static final String DISCHARGE_PROGRAM_ENABLE = "forcedDischargeStopSwitch1";

    // API server URL
    private static final String SERVER_URL = "https://server-api.growatt.com/";

    // API end points
    private static final String LOGIN_API = "newTwoLoginAPI.do";
    private static final String PLANT_LIST_API = "PlantListAPI.do";
    private static final String PLANT_INFO_API = "newTwoPlantAPI.do";
    private static final String NEW_TCP_SET_API = "newTcpsetAPI.do";

    // command operations
    private static final String OP_GET_ALL_DEVICE_LIST = "getAllDeviceList";

    // format strings
    private static final String FMT_TYPE_PARAM = "%s_ac_%s_time_period";
    private static final String FMT_API_PATH = "new%sApi.do";

    /*
     * map of device types vs. GET 'op' parameters
     * note: some values are guesses which have not yet been confirmed by users
     */
    private static final Map<String, String> SUPPORTED_TYPES_OP_PARAM_GET = Map.of(
    // @formatter:off
            "max", "getMaxSetData",
            "min", "getMinSetParams",
            "mix", "getMixSetParams",
            "spa", "getSpaSetData",
            "sph", "getSphSetData",
            "tlx", "getTlxSetData"
    // @formatter:on
    );

    /*
     * map of device types vs. POST (set) 'op' parameters
     * note: some values are guesses which have not yet been confirmed by users
     */
    private static final Map<String, String> SUPPORTED_TYPES_OP_PARAM_SET = Map.of(
    // @formatter:off
            "max", "maxSetApi",
            "min", "minSetApi",
            "mix", "mixSetApiNew", // NB previously "mixSetApi"
            "spa", "spaSetApi",
            "sph", "sphSet",
            "tlx", "tlxSet"
    // @formatter:on
    );

    private static final Set<String> GUESSED_TYPES = Set.of("max", "min", "spa", "sph", "tlx");

    // enum to select charge resp. discharge program
    private static enum ProgramType {
        CHARGE,
        DISCHARGE
    }

    // @formatter:off
    private static final Type DEVICE_LIST_TYPE = new TypeToken<List<GrowattDevice>>() {}.getType();
    // @formatter:on

    // HTTP headers (user agent is spoofed to mimic the Growatt Android Shine app)
    private static final String USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 12; https://www.openhab.org)";
    private static final String FORM_CONTENT = "application/x-www-form-urlencoded";

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final Logger logger = LoggerFactory.getLogger(GrowattCloud.class);
    private final HttpClient httpClient;
    private final GrowattInverterConfiguration configuration;
    private final Gson gson = new Gson();
    private final List<String> plantIds = new ArrayList<>();
    private final Map<String, String> deviceIdTypeMap = new ConcurrentHashMap<>();

    private String userId = "";

    /**
     * Constructor.
     *
     * @param configuration the thing configuration parameters.
     * @param httpClientFactory the OH core {@link HttpClientFactory} instance.
     * @throws Exception if anything goes wrong.
     */
    public GrowattCloud(GrowattInverterConfiguration configuration, HttpClientFactory httpClientFactory)
            throws Exception {
        this.configuration = configuration;
        this.httpClient = httpClientFactory.createHttpClient("growatt-cloud-api", new SslContextFactory.Client(true));
        this.httpClient.start();
    }

    @Override
    public void close() throws Exception {
        httpClient.stop();
    }

    /**
     * Refresh the login cookies.
     *
     * @throws GrowattApiException if any error occurs.
     */
    private void refreshCookies() throws GrowattApiException {
        List<HttpCookie> cookies = httpClient.getCookieStore().getCookies();
        if (cookies.isEmpty() || cookies.stream().anyMatch(HttpCookie::hasExpired)) {
            postLoginCredentials();
        }
    }

    /**
     * Create a hash of the given password using normal MD5, except add 'c' if a byte of the digest is less than 10
     *
     * @param password the plain text password
     * @return the hash of the password
     * @throws GrowattApiException if MD5 algorithm is not supported
     */
    private static String createHash(String password) throws GrowattApiException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new GrowattApiException("Hash algorithm error", e);
        }
        byte[] bytes = md.digest(password.getBytes());
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        for (int i = 0; i < result.length(); i += 2) {
            if (result.charAt(i) == '0') {
                result.replace(i, i + 1, "c");
            }
        }
        return result.toString();
    }

    /**
     * Login to the server (if necessary) and then execute an HTTP request using the given HTTP method, to the given end
     * point, and with the given request URL parameters and/or request form fields. If the cookies are not valid first
     * login to the server before making the actual HTTP request.
     *
     * @param method the HTTP method to use.
     * @param endPoint the API end point.
     * @param params the request URL parameters (may be null).
     * @param fields the request form fields (may be null).
     * @return a Map of JSON elements containing the server response.
     * @throws GrowattApiException if any error occurs.
     */
    private Map<String, JsonElement> doHttpRequest(HttpMethod method, String endPoint,
            @Nullable Map<String, String> params, @Nullable Fields fields) throws GrowattApiException {
        refreshCookies();
        return doHttpRequestInner(method, endPoint, params, fields);
    }

    /**
     * Inner method to execute an HTTP request using the given HTTP method, to the given end point, and with the given
     * request URL parameters and/or request form fields.
     *
     * @param method the HTTP method to use.
     * @param endPoint the API end point.
     * @param params the request URL parameters (may be null).
     * @param fields the request form fields (may be null).
     * @return a Map of JSON elements containing the server response.
     * @throws GrowattApiException if any error occurs.
     */
    private Map<String, JsonElement> doHttpRequestInner(HttpMethod method, String endPoint,
            @Nullable Map<String, String> params, @Nullable Fields fields) throws GrowattApiException {
        //
        Request request = httpClient.newRequest(SERVER_URL + endPoint).method(method).agent(USER_AGENT)
                .timeout(HTTP_TIMEOUT.getSeconds(), TimeUnit.SECONDS);

        if (params != null) {
            params.entrySet().forEach(p -> request.param(p.getKey(), p.getValue()));
        }

        if (fields != null) {
            request.content(new FormContentProvider(fields), FORM_CONTENT);
        }

        // if (logger.isTraceEnabled()) {
        logger.warn("{} {}{} {} {}", method, request.getPath(), params == null ? "" : "?" + request.getQuery(),
                request.getVersion(), fields == null ? "" : "? " + FormContentProvider.convert(fields));
        // }

        ContentResponse response;
        try {
            response = request.send();
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            throw new GrowattApiException("HTTP I/O Exception", e);
        }

        int status = response.getStatus();
        String content = response.getContentAsString();

        logger.warn("HTTP {} {} {}", status, HttpStatus.getMessage(status), content);

        if (status != HttpStatus.OK_200) {
            throw new GrowattApiException(String.format("HTTP %d %s", status, HttpStatus.getMessage(status)));
        }

        if (content == null || content.isBlank()) {
            throw new GrowattApiException("Response is " + (content == null ? "null" : "blank"));
        }

        if (content.contains("<html>")) {
            logger.warn("HTTP {} {} {}", status, HttpStatus.getMessage(status), content);
            throw new GrowattApiException("Response is HTML");
        }

        try {
            JsonElement jsonObject = JsonParser.parseString(content).getAsJsonObject();
            if (jsonObject instanceof JsonObject jsonElement) {
                return jsonElement.asMap();
            }
            throw new GrowattApiException("JSON invalid response");
        } catch (JsonSyntaxException | IllegalStateException e) {
            throw new GrowattApiException("JSON syntax exception", e);
        }
    }

    /**
     * Get the device type for the given deviceId. If the deviceIdTypeMap is empty then download it freshly.
     *
     * @param the deviceId to get.
     * @return the device type or null.
     * @throws GrowattApiException if any error occurs.
     */
    private @Nullable String getDeviceType(String deviceId) throws GrowattApiException {
        if (deviceIdTypeMap.isEmpty()) {
            if (plantIds.isEmpty()) {
                refreshCookies();
            }
            for (String plantId : plantIds) {
                deviceIdTypeMap.putAll(getPlantInfo(plantId).stream()
                        .collect(Collectors.toMap(GrowattDevice::getId, GrowattDevice::getType)));
            }
            logger.warn("Downloaded deviceTypes:{}", deviceIdTypeMap);
        }
        return deviceIdTypeMap.get(deviceId);
    }

    /**
     * Get the inverter device settings.
     *
     * @return a Map of JSON elements containing the server response.
     * @throws GrowattApiException if any error occurs.
     */
    public Map<String, JsonElement> getDeviceSettings() throws GrowattApiException {
        String deviceId = configuration.deviceId;
        if (deviceId.isBlank()) {
            throw new GrowattApiException("Blank device id");
        }
        String deviceType = getDeviceType(deviceId);
        if (deviceType == null || !SUPPORTED_TYPES_OP_PARAM_GET.containsKey(deviceType)) {
            throw new GrowattApiException("Unsupported device type:" + deviceType);
        }

        Map<String, String> params = new LinkedHashMap<>(); // keep params in order
        params.put("op", Objects.requireNonNull(SUPPORTED_TYPES_OP_PARAM_GET.get(deviceType)));
        params.put("serialNum", configuration.deviceId);
        params.put("kind", "0");

        String path = String.format(FMT_API_PATH, deviceType.substring(0, 1).toUpperCase() + deviceType.substring(1));
        Map<String, JsonElement> result = doHttpRequest(HttpMethod.GET, path, params, null);

        JsonElement obj = result.get("obj");
        if (obj instanceof JsonObject object) {
            Map<String, JsonElement> map = object.asMap();
            Optional<String> key = map.keySet().stream().filter(k -> k.toLowerCase().endsWith("bean")).findFirst();
            if (key.isPresent()) {
                JsonElement beanJson = map.get(key.get());
                if (beanJson instanceof JsonObject bean) {
                    informMaintainer(deviceType);
                    return bean.asMap();
                }
            }
        }
        throw new GrowattApiException("Invalid JSON response");
    }

    /**
     * Get the plant information.
     *
     * @return a list of {@link GrowattDevice} containing the server response.
     * @throws GrowattApiException if any error occurs.
     */
    public List<GrowattDevice> getPlantInfo(String plantId) throws GrowattApiException {
        Map<String, String> params = new LinkedHashMap<>(); // keep params in order
        params.put("op", OP_GET_ALL_DEVICE_LIST);
        params.put("plantId", plantId);
        params.put("pageNum", "1");
        params.put("pageSize", "1");

        Map<String, JsonElement> result = doHttpRequest(HttpMethod.GET, PLANT_INFO_API, params, null);

        JsonElement deviceList = result.get("deviceList");
        if (deviceList instanceof JsonArray deviceArray) {
            try {
                List<GrowattDevice> devices = gson.fromJson(deviceArray, DEVICE_LIST_TYPE);
                if (devices != null) {
                    return devices;
                }
            } catch (JsonSyntaxException e) {
                // fall through
            }
        }
        throw new GrowattApiException("Invalid JSON response");
    }

    /**
     * Get the plant list.
     *
     * @return a {@link GrowattPlantList} containing the server response.
     * @throws GrowattApiException if any error occurs.
     */
    public GrowattPlantList getPlantList(String userId) throws GrowattApiException {
        Map<String, String> params = new LinkedHashMap<>(); // keep params in order
        params.put("userId", userId);

        Map<String, JsonElement> result = doHttpRequest(HttpMethod.GET, PLANT_LIST_API, params, null);

        JsonElement back = result.get("back");
        if (back instanceof JsonObject backObject) {
            try {
                GrowattPlantList plantList = gson.fromJson(backObject, GrowattPlantList.class);
                if (plantList != null && plantList.getSuccess()) {
                    return plantList;
                }
            } catch (JsonSyntaxException e) {
                // fall through
            }
        }
        throw new GrowattApiException("Invalid JSON response");
    }

    /**
     * Attempt to login to the remote server by posting the given user credentials.
     *
     * @throws GrowattApiException if any error occurs.
     */
    private void postLoginCredentials() throws GrowattApiException {
        if (configuration.userName.isBlank()) {
            throw new GrowattApiException("User name missing");
        }
        if (configuration.password.isBlank()) {
            throw new GrowattApiException("Password missing");
        }

        Fields fields = new Fields();
        fields.put("userName", configuration.userName);
        fields.put("password", createHash(configuration.password));

        Map<String, JsonElement> result = doHttpRequestInner(HttpMethod.POST, LOGIN_API, null, fields);

        JsonElement back = result.get("back");
        if (back instanceof JsonObject backObject) {
            try {
                GrowattPlantList plantList = gson.fromJson(backObject, GrowattPlantList.class);
                if (plantList != null && plantList.getSuccess()) {
                    GrowattUser user = plantList.getUserId();
                    userId = user != null ? user.getId() : userId;
                    plantIds.clear();
                    plantIds.addAll(plantList.getPlants().stream().map(GrowattPlant::getId).toList());
                    logger.warn("Logged in userId:{}, plantIds:{}", userId, plantIds);
                    return;
                }
            } catch (JsonSyntaxException e) {
                // fall through
            }
        }
        throw new GrowattApiException("Login failed");
    }

    /**
     * Post a command to setup the inverter battery charging program.
     *
     * @param powerLevel the rate of charging 0%..100%
     * @param targetSOC the SOC at which to stop charging 0%..100%
     * @param allowAcCharging allow charging from AC power (only applies to hybrid/mix inverters)
     * @param startTime the start time of the charging program
     * @param stopTime the stop time of the charging program
     * @param programEnable charge program shall be enabled
     * @return a Map of JSON elements containing the server response
     * @throws GrowattApiException if any error occurs
     */
    public Map<String, JsonElement> setupChargingProgram(int powerLevel, int targetSOC, boolean allowAcCharging,
            LocalTime startTime, LocalTime stopTime, boolean programEnable) throws GrowattApiException {
        return setupBatteryProgram(ProgramType.CHARGE, powerLevel, targetSOC, startTime, stopTime, programEnable,
                allowAcCharging);
    }

    /**
     * Post a command to setup the inverter battery discharging program.
     *
     * @param powerLevel the rate of discharging 1%..100%
     * @param targetSOC the SOC at which to stop charging 1%..100%
     * @param startTime the start time of the discharging program
     * @param stopTime the stop time of the discharging program
     * @param programEnable discharge program shall be enabled
     * @return a Map of JSON elements containing the server response
     * @throws GrowattApiException if any error occurs
     */
    public Map<String, JsonElement> setupDischargingProgram(int powerLevel, int targetSOC, LocalTime startTime,
            LocalTime stopTime, boolean programEnable) throws GrowattApiException {
        return setupBatteryProgram(ProgramType.DISCHARGE, powerLevel, targetSOC, startTime, stopTime, programEnable,
                null);
    }

    /**
     * Look for an entry in the given Map, and return its value as a boolean.
     *
     * @param map the source map.
     * @param key the key to search for in the map.
     * @return the boolean value.
     * @throws GrowattApiException if any error occurs.
     */
    public static boolean mapGetBoolean(Map<String, JsonElement> map, String key) throws GrowattApiException {
        JsonElement element = map.get(key);
        if (element instanceof JsonPrimitive primitive) {
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            } else if (primitive.isNumber() || primitive.isString()) {
                try {
                    switch (primitive.getAsInt()) {
                        case 0:
                            return false;
                        case 1:
                            return true;
                    }
                } catch (NumberFormatException e) {
                    throw new GrowattApiException("Boolean bad value", e);
                }
            }
        }
        throw new GrowattApiException("Boolean missing or bad value");
    }

    /**
     * Look for an entry in the given Map, and return its value as an integer.
     *
     * @param map the source map.
     * @param key the key to search for in the map.
     * @return the integer value.
     * @throws GrowattApiException if any error occurs.
     */
    public static int mapGetInteger(Map<String, JsonElement> map, String key) throws GrowattApiException {
        JsonElement element = map.get(key);
        if (element instanceof JsonPrimitive primitive) {
            try {
                return primitive.getAsInt();
            } catch (NumberFormatException e) {
                throw new GrowattApiException("Integer bad value", e);
            }
        }
        throw new GrowattApiException("Integer missing or bad value");
    }

    /**
     * Look for an entry in the given Map, and return its value as a LocalTime.
     *
     * @param source the source map.
     * @param key the key to search for in the map.
     * @return the LocalTime.
     * @throws GrowattApiException if any error occurs.
     */
    public static LocalTime mapGetLocalTime(Map<String, JsonElement> source, String key) throws GrowattApiException {
        JsonElement element = source.get(key);
        if ((element instanceof JsonPrimitive primitive) && primitive.isString()) {
            try {
                return localTimeOf(primitive.getAsString());
            } catch (DateTimeException e) {
                throw new GrowattApiException("LocalTime bad value", e);
            }
        }
        throw new GrowattApiException("LocalTime missing or bad value");
    }

    /**
     * Parse a time formatted string into a LocalTime entity.
     * <p>
     * Note: unlike the standard LocalTime.parse() method, this method accepts hour and minute fields from the Growatt
     * server that are without leading zeros e.g. "1:1" and it accepts the conventional "01:01" format too.
     *
     * @param localTime a time formatted string e.g. "12:34"
     * @return a corresponding LocalTime entity.
     * @throws DateTimeException if any error occurs.
     */
    public static LocalTime localTimeOf(String localTime) throws DateTimeException {
        String splitParts[] = localTime.split(":");
        if (splitParts.length < 2) {
            throw new DateTimeException("LocalTime bad value");
        }
        try {
            return LocalTime.of(Integer.valueOf(splitParts[0]), Integer.valueOf(splitParts[1]));
        } catch (NumberFormatException | DateTimeException e) {
            throw new DateTimeException("LocalTime bad value", e);
        }
    }

    /**
     * Post a command to setup the inverter battery charging / discharging program.
     *
     * @param programType selects whether the program is for charge or discharge
     * @param powerLevel the rate of charging / discharging 1%..100%
     * @param targetSOC the SOC at which to stop the program 1%..100%
     * @param startTime the start time of the program
     * @param stopTime the stop time of the program
     * @param programEnable the program shall be enabled
     * @param allowAcCharging allow charging from AC power (only applies to hybrid/mix inverters)
     * @return a Map of JSON elements containing the server response
     * @throws GrowattApiException if any error occurs
     */
    private Map<String, JsonElement> setupBatteryProgram(ProgramType programType, int powerLevel, int targetSOC,
            LocalTime startTime, LocalTime stopTime, boolean programEnable, @Nullable Boolean allowAcCharging)
            throws GrowattApiException {
        String deviceId = configuration.deviceId;
        if (deviceId.isBlank()) {
            throw new GrowattApiException("Blank device id");
        }
        String deviceType = getDeviceType(deviceId);
        if (deviceType == null || !SUPPORTED_TYPES_OP_PARAM_SET.containsKey(deviceType)) {
            throw new GrowattApiException("Unsupported device type:" + deviceType);
        }
        if (powerLevel < 1 || powerLevel > 100) {
            throw new GrowattApiException("Power level out of range (1%..100%)");
        }
        if (targetSOC < 1 || targetSOC > 100) {
            throw new GrowattApiException("Target SOC out of range (1%..100%)");
        }

        Fields fields = new Fields();

        fields.put("op", Objects.requireNonNull(SUPPORTED_TYPES_OP_PARAM_SET.get(deviceType)));
        fields.put("serialNum", configuration.deviceId);
        fields.put("type", String.format(FMT_TYPE_PARAM, deviceType, programType.name().toLowerCase()));

        int paramIndex = 1;

        paramIndex = addFieldParam(fields, paramIndex, String.format("%d", powerLevel));
        paramIndex = addFieldParam(fields, paramIndex, String.format("%d", targetSOC));
        if ("mix".equals(deviceType) && ProgramType.CHARGE == programType) {
            paramIndex = addFieldParam(fields, paramIndex, allowAcCharging ? "1" : "0");
        }
        paramIndex = addFieldParam(fields, paramIndex, String.format("%02d", startTime.getHour()));
        paramIndex = addFieldParam(fields, paramIndex, String.format("%02d", startTime.getMinute()));
        paramIndex = addFieldParam(fields, paramIndex, String.format("%02d", stopTime.getHour()));
        paramIndex = addFieldParam(fields, paramIndex, String.format("%02d", stopTime.getMinute()));
        paramIndex = addFieldParam(fields, paramIndex, programEnable ? "1" : "0");
        paramIndex = addFieldParam(fields, paramIndex, "00");
        paramIndex = addFieldParam(fields, paramIndex, "00");
        paramIndex = addFieldParam(fields, paramIndex, "00");
        paramIndex = addFieldParam(fields, paramIndex, "00");
        paramIndex = addFieldParam(fields, paramIndex, "0");
        paramIndex = addFieldParam(fields, paramIndex, "00");
        paramIndex = addFieldParam(fields, paramIndex, "00");
        paramIndex = addFieldParam(fields, paramIndex, "00");
        paramIndex = addFieldParam(fields, paramIndex, "00");
        paramIndex = addFieldParam(fields, paramIndex, "0");

        Map<String, JsonElement> result = doHttpRequest(HttpMethod.POST, NEW_TCP_SET_API, null, fields);

        JsonElement success = result.get("success");
        if (success instanceof JsonPrimitive sucessPrimitive) {
            if (sucessPrimitive.getAsBoolean()) {
                informMaintainer(deviceType);
                return result;
            }
        }
        throw new GrowattApiException("Command failed");
    }

    /**
     * Add a new entry in the given {@link Fields} map in the form "paramN" = paramValue where N is the parameter index.
     *
     * @param fields the map to be added to.
     * @param parameterIndex the parameter index.
     * @param parameterValue the parameter value.
     *
     * @return the next parameter index.
     */
    private int addFieldParam(Fields fields, int parameterIndex, String parameterValue) {
        fields.put(String.format("param%d", parameterIndex), parameterValue);
        return parameterIndex + 1;
    }

    /**
     * Inform maintainers about a newly checked (i.e. supported) device type.
     *
     * @param deviceType
     */
    private void informMaintainer(String deviceType) {
        if (GUESSED_TYPES.contains(deviceType)) {
            logger.warn("Please inform maintainer that deviceType:'{}' is now tested", deviceType);
        }
    }
}
