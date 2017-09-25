/**
 * Copyright (c) 2014-2016 by the respective copyright holders.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.modbus.handler;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.library.items.ContactItem;
import org.eclipse.smarthome.core.library.items.DateTimeItem;
import org.eclipse.smarthome.core.library.items.DimmerItem;
import org.eclipse.smarthome.core.library.items.NumberItem;
import org.eclipse.smarthome.core.library.items.RollershutterItem;
import org.eclipse.smarthome.core.library.items.StringItem;
import org.eclipse.smarthome.core.library.items.SwitchItem;
import org.eclipse.smarthome.core.library.types.DateTimeType;
import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.OpenClosedType;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.ThingStatusInfo;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.eclipse.smarthome.core.types.State;
import org.openhab.binding.modbus.ModbusBindingConstants;
import org.openhab.binding.modbus.internal.Transformation;
import org.openhab.binding.modbus.internal.config.ModbusReadConfiguration;
import org.openhab.io.transport.modbus.BitArray;
import org.openhab.io.transport.modbus.ModbusBitUtilities;
import org.openhab.io.transport.modbus.ModbusConstants;
import org.openhab.io.transport.modbus.ModbusConstants.ValueType;
import org.openhab.io.transport.modbus.ModbusManager.PollTask;
import org.openhab.io.transport.modbus.ModbusReadCallback;
import org.openhab.io.transport.modbus.ModbusReadFunctionCode;
import org.openhab.io.transport.modbus.ModbusReadRequestBlueprint;
import org.openhab.io.transport.modbus.ModbusRegisterArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link ModbusReadThingHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Sami Salonen - Initial contribution
 */
public class ModbusReadThingHandler extends BaseThingHandler implements ModbusReadCallback {

    private Logger logger = LoggerFactory.getLogger(ModbusReadThingHandler.class);
    private volatile ModbusReadConfiguration config;
    private volatile Object lastStateLock = new Object();
    private volatile Map<ChannelUID, State> lastState;
    private volatile Transformation transformation;
    private volatile String trigger;

    private static final Map<String, List<Class<? extends State>>> channelIdToAcceptedDataTypes = new HashMap<>();

    static {
        channelIdToAcceptedDataTypes.put(ModbusBindingConstants.CHANNEL_SWITCH,
                new SwitchItem("").getAcceptedDataTypes());
        channelIdToAcceptedDataTypes.put(ModbusBindingConstants.CHANNEL_CONTACT,
                new ContactItem("").getAcceptedDataTypes());
        channelIdToAcceptedDataTypes.put(ModbusBindingConstants.CHANNEL_DATETIME,
                new DateTimeItem("").getAcceptedDataTypes());
        channelIdToAcceptedDataTypes.put(ModbusBindingConstants.CHANNEL_DIMMER,
                new DimmerItem("").getAcceptedDataTypes());
        channelIdToAcceptedDataTypes.put(ModbusBindingConstants.CHANNEL_NUMBER,
                new NumberItem("").getAcceptedDataTypes());
        channelIdToAcceptedDataTypes.put(ModbusBindingConstants.CHANNEL_STRING,
                new StringItem("").getAcceptedDataTypes());
        channelIdToAcceptedDataTypes.put(ModbusBindingConstants.CHANNEL_ROLLERSHUTTER,
                new RollershutterItem("").getAcceptedDataTypes());
    }

    private Map<ChannelUID, List<Class<? extends State>>> channelUIDToAcceptedDataTypes;
    private @Nullable ValueType valueType;

    public ModbusReadThingHandler(@NonNull Thing thing) {
        super(thing);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // All channels are read-only for now
        // TODO: handle REFRESH
        if (command.equals(RefreshType.REFRESH)) {
        }
    }

    @Override
    public synchronized void initialize() {
        // Initialize the thing. If done set status to ONLINE to indicate proper working.
        // Long running initialization should be done asynchronously in background.
        synchronized (lastStateLock) {
            lastState = null;
        }
        try {
            config = getConfigAs(ModbusReadConfiguration.class);
            valueType = ValueType.fromConfigValue(config.getValueType());
            trigger = config.getTrigger();
            transformation = new Transformation(config.getTransform());
        } catch (IllegalArgumentException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, e.toString());
            return;
        }

        channelUIDToAcceptedDataTypes = channelIdToAcceptedDataTypes.keySet().stream()
                .collect(Collectors.toMap(channelId -> new ChannelUID(getThing().getUID(), channelId),
                        channel -> channelIdToAcceptedDataTypes.get(channel)));

        validateConfiguration();
    }

    public synchronized void validateConfiguration() {
        Bridge readwrite = getBridgeOfThing(getThing());
        if (readwrite == null) {
            logger.debug("ReadThing '{}' has no ReadThing bridge. Aborting config validation", getThing().getLabel());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE, "No read-write bridge");
            return;
        }
        if (readwrite.getStatus() != ThingStatus.ONLINE) {
            logger.debug("ReadWrite bridge '{}' of ReadThing '{}' is offline. Aborting config validation",
                    readwrite.getLabel(), getThing().getLabel());
            if (readwrite.getStatusInfo().getStatusDetail() == ThingStatusDetail.COMMUNICATION_ERROR
                    && this.getThing().getStatusInfo().getStatus() == ThingStatus.OFFLINE
                    && this.getThing().getStatusInfo().getStatusDetail() == ThingStatusDetail.COMMUNICATION_ERROR) {
                // Communication error has been communicated already by readwrite to this thing handler
                return;
            } else {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE,
                        String.format("Read-write bridge %s is offline", readwrite.getLabel()));
                return;
            }
        }

        Bridge poller = getBridgeOfThing(readwrite);
        if (poller == null) {
            logger.debug("ReadWrite bridge '{}' of ReadThing '{}' has no Poller bridge. Aborting config validation",
                    readwrite.getLabel(), getThing().getLabel());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE,
                    String.format("No poller bridge set for the read-write bridge %s", readwrite.getLabel()));
            return;
        }
        if (poller.getStatus() != ThingStatus.ONLINE) {
            logger.debug(
                    "Poller bridge '{}' of ReadWriteThing bridge '{}' of ReadThing '{}' is offline. Aborting config validation",
                    poller.getLabel(), readwrite.getLabel(), getThing().getLabel());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE,
                    String.format("Poller bridge %s of the read-write bridge is offline", poller.getLabel()));
            return;
        }

        if (poller.getHandler() == null) {
            logger.warn(
                    "Poller '{}' of ReadWrite bridge '{}' of ReadThing '{}' has no handler. Aborting config validation",
                    poller.getLabel(), readwrite.getLabel(), getThing().getLabel());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    String.format("Poller '%s' configuration incomplete or with errors", poller.getLabel()));
            return;
        }

        @SuppressWarnings("null")
        @NonNull
        ModbusPollerThingHandler handler = (@NonNull ModbusPollerThingHandler) poller.getHandler();

        if (handler.getPollTask() == null) {
            logger.warn(
                    "Poller '{}' of ReadWrite bridge '{}' of ReadThing '{}' has no poll task. Aborting config validation",
                    poller.getLabel(), readwrite.getLabel(), getThing().getLabel());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    String.format("Poller '%s' configuration incomplete or with errors", poller.getLabel()));
            return;
        }
        PollTask pollTask = handler.getPollTask();

        if (!validateIndex(pollTask)) {
            return;
        }
        if (!validateValueType(pollTask)) {
            return;
        }

        updateStatus(ThingStatus.ONLINE);
    }

    private boolean validateValueType(PollTask pollTask) {
        ModbusReadFunctionCode functionCode = pollTask.getRequest().getFunctionCode();
        if ((functionCode == ModbusReadFunctionCode.READ_COILS
                || functionCode == ModbusReadFunctionCode.READ_INPUT_DISCRETES)
                && !ModbusConstants.ValueType.BIT.equals(valueType)) {
            logger.error(
                    "ReadThing {}: Only valueType='{}' supported with coils or discrete inputs. Value type was: {}",
                    getThing(), ModbusConstants.ValueType.BIT, config.getValueType());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    String.format("Only valueType='%s' supported with coils or discrete inputs. Value type was: {}",
                            ModbusConstants.ValueType.BIT, config.getValueType()));
            return false;
        } else {
            return true;
        }
    }

    private boolean validateIndex(PollTask pollTask) {
        // bits represented by the value type, e.g. int32 -> 32
        int valueTypeBitCount = valueType.getBits();
        // bits represented by the function code. For registers this is 16, for coils and discrete inputs it is 1.
        int functionObjectBitSize;
        // textual name for the data element, e.g. register
        // (for logging)
        String dataElement;
        if (pollTask.getRequest().getFunctionCode() == ModbusReadFunctionCode.READ_INPUT_REGISTERS
                || pollTask.getRequest().getFunctionCode() == ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS) {
            functionObjectBitSize = 16;
            dataElement = "register";
        } else {
            functionObjectBitSize = 1;
            if (pollTask.getRequest().getFunctionCode() == ModbusReadFunctionCode.READ_COILS) {
                dataElement = "coil";
            } else {
                dataElement = "discrete input";
            }
        }

        // First index of polled items (e.g. registers or coils) that is needed to read the object. For example, the
        // index of the register that corresponds to the first 16bits of float32 object.
        int firstObjectIndex;
        if (valueTypeBitCount < 16) {
            firstObjectIndex = (config.getStart() * valueTypeBitCount) / functionObjectBitSize;
        } else {
            firstObjectIndex = config.getStart();
        }
        // Convert object size to polled items. E.g. float32 -> 2 (registers)
        int objectSizeInPolledItemCount = Math.max(1, valueTypeBitCount / functionObjectBitSize);
        int lastObjectIndex = firstObjectIndex + objectSizeInPolledItemCount - 1;
        int pollObjectCount = pollTask.getRequest().getDataLength();

        if (firstObjectIndex >= pollObjectCount || lastObjectIndex >= pollObjectCount) {
            String errmsg = String.format(
                    "Out-of-bounds: tried to read %s elements with index %d to %d (zero based index). Poller reads only %d %s elements which means that maximum index (zero-indexed) is %d",
                    dataElement, firstObjectIndex, lastObjectIndex, pollObjectCount, dataElement, pollObjectCount);
            logger.error("ReadThing '{}' is out of bounds: {}", getThing().getLabel(), errmsg);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, errmsg);
            return false;
        } else {
            return true;
        }
    }

    /**
     * Implementation copied from BaseThingHandler
     *
     * @param thing
     * @return
     */
    private Bridge getBridgeOfThing(Thing thing) {
        ThingUID bridgeUID = thing.getBridgeUID();
        synchronized (this) {
            if (bridgeUID != null && thingRegistry != null) {
                return (Bridge) thingRegistry.get(bridgeUID);
            } else {
                return null;
            }
        }
    }

    @Override
    public synchronized void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        super.bridgeStatusChanged(bridgeStatusInfo);
        validateConfiguration();
    }

    private boolean containsOnOff(List<Class<? extends State>> channelAcceptedDataTypes) {
        return channelAcceptedDataTypes.stream().anyMatch(clz -> {
            return clz.equals(OnOffType.class);
        });
    }

    private boolean containsOpenClosed(List<Class<? extends State>> acceptedDataTypes) {
        return acceptedDataTypes.stream().anyMatch(clz -> {
            return clz.equals(OpenClosedType.class);
        });
    }

    @Override
    public void onRegisters(ModbusReadRequestBlueprint request, ModbusRegisterArray registers) {
        if (getThing().getStatus() != ThingStatus.ONLINE) {
            return;
        }
        DecimalType numericState = ModbusBitUtilities.extractStateFromRegisters(registers, config.getStart(),
                valueType);
        boolean boolValue = !numericState.equals(DecimalType.ZERO);
        Map<ChannelUID, State> state = processUpdatedValue(numericState, boolValue);
        synchronized (lastStateLock) {
            lastState = state;
        }

        updateState(ModbusBindingConstants.CHANNEL_LAST_READ_SUCCESS, new DateTimeType());
    }

    @Override
    public synchronized void onBits(ModbusReadRequestBlueprint request, BitArray bits) {
        if (getThing().getStatus() != ThingStatus.ONLINE) {
            return;
        }
        boolean boolValue = bits.getBit(config.getStart());
        DecimalType numericState = boolValue ? new DecimalType(BigDecimal.ONE) : DecimalType.ZERO;

        Map<ChannelUID, State> state = processUpdatedValue(numericState, boolValue);
        synchronized (lastStateLock) {
            lastState = state;
        }

        updateState(ModbusBindingConstants.CHANNEL_LAST_READ_SUCCESS, new DateTimeType());
    }

    @Override
    public synchronized void onError(ModbusReadRequestBlueprint request, Exception error) {
        logger.error("Thing {} received read error: {} {}. Stack trace follows for unexpected errors.",
                getThing().getLabel(), error.getClass().getName(), error.getMessage(), error);
        Map<ChannelUID, State> states = new HashMap<>();
        states.put(new ChannelUID(getThing().getUID(), ModbusBindingConstants.CHANNEL_LAST_READ_ERROR),
                new DateTimeType());

        synchronized (this) {
            // Update channels
            states.forEach((uid, state) -> {
                tryUpdateState(uid, state);
            });

            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    String.format("Error with read: %s: %s", error.getClass().getName(), error.getMessage()));
            synchronized (lastStateLock) {
                lastState = states;
            }
        }

    }

    public Optional<Map<ChannelUID, State>> getLastState() {
        return Optional.ofNullable(lastState);
    }

    private Map<ChannelUID, State> processUpdatedValue(DecimalType numericState, boolean boolValue) {
        Map<ChannelUID, State> states = new HashMap<>();
        boolean matchesTrigger = trigger.equals("*") || trigger.equalsIgnoreCase(numericState.toString());
        logger.trace("Thing '{}' with trigger '{}' matched numeric value '{}'? {}", getThing().getLabel(), trigger,
                numericState, matchesTrigger);
        if (matchesTrigger) {
            channelUIDToAcceptedDataTypes.entrySet().stream().forEach(entry -> {
                ChannelUID channelUID = entry.getKey();
                List<Class<? extends State>> acceptedDataTypes = entry.getValue();
                if (acceptedDataTypes.isEmpty()) {
                    // Channel is not linked -- skip
                    return;
                }

                State boolLikeState;
                if (containsOnOff(acceptedDataTypes)) {
                    boolLikeState = boolValue ? OnOffType.ON : OnOffType.OFF;
                } else if (containsOpenClosed(acceptedDataTypes)) {
                    boolLikeState = boolValue ? OpenClosedType.OPEN : OpenClosedType.CLOSED;
                } else {
                    boolLikeState = null;
                }

                State transformedState;
                if (transformation.isIdentityTransform()) {
                    if (boolLikeState != null) {
                        // A bit of smartness for ON/OFF and OPEN/CLOSED with boolean like items
                        transformedState = boolLikeState;
                    } else {
                        // Numeric states always go through transformation. This allows value of 17.5 to be converted to
                        // 17.5% with percent types (instead of raising error)
                        transformedState = transformation.transformState(bundleContext, acceptedDataTypes,
                                numericState);
                    }
                } else {
                    transformedState = transformation.transformState(bundleContext, acceptedDataTypes, numericState);
                }

                if (transformedState == null) {
                    logger.debug("Thing {}, channel {} will not be updated since transformation was unsuccesful",
                            getThing().getLabel(), channelUID);
                } else {
                    states.put(channelUID, transformedState);
                }
            });
        }

        states.put(new ChannelUID(getThing().getUID(), ModbusBindingConstants.CHANNEL_LAST_READ_SUCCESS),
                new DateTimeType());

        synchronized (this) {
            updateStatus(ThingStatus.ONLINE);
            // Update channels
            states.forEach((uid, state) -> {
                tryUpdateState(uid, state);
            });
        }
        return states;
    }

    private void tryUpdateState(ChannelUID uid, State state) {
        try {
            updateState(uid, state);
        } catch (IllegalArgumentException e) {
            logger.warn("Error updating state '{}' (type {}) to channel {}: {} {}", state,
                    Optional.ofNullable(state).map(s -> s.getClass().getName()).orElse("null"), uid,
                    e.getClass().getName(), e.getMessage());
        }
    }

}
