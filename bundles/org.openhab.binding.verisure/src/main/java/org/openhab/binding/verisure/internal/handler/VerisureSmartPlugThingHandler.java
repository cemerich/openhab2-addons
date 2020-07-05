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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.Channel;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.eclipse.smarthome.core.types.State;
import org.eclipse.smarthome.core.types.UnDefType;
import org.openhab.binding.verisure.internal.VerisureSession;
import org.openhab.binding.verisure.internal.model.VerisureSmartPlugs;
import org.openhab.binding.verisure.internal.model.VerisureSmartPlugs.Smartplug;
import org.openhab.binding.verisure.internal.model.VerisureThing;

/**
 * Handler for the Smart Plug Device thing type that Verisure provides.
 *
 * @author Jan Gustafsson - Initial contribution
 *
 */
@NonNullByDefault
public class VerisureSmartPlugThingHandler extends VerisureThingHandler {

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Collections.singleton(THING_TYPE_SMARTPLUG);

    private static final int REFRESH_DELAY_SECONDS = 10;

    public VerisureSmartPlugThingHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("handleCommand, channel: {}, command: {}", channelUID, command);
        if (command instanceof RefreshType) {
            super.handleCommand(channelUID, command);
        } else if (channelUID.getId().equals(CHANNEL_SMARTPLUG_STATUS)) {
            handleSmartPlugState(command);
            scheduleImmediateRefresh(REFRESH_DELAY_SECONDS);
        } else {
            logger.warn("Unknown command! {}", command);
        }
    }

    private void handleSmartPlugState(Command command) {
        String deviceId = config.getDeviceId();
        VerisureSession session = getSession();
        if (session != null) {
            VerisureSmartPlugs smartPlug = (VerisureSmartPlugs) session.getVerisureThing(deviceId);
            if (smartPlug != null) {
                BigDecimal installationId = smartPlug.getSiteId();
                String url = START_GRAPHQL;
                String operation;
                boolean isOperation;
                if (command == OnOffType.OFF) {
                    operation = "false";
                    isOperation = false;
                } else if (command == OnOffType.ON) {
                    operation = "true";
                    isOperation = true;
                } else {
                    logger.debug("Unknown command! {}", command);
                    return;
                }
                String query = "mutation UpdateState($giid: String!, $deviceLabel: String!, $state: Boolean!) {\n SmartPlugSetState(giid: $giid, input: [{deviceLabel: $deviceLabel, state: $state}])\n}\n";
                ArrayList<SmartPlug> list = new ArrayList<>();
                SmartPlug smartPlugJSON = new SmartPlug();
                Variables variables = new Variables();

                variables.setDeviceLabel(deviceId);
                variables.setGiid(installationId.toString());
                variables.setState(isOperation);
                smartPlugJSON.setOperationName("UpdateState");
                smartPlugJSON.setVariables(variables);
                smartPlugJSON.setQuery(query);
                list.add(smartPlugJSON);

                String queryQLSmartPlugSetState = gson.toJson(list);
                logger.debug("Trying to set SmartPlug state to {} with URL {} and data {}", operation, url,
                        queryQLSmartPlugSetState);
                int httpResultCode = session.sendCommand(url, queryQLSmartPlugSetState, installationId);
                if (httpResultCode == HttpStatus.OK_200) {
                    logger.debug("Smartplug state successfully changed!");
                } else {
                    logger.warn("Failed to send command, HTTP result code {}", httpResultCode);
                }
            }
        }
    }

    @Override
    public Class<VerisureSmartPlugs> getVerisureThingClass() {
        return VerisureSmartPlugs.class;
    }

    @Override
    public synchronized void update(VerisureThing thing) {
        logger.debug("update on thing: {}", thing);
        updateStatus(ThingStatus.ONLINE);
        updateSmartPlugState((VerisureSmartPlugs) thing);
    }

    private void updateSmartPlugState(VerisureSmartPlugs smartPlugJSON) {
        Smartplug smartplug = smartPlugJSON.getData().getInstallation().getSmartplugs().get(0);
        String smartPlugStatus = smartplug.getCurrentState();
        if (smartPlugStatus != null) {
            getThing().getChannels().stream().map(Channel::getUID)
                    .filter(channelUID -> isLinked(channelUID) && !channelUID.getId().equals("timestamp"))
                    .forEach(channelUID -> {
                        State state = getValue(channelUID.getId(), smartplug, smartPlugStatus);
                        updateState(channelUID, state);
                    });
            super.update(smartPlugJSON);
        }
    }

    public State getValue(String channelId, Smartplug smartplug, String smartPlugStatus) {
        switch (channelId) {
            case CHANNEL_SMARTPLUG_STATUS:
                if ("ON".equals(smartPlugStatus)) {
                    return OnOffType.ON;
                } else if ("OFF".equals(smartPlugStatus)) {
                    return OnOffType.OFF;
                } else if ("PENDING".equals(smartPlugStatus)) {
                    // Schedule another refresh.
                    logger.debug("Issuing another immediate refresh since status is still PENDING ...");
                    this.scheduleImmediateRefresh(REFRESH_DELAY_SECONDS);
                }
                break;
            case CHANNEL_LOCATION:
                String location = smartplug.getDevice().getArea();
                return location != null ? new StringType(location) : UnDefType.NULL;
            case CHANNEL_HAZARDOUS:
                return OnOffType.from(smartplug.isHazardous());
        }
        return UnDefType.UNDEF;
    }

    @NonNullByDefault
    private static class SmartPlug {

        private @Nullable String operationName;
        private Variables variables = new Variables();
        private @Nullable String query;

        public void setOperationName(String operationName) {
            this.operationName = operationName;
        }

        public void setVariables(Variables variables) {
            this.variables = variables;
        }

        public void setQuery(String query) {
            this.query = query;
        }
    }

    @NonNullByDefault
    private static class Variables {

        private @Nullable String giid;
        private @Nullable String deviceLabel;
        private boolean state;

        public void setGiid(String giid) {
            this.giid = giid;
        }

        public void setDeviceLabel(String deviceLabel) {
            this.deviceLabel = deviceLabel;
        }

        public void setState(boolean state) {
            this.state = state;
        }
    }
}
