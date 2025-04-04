/**
 * Copyright (c) 2010-2024 Contributors to the openHAB project
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
package org.openhab.binding.tado.internal.handler;

import static org.openhab.binding.tado.internal.TadoBindingConstants.*;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.tado.internal.TadoBindingConstants.TemperatureUnit;
import org.openhab.binding.tado.internal.api.HomeApiFactory;
import org.openhab.binding.tado.internal.config.TadoHomeConfig;
import org.openhab.binding.tado.internal.servlet.TadoAuthenticationServlet;
import org.openhab.binding.tado.swagger.codegen.api.ApiException;
import org.openhab.binding.tado.swagger.codegen.api.client.HomeApi;
import org.openhab.binding.tado.swagger.codegen.api.model.HomeInfo;
import org.openhab.binding.tado.swagger.codegen.api.model.HomePresence;
import org.openhab.binding.tado.swagger.codegen.api.model.HomeState;
import org.openhab.binding.tado.swagger.codegen.api.model.PresenceState;
import org.openhab.binding.tado.swagger.codegen.api.model.User;
import org.openhab.binding.tado.swagger.codegen.api.model.UserHomes;
import org.openhab.core.auth.client.oauth2.AccessTokenRefreshListener;
import org.openhab.core.auth.client.oauth2.AccessTokenResponse;
import org.openhab.core.auth.client.oauth2.OAuthClientService;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link TadoHomeHandler} is the bridge of all home-based things.
 *
 * @author Dennis Frommknecht - Initial contribution
 * @author Andrew Fiddian-Green - OAuth RFC18628 authentication
 */
@NonNullByDefault
public class TadoHomeHandler extends BaseBridgeHandler implements AccessTokenRefreshListener {

    // thing status description i18n text pointers
    private static final String CONF_ERROR_NO_HOME = "@text/tado.home.status.nohome";
    private static final String CONF_ERROR_NO_HOME_ID = "@text/tado.home.status.nohomeid";
    private static final String CONF_PENDING_USER_CREDS = "@text/tado.home.status.username";
    private static final String CONF_PENDING_OAUTH_CREDS = //
            "@text/tado.home.status.oauth [\"http(s)://<YOUROPENHAB>:<YOURPORT>%s?%s=%s\"]";

    // tado specific RFC-8628 oAuth authentication parameters
    private static final ZonedDateTime OAUTH_MANDATORY_FROM_DATE = ZonedDateTime.parse("2025-03-15T00:00:00Z");

    private final Logger logger = LoggerFactory.getLogger(TadoHomeHandler.class);

    private final TadoBatteryChecker batteryChecker;
    private final TadoHandlerFactory tadoHandlerFactory;

    private @NonNullByDefault({}) TadoHomeConfig configuration;
    private @NonNullByDefault({}) String confPendingText;
    private @NonNullByDefault({}) HomeApi api;

    private @Nullable Long homeId;
    private @Nullable ScheduledFuture<?> initializationFuture;
    private @Nullable OAuthClientService oAuthClientService;

    public TadoHomeHandler(Bridge bridge, TadoHandlerFactory tadoHandlerFactory) {
        super(bridge);
        this.batteryChecker = new TadoBatteryChecker(this);
        this.configuration = getConfigAs(TadoHomeConfig.class);
        this.tadoHandlerFactory = tadoHandlerFactory;
    }

    public TemperatureUnit getTemperatureUnit() {
        String temperatureUnitStr = this.thing.getProperties().getOrDefault(PROPERTY_HOME_TEMPERATURE_UNIT, "CELSIUS");
        return TemperatureUnit.valueOf(temperatureUnitStr);
    }

    @Override
    public void initialize() {
        configuration = getConfigAs(TadoHomeConfig.class);

        String username = configuration.username;
        String password = configuration.password;
        boolean v1CredentialsMissing = username == null || username.isBlank() || password == null || password.isBlank();

        boolean suggestRfc8628 = false;
        suggestRfc8628 |= Boolean.TRUE.equals(configuration.useRfc8628);
        suggestRfc8628 |= v1CredentialsMissing;
        suggestRfc8628 |= ZonedDateTime.now().isAfter(OAUTH_MANDATORY_FROM_DATE);

        if (suggestRfc8628) {
            String rfcUser = Boolean.TRUE.equals(configuration.rfcWithUser) //
                    ? username != null && !username.isBlank() ? username : null
                    : null;
            OAuthClientService oAuthClientService = tadoHandlerFactory.subscribeOAuthClientService(this, rfcUser);
            oAuthClientService.addAccessTokenRefreshListener(this);
            this.api = new HomeApiFactory().create(oAuthClientService);
            this.oAuthClientService = oAuthClientService;
            logger.trace("initialize() api v2 created");
            confPendingText = CONF_PENDING_OAUTH_CREDS.formatted(TadoAuthenticationServlet.PATH,
                    TadoAuthenticationServlet.PARAM_NAME_USER, rfcUser != null ? rfcUser : "");
            if (!Boolean.TRUE.equals(configuration.useRfc8628)) {
                Configuration configuration = editConfiguration();
                configuration.put(CONFIG_USE_RFC8628, Boolean.TRUE);
                updateConfiguration(configuration);
            }
        } else {
            api = new HomeApiFactory().create(Objects.requireNonNull(username), Objects.requireNonNull(password));
            logger.trace("initialize() api v1 created");
            confPendingText = CONF_PENDING_USER_CREDS;
        }

        ScheduledFuture<?> initializationFuture = this.initializationFuture;
        if (initializationFuture == null || initializationFuture.isDone()) {
            this.initializationFuture = scheduler.scheduleWithFixedDelay(
                    this::initializeBridgeStatusAndPropertiesIfOffline, 0, 300, TimeUnit.SECONDS);
        }
    }

    private synchronized void initializeBridgeStatusAndPropertiesIfOffline() {
        if (getThing().getStatus() == ThingStatus.ONLINE) {
            for (Thing thing : getThing().getThings()) {
                ThingHandler handler = thing.getHandler();
                if ((handler instanceof BaseHomeThingHandler) && (thing.getStatus() == ThingStatus.OFFLINE)
                        && (thing.getStatusInfo().getStatusDetail() == ThingStatusDetail.COMMUNICATION_ERROR)) {
                    scheduler.submit(() -> handler.bridgeStatusChanged(getThing().getStatusInfo()));
                }
            }
        }

        try {
            // if we are already online, don't make unnecessary calls on the server
            if (getThing().getStatus() != ThingStatus.ONLINE) {
                // Get user info to verify successful authentication and connection to server
                User user = api.showUser();
                if (user == null) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING, confPendingText);
                    return;
                }

                List<UserHomes> homes = user.getHomes();
                if (homes == null || homes.isEmpty()) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, CONF_ERROR_NO_HOME);
                    return;
                }

                /*
                 * If there is only one home, or if there is no valid configuration.homeId entry, then use the first
                 * home id. Otherwise use the configuration.homeId entry value (if one exists). If there is no valid
                 * configuration.homeId entry but there are multiple homes then log the available home to help the
                 * user set up the proper configuration.homeId entry.
                 */
                Integer firstHomeId = homes.get(0).getId();
                if (homes.size() > 1) {
                    Integer configHomeId = configuration.homeId;
                    if (configHomeId == null || configHomeId == 0) {
                        logger.info("Trying first Home Id in the list [{}]", homes.stream()
                                .map(home -> String.valueOf(home.getId())).collect(Collectors.joining(",")));
                    } else {
                        firstHomeId = homes.stream().map(home -> home.getId()).filter(id -> configHomeId.equals(id))
                                .findFirst().orElse(null);
                    }
                }

                if (firstHomeId == null) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, CONF_ERROR_NO_HOME_ID);
                    return;
                }

                homeId = firstHomeId.longValue();
            }

            // but always make one server call as a 'ping' to confirm we are really still online
            HomeInfo homeInfo = api.showHome(homeId);
            TemperatureUnit temperatureUnit = org.openhab.binding.tado.swagger.codegen.api.model.TemperatureUnit.FAHRENHEIT == homeInfo
                    .getTemperatureUnit() ? TemperatureUnit.FAHRENHEIT : TemperatureUnit.CELSIUS;
            updateProperty(PROPERTY_HOME_TEMPERATURE_UNIT, temperatureUnit.name());
        } catch (IOException | ApiException e) {
            logger.debug("Error accessing tado server: {}", e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING, confPendingText);
            return;
        }

        updateStatus(ThingStatus.ONLINE);
    }

    @Override
    public void dispose() {
        super.dispose();
        OAuthClientService oAuthClientService = this.oAuthClientService;
        if (oAuthClientService != null) {
            tadoHandlerFactory.unsubscribeOAuthClientService(this);
            oAuthClientService.removeAccessTokenRefreshListener(this);
        }
        ScheduledFuture<?> initializationFuture = this.initializationFuture;
        if (initializationFuture != null && !initializationFuture.isCancelled()) {
            initializationFuture.cancel(true);
        }
    }

    public HomeApi getApi() {
        return api;
    }

    public @Nullable Long getHomeId() {
        return homeId;
    }

    public HomeState getHomeState() throws IOException, ApiException {
        return api.homeState(getHomeId());
    }

    public void updateHomeState() {
        try {
            updateState(CHANNEL_HOME_PRESENCE_MODE, OnOffType.from(getHomeState().getPresence() == PresenceState.HOME));
        } catch (IOException | ApiException e) {
            logger.debug("Error accessing tado server: {}", e.getMessage(), e);
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        String id = channelUID.getId();

        if (command == RefreshType.REFRESH) {
            updateHomeState();
            return;
        }

        switch (id) {
            case CHANNEL_HOME_PRESENCE_MODE:
                HomePresence presence = new HomePresence();
                presence.setHomePresence("ON".equals(command.toFullString().toUpperCase())
                        || "HOME".equals(command.toFullString().toUpperCase()) ? PresenceState.HOME
                                : PresenceState.AWAY);
                try {
                    api.updatePresenceLock(homeId, presence);
                } catch (IOException | ApiException e) {
                    logger.warn("Error setting home presence: {}", e.getMessage(), e);
                }

                break;
        }
    }

    public TadoBatteryChecker getBatteryChecker() {
        return this.batteryChecker;
    }

    @Override
    public void onAccessTokenResponse(AccessTokenResponse atr) {
        initializeBridgeStatusAndPropertiesIfOffline();
    }
}
