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
package org.openhab.binding.verisure.internal.handler;

import static org.openhab.binding.verisure.internal.VerisureBindingConstants.*;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.eclipse.smarthome.core.thing.binding.BaseBridgeHandler;
import org.eclipse.smarthome.core.thing.binding.ThingHandlerService;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.openhab.binding.verisure.internal.DeviceStatusListener;
import org.openhab.binding.verisure.internal.VerisureBridgeConfiguration;
import org.openhab.binding.verisure.internal.VerisureSession;
import org.openhab.binding.verisure.internal.discovery.VerisureThingDiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link VerisureBridgeHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author l3rum - Initial contribution
 * @author Jan Gustafsson - Furher development
 */
@NonNullByDefault
public class VerisureBridgeHandler extends BaseBridgeHandler {

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Collections.singleton(THING_TYPE_BRIDGE);

    private static final int REFRESH_DELAY_SECONDS = 30;
    private final Logger logger = LoggerFactory.getLogger(VerisureBridgeHandler.class);
    private final ReentrantLock immediateRefreshJobLock = new ReentrantLock();

    private String authstring = "";
    private @Nullable String pinCode;
    private static int refresh = 600;
    private @Nullable ScheduledFuture<?> refreshJob;
    private @Nullable ScheduledFuture<?> immediateRefreshJob;
    private @Nullable VerisureSession session;
    private final HttpClient httpClient;

    public VerisureBridgeHandler(Bridge bridge, HttpClient httpClient) {
        super(bridge);
        this.httpClient = httpClient;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("VerisureBridgeHandler Handle command {} on channelUID: {}", command, channelUID);
        if (command instanceof RefreshType) {
            if (channelUID.getId().equals(CHANNEL_STATUS) && channelUID.getThingUID().equals(getThing().getUID())) {
                logger.debug("Refresh command on status channel {} will trigger instant refresh", channelUID);
                scheduleImmediateRefresh(0);
            } else {
                logger.debug("Refresh command on channel {} will trigger refresh in {} seconds", channelUID,
                        REFRESH_DELAY_SECONDS);
                scheduleImmediateRefresh(REFRESH_DELAY_SECONDS);
            }
        } else {
            logger.warn("unknown command! {}", command);
        }
    }

    @Override
    public void channelLinked(ChannelUID channelUID) {
        // Do not do a refresh since that will refresh all things as well
        logger.debug("Channel linked {}.", channelUID);
    }

    @Override
    protected void updateThing(Thing thing) {
        super.updateThing(thing);
    }

    @Override
    protected void updateConfiguration(Configuration configuration) {
        stopAutomaticRefresh();
        stopImmediateRefresh();
        super.updateConfiguration(configuration);
        initialize();
    }

    public @Nullable VerisureSession getSession() {
        return session;
    }

    public @Nullable ThingUID getUID() {
        return getThing().getUID();
    }

    @Override
    public void initialize() {
        logger.debug("Initializing Verisure Binding");
        VerisureBridgeConfiguration config = getConfigAs(VerisureBridgeConfiguration.class);
        refresh = config.refresh;
        this.pinCode = config.pin;
        if (config.username == null || config.password == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Configuration of username and password is mandatory");
        } else if (refresh < 0) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Refresh time cannot negative!");
        } else {
            try {
                authstring = "j_username=" + config.username + "&j_password="
                        + URLEncoder.encode(config.password, StandardCharsets.UTF_8.toString())
                        + "&spring-security-redirect=" + START_REDIRECT;
                updateStatus(ThingStatus.UNKNOWN);
                scheduler.execute(() -> {
                    if (session == null) {
                        logger.debug("Session is null, probably configuration change, let's create a new one");
                        session = new VerisureSession(this.httpClient);
                    }
                    if (!session.initialize(authstring, pinCode, config.username)) {
                        logger.warn("Failed to initialize bridge, please check your credentials!");
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.HANDLER_REGISTERING_ERROR,
                                "Failed to login to Verisure, please check your credentials!");
                        return;
                    }
                    startAutomaticRefresh();
                });
            } catch (RuntimeException e) {
                logger.warn("Failed to initialize! Exception caught: {}", e.getMessage(), e);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
            } catch (UnsupportedEncodingException e) {
                logger.warn("Failed to URL Encode password, exception caught: {} ", e.getMessage(), e);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
            }
        }
    }

    @Override
    public void dispose() {
        logger.debug("Handler disposed.");
        stopAutomaticRefresh();
        stopImmediateRefresh();
        if (session != null) {
            session.dispose();
            session = null;
        }
    }

    public boolean registerObjectStatusListener(DeviceStatusListener deviceStatusListener) {
        if (session != null) {
            logger.debug("registerObjectStatusListener for listener {}", deviceStatusListener);
            return session.registerDeviceStatusListener(deviceStatusListener);
        }
        return false;
    }

    public boolean unregisterObjectStatusListener(DeviceStatusListener deviceStatusListener) {
        if (session != null) {
            logger.debug("unregisterObjectStatusListener for listener {}", deviceStatusListener);
            return session.unregisterDeviceStatusListener(deviceStatusListener);
        }
        return false;
    }

    @Override
    public void handleRemoval() {
        logger.debug("handleRemoval");
        stopAutomaticRefresh();
        stopImmediateRefresh();
        if (session != null) {
            session.dispose();
            session = null;
        }
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Collections.singleton(VerisureThingDiscoveryService.class);
    }

    private void refreshAndUpdateStatus() {
        logger.debug("VerisureBridgeHandler - Polling thread is up'n running!");
        try {
            if (session != null) {
                boolean success = session.refresh();
                if (success) {
                    updateStatus(ThingStatus.ONLINE);
                } else {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR);
                }
            }
        } catch (RuntimeException e) {
            logger.debug("Exception occurred during execution: {}", e.getMessage(), e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR);
        }
    }

    void scheduleImmediateRefresh(int refreshDelay) {
        logger.debug("VerisureBridgeHandler - scheduleImmediateRefresh");
        immediateRefreshJobLock.lock();
        try {
            // We schedule in 10 sec, to avoid multiple updates
            if (refreshJob != null) {
                logger.debug("Current remaining delay {} for refresh job {}", refreshJob.getDelay(TimeUnit.SECONDS),
                        refreshJob);
                if (immediateRefreshJob != null) {
                    logger.debug("Current remaining delay {} for immediate refresh job {}",
                            immediateRefreshJob.getDelay(TimeUnit.SECONDS), immediateRefreshJob);
                }
                if (refreshJob.getDelay(TimeUnit.SECONDS) > refreshDelay) {
                    if (immediateRefreshJob == null || immediateRefreshJob.getDelay(TimeUnit.SECONDS) <= 0) {
                        if (immediateRefreshJob != null) {
                            logger.debug("Current remaining delay {} for immediate refresh job {}",
                                    immediateRefreshJob.getDelay(TimeUnit.SECONDS), immediateRefreshJob);
                        }
                        // Note we are using getDelay() instead of isDone() as we want to allow Things to schedule a
                        // refresh if their status is pending. As the status update happens inside the
                        // refreshAndUpdateStatus
                        // execution the isDone() will return false and would not allow the rescheduling of the task.
                        immediateRefreshJob = scheduler.schedule(this::refreshAndUpdateStatus, refreshDelay,
                                TimeUnit.SECONDS);
                        logger.debug("Scheduling new immediate refresh job {}", immediateRefreshJob);
                    }
                }
            }
        } catch (RejectedExecutionException e) {
            logger.warn("Immediate refresh job cannot be scheduled!");
        } finally {
            immediateRefreshJobLock.unlock();
        }
    }

    private void startAutomaticRefresh() {
        logger.debug("Start automatic refresh {}", refreshJob);
        if (refreshJob == null || refreshJob.isCancelled()) {
            try {
                refreshJob = scheduler.scheduleWithFixedDelay(this::refreshAndUpdateStatus, 0, refresh,
                        TimeUnit.SECONDS);
                logger.debug("Scheduling at fixed delay refreshjob {}", refreshJob);
            } catch (RejectedExecutionException e) {
                logger.warn("Automatic refresh job cannot be started!");
            }
        }
    }

    private void stopImmediateRefresh() {
        immediateRefreshJobLock.lock();
        try {
            logger.debug("Stop immediate refresh for job {}", immediateRefreshJob);
            if (immediateRefreshJob != null && !immediateRefreshJob.isCancelled()) {
                immediateRefreshJob.cancel(true);
                immediateRefreshJob = null;
            }
        } catch (RejectedExecutionException e) {
            logger.warn("Immediate refresh job cannot be scheduled!");
        } finally {
            immediateRefreshJobLock.unlock();
        }
    }

    private void stopAutomaticRefresh() {
        logger.debug("Stop automatic refresh for job {}", refreshJob);
        if (refreshJob != null && !refreshJob.isCancelled()) {
            refreshJob.cancel(true);
            refreshJob = null;
        }
    }
}
