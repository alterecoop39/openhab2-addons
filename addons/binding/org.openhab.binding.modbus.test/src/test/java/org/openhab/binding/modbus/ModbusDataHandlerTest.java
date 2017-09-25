package org.openhab.binding.modbus;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.core.common.registry.ProviderChangeListener;
import org.eclipse.smarthome.core.internal.items.ItemRegistryImpl;
import org.eclipse.smarthome.core.items.Item;
import org.eclipse.smarthome.core.items.ItemProvider;
import org.eclipse.smarthome.core.library.items.ContactItem;
import org.eclipse.smarthome.core.library.items.DateTimeItem;
import org.eclipse.smarthome.core.library.items.DimmerItem;
import org.eclipse.smarthome.core.library.items.NumberItem;
import org.eclipse.smarthome.core.library.items.RollershutterItem;
import org.eclipse.smarthome.core.library.items.StringItem;
import org.eclipse.smarthome.core.library.items.SwitchItem;
import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.Channel;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingRegistry;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.ThingStatusInfo;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.eclipse.smarthome.core.thing.binding.ThingHandlerCallback;
import org.eclipse.smarthome.core.thing.binding.builder.BridgeBuilder;
import org.eclipse.smarthome.core.thing.binding.builder.ThingBuilder;
import org.eclipse.smarthome.core.thing.internal.BridgeImpl;
import org.eclipse.smarthome.core.thing.link.ItemChannelLink;
import org.eclipse.smarthome.core.thing.link.ItemChannelLinkProvider;
import org.eclipse.smarthome.core.thing.link.ItemChannelLinkRegistry;
import org.eclipse.smarthome.core.transform.TransformationException;
import org.eclipse.smarthome.core.transform.TransformationService;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.State;
import org.hamcrest.Matcher;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;
import org.openhab.binding.modbus.handler.ModbusDataThingHandler;
import org.openhab.binding.modbus.handler.ModbusPollerThingHandlerImpl;
import org.openhab.io.transport.modbus.BitArray;
import org.openhab.io.transport.modbus.ModbusConstants;
import org.openhab.io.transport.modbus.ModbusConstants.ValueType;
import org.openhab.io.transport.modbus.ModbusManager;
import org.openhab.io.transport.modbus.ModbusManager.PollTask;
import org.openhab.io.transport.modbus.ModbusManager.WriteTask;
import org.openhab.io.transport.modbus.ModbusReadFunctionCode;
import org.openhab.io.transport.modbus.ModbusReadRequestBlueprint;
import org.openhab.io.transport.modbus.ModbusRegister;
import org.openhab.io.transport.modbus.ModbusRegisterArray;
import org.openhab.io.transport.modbus.ModbusRegisterArrayImpl;
import org.openhab.io.transport.modbus.ModbusRegisterImpl;
import org.openhab.io.transport.modbus.ModbusResponse;
import org.openhab.io.transport.modbus.ModbusWriteCoilRequestBlueprint;
import org.openhab.io.transport.modbus.ModbusWriteFunctionCode;
import org.openhab.io.transport.modbus.ModbusWriteRegisterRequestBlueprint;
import org.openhab.io.transport.modbus.ModbusWriteRequestBlueprint;
import org.openhab.io.transport.modbus.endpoint.ModbusSlaveEndpoint;
import org.openhab.io.transport.modbus.endpoint.ModbusTCPSlaveEndpoint;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;

import com.google.common.collect.ImmutableMap;

@SuppressWarnings("restriction")
@RunWith(MockitoJUnitRunner.class)
public class ModbusDataHandlerTest {

    private class ItemChannelLinkRegistryTestImpl extends ItemChannelLinkRegistry {
        public ItemChannelLinkRegistryTestImpl() {
            super();
            this.setItemRegistry(itemRegistry);
            this.setThingRegistry(thingRegistry);
        }

        public void update() {
            addProvider(new ItemChannelLinkProvider() {

                @Override
                public void addProviderChangeListener(ProviderChangeListener<ItemChannelLink> listener) {
                }

                @Override
                public Collection<ItemChannelLink> getAll() {
                    return links;
                }

                @Override
                public void removeProviderChangeListener(ProviderChangeListener<ItemChannelLink> listener) {
                }
            });
        }
    };

    private class ItemRegistryTestImpl extends ItemRegistryImpl {
        public ItemRegistryTestImpl() {
            super();
        }

        public void update() {
            addProvider(new ItemProvider() {

                @Override
                public void addProviderChangeListener(ProviderChangeListener<Item> listener) {
                }

                @Override
                public Collection<Item> getAll() {
                    return items;
                }

                @Override
                public void removeProviderChangeListener(ProviderChangeListener<Item> listener) {
                }
            });
        }
    };

    private static final Map<String, Class<? extends Item>> channelToItemClass = new HashMap<>();
    static {
        channelToItemClass.put(ModbusBindingConstants.CHANNEL_SWITCH, SwitchItem.class);
        channelToItemClass.put(ModbusBindingConstants.CHANNEL_CONTACT, ContactItem.class);
        channelToItemClass.put(ModbusBindingConstants.CHANNEL_DATETIME, DateTimeItem.class);
        channelToItemClass.put(ModbusBindingConstants.CHANNEL_DIMMER, DimmerItem.class);
        channelToItemClass.put(ModbusBindingConstants.CHANNEL_NUMBER, NumberItem.class);
        channelToItemClass.put(ModbusBindingConstants.CHANNEL_STRING, StringItem.class);
        channelToItemClass.put(ModbusBindingConstants.CHANNEL_ROLLERSHUTTER, RollershutterItem.class);
    }

    private List<Thing> things = new ArrayList<>();
    private List<Item> items = new ArrayList<>();
    private List<ItemChannelLink> links = new ArrayList<>();
    private List<WriteTask> writeTasks = new ArrayList<>();

    @Mock
    private BundleContext bundleContext;

    @Mock
    private ThingHandlerCallback thingCallback;

    @Mock
    private ThingRegistry thingRegistry;

    @Mock
    private ModbusManager manager;

    private ItemRegistryTestImpl itemRegistry = new ItemRegistryTestImpl();
    private ItemChannelLinkRegistryTestImpl linkRegistry = new ItemChannelLinkRegistryTestImpl();

    Map<ChannelUID, List<State>> stateUpdates = new HashMap<>();

    private Map<String, String> channelToAcceptedType = ImmutableMap.<String, String> builder()
            .put(ModbusBindingConstants.CHANNEL_SWITCH, "Switch").put(ModbusBindingConstants.CHANNEL_CONTACT, "Contact")
            .put(ModbusBindingConstants.CHANNEL_DATETIME, "DateTime")
            .put(ModbusBindingConstants.CHANNEL_DIMMER, "Dimmer").put(ModbusBindingConstants.CHANNEL_NUMBER, "Number")
            .put(ModbusBindingConstants.CHANNEL_STRING, "String")
            .put(ModbusBindingConstants.CHANNEL_ROLLERSHUTTER, "Rollershutter")
            .put(ModbusBindingConstants.CHANNEL_LAST_READ_SUCCESS, "DateTime")
            .put(ModbusBindingConstants.CHANNEL_LAST_WRITE_SUCCESS, "DateTime")
            .put(ModbusBindingConstants.CHANNEL_LAST_WRITE_ERROR, "DateTime")
            .put(ModbusBindingConstants.CHANNEL_LAST_READ_ERROR, "DateTime").build();

    private void registerThingToMockRegistry(Thing thing) {
        things.add(thing);
        // update bridge with the new child thing
        if (thing.getBridgeUID() != null) {
            ThingUID bridgeUID = thing.getBridgeUID();
            things.stream().filter(t -> t.getUID().equals(bridgeUID)).findFirst()
                    .ifPresent(t -> ((BridgeImpl) t).addThing(thing));
        }
    }

    private void hookItemRegistry(ThingHandler thingHandler) {
        Field thingRegisteryField;
        try {
            thingRegisteryField = BaseThingHandler.class.getDeclaredField("thingRegistry");
            thingRegisteryField.setAccessible(true);
            thingRegisteryField.set(thingHandler, thingRegistry);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        } catch (SecurityException e) {
            throw new RuntimeException(e);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private void hookLinkRegistry(ThingHandler thingHandler) {
        Field linkRegistryField;
        try {
            linkRegistryField = BaseThingHandler.class.getDeclaredField("linkRegistry");
            linkRegistryField.setAccessible(true);
            linkRegistryField.set(thingHandler, linkRegistry);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        } catch (SecurityException e) {
            throw new RuntimeException(e);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("null")
    private void hookStatusUpdates(Thing thing) {
        Mockito.doAnswer(invocation -> {
            thing.setStatusInfo(invocation.getArgumentAt(1, ThingStatusInfo.class));
            return null;
        }).when(thingCallback).statusUpdated(Matchers.same(thing), Matchers.any());
    }

    @SuppressWarnings("null")
    private void hookStateUpdates(Thing thing) {
        Mockito.doAnswer(invocation -> {
            ChannelUID channelUID = invocation.getArgumentAt(0, ChannelUID.class);
            State state = invocation.getArgumentAt(1, State.class);
            stateUpdates.putIfAbsent(channelUID, new ArrayList<>());
            stateUpdates.get(channelUID).add(state);
            return null;
        }).when(thingCallback).stateUpdated(any(), any());
    }

    @SuppressWarnings("null")
    private Bridge createPoller(String readwriteId, String pollerId, PollTask task) {

        final Bridge poller;
        ThingUID thingUID = new ThingUID(ModbusBindingConstants.THING_TYPE_MODBUS_POLLER, pollerId);
        BridgeBuilder builder = BridgeBuilder.create(ModbusBindingConstants.THING_TYPE_MODBUS_POLLER, thingUID)
                .withLabel("label for " + pollerId);
        for (Entry<String, String> entry : channelToAcceptedType.entrySet()) {
            String channelId = entry.getKey();
            String channelAcceptedType = entry.getValue();
            builder = builder.withChannel(new Channel(new ChannelUID(thingUID, channelId), channelAcceptedType));
        }
        poller = builder.build();
        poller.setStatusInfo(new ThingStatusInfo(ThingStatus.ONLINE, ThingStatusDetail.NONE, ""));
        ModbusPollerThingHandlerImpl handler = Mockito.mock(ModbusPollerThingHandlerImpl.class);
        doReturn(task).when(handler).getPollTask();
        Supplier<ModbusManager> managerRef = () -> manager;
        doReturn(managerRef).when(handler).getManagerRef();
        poller.setHandler(handler);
        registerThingToMockRegistry(poller);
        return poller;
    }

    private ModbusDataThingHandler createDataHandler(String id, Bridge bridge,
            Function<ThingBuilder, ThingBuilder> builderConfigurator) {
        return createDataHandler(id, bridge, builderConfigurator, null);
    }

    private ModbusDataThingHandler createDataHandler(String id, Bridge bridge,
            Function<ThingBuilder, ThingBuilder> builderConfigurator, BundleContext context) {
        ThingUID thingUID = new ThingUID(ModbusBindingConstants.THING_TYPE_MODBUS_DATA, id);
        ThingBuilder builder = ThingBuilder.create(ModbusBindingConstants.THING_TYPE_MODBUS_DATA, thingUID)
                .withLabel("label for " + id);
        for (Entry<String, String> entry : channelToAcceptedType.entrySet()) {
            String channelId = entry.getKey();
            String channelAcceptedType = entry.getValue();
            builder = builder.withChannel(new Channel(new ChannelUID(thingUID, channelId), channelAcceptedType));
        }
        if (builderConfigurator != null) {
            builder = builderConfigurator.apply(builder);
        }

        Thing dataThing = builder.withBridge(bridge.getUID()).build();
        registerThingToMockRegistry(dataThing);
        hookStatusUpdates(dataThing);
        hookStateUpdates(dataThing);

        ModbusDataThingHandler dataThingHandler = new ModbusDataThingHandler(dataThing);
        hookItemRegistry(dataThingHandler);
        hookLinkRegistry(dataThingHandler);
        dataThing.setHandler(dataThingHandler);
        dataThingHandler.setCallback(thingCallback);
        if (context != null) {
            dataThingHandler.setBundleContext(context);
        }
        dataThingHandler.initialize();
        return dataThingHandler;
    }

    private void assertSingleStateUpdate(ModbusDataThingHandler handler, String channel, Matcher<State> matcher) {
        List<State> updates = stateUpdates.get(new ChannelUID(handler.getThing().getUID(), channel));
        if (updates != null) {
            assertThat(updates.size(), is(equalTo(1)));
        }
        assertThat(updates == null ? null : updates.get(0), is(matcher));
    }

    private void assertSingleStateUpdate(ModbusDataThingHandler handler, String channel, State state) {
        assertSingleStateUpdate(handler, channel, is(equalTo(state)));
    }

    //
    // /**
    // * Updates item and link registries such that added items and links are reflected in handlers
    // */
    // private void updateItemsAndLinks() {
    // itemRegistry.update();
    // linkRegistry.update();
    // }

    @Before
    public void setUp() {
        Mockito.when(thingRegistry.get(Matchers.any())).then(invocation -> {
            ThingUID uid = invocation.getArgumentAt(0, ThingUID.class);
            for (Thing thing : things) {
                if (thing.getUID().equals(uid)) {
                    return thing;
                }
            }
            throw new IllegalArgumentException("UID is unknown: " + uid.getAsString());
        });

        Mockito.when(manager.submitOneTimeWrite(any())).then(invocation -> {
            WriteTask task = invocation.getArgumentAt(0, WriteTask.class);
            writeTasks.add(task);
            return Mockito.mock(ScheduledFuture.class);
        });
    }

    private void testOutOfBoundsGeneric(int pollStart, int pollLength, String start,
            ModbusReadFunctionCode functionCode, ValueType valueType, ThingStatus expectedStatus) {
        testOutOfBoundsGeneric(pollStart, pollLength, start, functionCode, valueType, expectedStatus, null);
    }

    @SuppressWarnings({ "null" })
    private void testOutOfBoundsGeneric(int pollStart, int pollLength, String start,
            ModbusReadFunctionCode functionCode, ValueType valueType, ThingStatus expectedStatus,
            BundleContext context) {
        ModbusSlaveEndpoint endpoint = new ModbusTCPSlaveEndpoint("thisishost", 502);

        // Minimally mocked request
        ModbusReadRequestBlueprint request = Mockito.mock(ModbusReadRequestBlueprint.class);
        doReturn(pollStart).when(request).getReference();
        doReturn(pollLength).when(request).getDataLength();
        doReturn(functionCode).when(request).getFunctionCode();

        PollTask task = Mockito.mock(PollTask.class);
        doReturn(endpoint).when(task).getEndpoint();
        doReturn(request).when(task).getRequest();

        Bridge pollerThing = createPoller("data1", "poller1", task);

        Configuration dataConfig = new Configuration();
        dataConfig.put("readStart", start);
        dataConfig.put("readTransform", "default");
        dataConfig.put("readValueType", valueType.getConfigValue());
        dataConfig.put("writeValueType", valueType.getConfigValue());
        ModbusDataThingHandler dataHandler = createDataHandler("data1", pollerThing,
                builder -> builder.withConfiguration(dataConfig), context);
        assertThat(dataHandler.getThing().getStatus(), is(equalTo(expectedStatus)));

    }

    @Test
    public void testInitCoilsOutOfIndex() {
        testOutOfBoundsGeneric(4, 3, "8", ModbusReadFunctionCode.READ_COILS, ModbusConstants.ValueType.BIT,
                ThingStatus.OFFLINE);
    }

    @Test
    public void testInitCoilsOK() {
        testOutOfBoundsGeneric(4, 3, "6", ModbusReadFunctionCode.READ_COILS, ModbusConstants.ValueType.BIT,
                ThingStatus.ONLINE);
    }

    @Test
    public void testInitRegistersWithBitOutOfIndex() {
        testOutOfBoundsGeneric(4, 3, "8.0", ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS,
                ModbusConstants.ValueType.BIT, ThingStatus.OFFLINE);
    }

    @Test
    public void testInitRegistersWithBitOutOfIndex2() {
        testOutOfBoundsGeneric(4, 3, "7.16", ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS,
                ModbusConstants.ValueType.BIT, ThingStatus.OFFLINE);
    }

    @Test
    public void testInitRegistersWithBitOK() {
        testOutOfBoundsGeneric(4, 3, "6.0", ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS,
                ModbusConstants.ValueType.BIT, ThingStatus.ONLINE);
    }

    @Test
    public void testInitRegistersWithBitOK2() {
        testOutOfBoundsGeneric(4, 3, "6.15", ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS,
                ModbusConstants.ValueType.BIT, ThingStatus.ONLINE);
    }

    @Test
    public void testInitRegistersWithInt8OutOfIndex() {
        testOutOfBoundsGeneric(4, 3, "8.0", ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS,
                ModbusConstants.ValueType.INT8, ThingStatus.OFFLINE);
    }

    @Test
    public void testInitRegistersWithInt8OutOfIndex2() {
        testOutOfBoundsGeneric(4, 3, "7.2", ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS,
                ModbusConstants.ValueType.INT8, ThingStatus.OFFLINE);
    }

    @Test
    public void testInitRegistersWithInt8OK() {
        testOutOfBoundsGeneric(4, 3, "6.0", ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS,
                ModbusConstants.ValueType.INT8, ThingStatus.ONLINE);
    }

    @Test
    public void testInitRegistersWithInt8OK2() {
        testOutOfBoundsGeneric(4, 3, "6.1", ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS,
                ModbusConstants.ValueType.INT8, ThingStatus.ONLINE);
    }

    @Test
    public void testInitRegistersWithInt16OK() {
        testOutOfBoundsGeneric(4, 3, "6", ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS,
                ModbusConstants.ValueType.INT16, ThingStatus.ONLINE);
    }

    @Test
    public void testInitRegistersWithInt16OutOfBounds() {
        testOutOfBoundsGeneric(4, 3, "8", ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS,
                ModbusConstants.ValueType.INT16, ThingStatus.OFFLINE);
    }

    @Test
    public void testInitRegistersWithInt16NoDecimalFormatAllowed() {
        testOutOfBoundsGeneric(4, 3, "7.0", ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS,
                ModbusConstants.ValueType.INT16, ThingStatus.OFFLINE);
    }

    @Test
    public void testInitRegistersWithInt32OK() {
        testOutOfBoundsGeneric(4, 3, "5", ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS,
                ModbusConstants.ValueType.INT32, ThingStatus.ONLINE);
    }

    @Test
    public void testInitRegistersWithInt32OutOfBounds() {
        testOutOfBoundsGeneric(4, 3, "6", ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS,
                ModbusConstants.ValueType.INT32, ThingStatus.OFFLINE);
    }

    private ModbusDataThingHandler testReadHandlingGeneric(ModbusReadFunctionCode functionCode, String start,
            String transform, ValueType valueType, BitArray bits, ModbusRegisterArray registers, Exception error) {
        return testReadHandlingGeneric(functionCode, start, transform, valueType, bits, registers, error, null);
    }

    @SuppressWarnings({ "null" })
    private ModbusDataThingHandler testReadHandlingGeneric(ModbusReadFunctionCode functionCode, String start,
            String transform, ValueType valueType, BitArray bits, ModbusRegisterArray registers, Exception error,
            BundleContext context) {
        ModbusSlaveEndpoint endpoint = new ModbusTCPSlaveEndpoint("thisishost", 502);

        int pollLength = 3;

        // Minimally mocked request
        ModbusReadRequestBlueprint request = Mockito.mock(ModbusReadRequestBlueprint.class);
        doReturn(pollLength).when(request).getDataLength();
        doReturn(functionCode).when(request).getFunctionCode();

        PollTask task = Mockito.mock(PollTask.class);
        doReturn(endpoint).when(task).getEndpoint();
        doReturn(request).when(task).getRequest();

        Bridge poller = createPoller("readwrite1", "poller1", task);

        Configuration dataConfig = new Configuration();
        dataConfig.put("readStart", start);
        dataConfig.put("readTransform", transform);
        dataConfig.put("readValueType", valueType.getConfigValue());

        String thingId = "read1";
        // //
        // // Bind all channels to corresponding items
        // //
        // for (String channel : channelToItemClass.keySet()) {
        // String itemName = channel + "item";
        // links.add(new ItemChannelLink(itemName,
        // new ChannelUID(new ThingUID(ModbusBindingConstants.THING_TYPE_MODBUS_WRITE, thingId), channel)));
        // Class<?> clz = channelToItemClass.get(channel);
        // Item item;
        // try {
        // item = (Item) clz.getConstructor(String.class).newInstance(itemName);
        // } catch (NoSuchMethodException e) {
        // throw new RuntimeException(e);
        // } catch (InvocationTargetException e) {
        // throw new RuntimeException(e);
        // } catch (IllegalAccessException e) {
        // throw new RuntimeException(e);
        // } catch (InstantiationException e) {
        // throw new RuntimeException(e);
        // }
        // items.add(item);
        // }
        //
        // updateItemsAndLinks();

        ModbusDataThingHandler dataHandler = createDataHandler(thingId, poller,
                builder -> builder.withConfiguration(dataConfig), context);

        assertThat(dataHandler.getThing().getStatus(), is(equalTo(ThingStatus.ONLINE)));

        // call callbacks
        if (bits != null) {
            assert registers == null;
            assert error == null;
            dataHandler.onBits(request, bits);
        } else if (registers != null) {
            assert bits == null;
            assert error == null;
            dataHandler.onRegisters(request, registers);
        } else {
            assert bits == null;
            assert registers == null;
            assert error != null;
            dataHandler.onError(request, error);
        }
        return dataHandler;
    }

    @SuppressWarnings({ "null" })
    private ModbusDataThingHandler testWriteHandlingGeneric(Integer start, String transform, ValueType valueType,
            String writeType, ModbusWriteFunctionCode successFC, String channel, Command command, Exception error,
            BundleContext context) {
        ModbusSlaveEndpoint endpoint = new ModbusTCPSlaveEndpoint("thisishost", 502);

        // Minimally mocked request
        ModbusReadRequestBlueprint request = Mockito.mock(ModbusReadRequestBlueprint.class);

        PollTask task = Mockito.mock(PollTask.class);
        doReturn(endpoint).when(task).getEndpoint();
        doReturn(request).when(task).getRequest();

        Bridge poller = createPoller("readwrite1", "poller1", task);

        Configuration dataConfig = new Configuration();
        dataConfig.put("readStart", "");
        dataConfig.put("writeStart", start);
        dataConfig.put("writeTransform", transform);
        dataConfig.put("writeValueType", valueType.getConfigValue());
        dataConfig.put("writeType", writeType);

        String thingId = "write";

        ModbusDataThingHandler dataHandler = createDataHandler(thingId, poller,
                builder -> builder.withConfiguration(dataConfig), context);

        assertThat(dataHandler.getThing().getStatus(), is(equalTo(ThingStatus.ONLINE)));

        dataHandler.handleCommand(new ChannelUID(dataHandler.getThing().getUID(), channel), command);

        if (error != null) {
            dataHandler.onError(request, error);
        } else {
            ModbusResponse resp = new ModbusResponse() {

                @Override
                public int getFunctionCode() {
                    return successFC.getFunctionCode();
                }
            };
            dataHandler.onWriteResponse(Mockito.mock(ModbusWriteRequestBlueprint.class), resp);
        }
        return dataHandler;
    }

    @SuppressWarnings("null")
    @Test
    public void testOnError() {
        ModbusDataThingHandler dataHandler = testReadHandlingGeneric(ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS,
                "0", "default", ModbusConstants.ValueType.FLOAT32, null, null, new Exception("fooerror"));

        assertThat(stateUpdates.size(), is(equalTo(1)));
        assertThat(
                stateUpdates.get(
                        dataHandler.getThing().getChannel(ModbusBindingConstants.CHANNEL_LAST_READ_ERROR).getUID()),
                is(notNullValue()));
    }

    @Test
    public void testOnRegistersInt16StaticTransformation() {
        ModbusDataThingHandler dataHandler = testReadHandlingGeneric(ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS,
                "0", "-3", ModbusConstants.ValueType.INT16, null,
                new ModbusRegisterArrayImpl(new ModbusRegister[] { new ModbusRegisterImpl((byte) 0xff, (byte) 0xfd) }),
                null);

        assertSingleStateUpdate(dataHandler, ModbusBindingConstants.CHANNEL_LAST_READ_SUCCESS,
                is(notNullValue(State.class)));
        assertSingleStateUpdate(dataHandler, ModbusBindingConstants.CHANNEL_LAST_READ_ERROR,
                is(nullValue(State.class)));

        // -3 converts to "true"
        assertSingleStateUpdate(dataHandler, ModbusBindingConstants.CHANNEL_CONTACT, is(nullValue(State.class)));
        assertSingleStateUpdate(dataHandler, ModbusBindingConstants.CHANNEL_SWITCH, is(nullValue(State.class)));
        assertSingleStateUpdate(dataHandler, ModbusBindingConstants.CHANNEL_DIMMER, is(nullValue(State.class)));
        assertSingleStateUpdate(dataHandler, ModbusBindingConstants.CHANNEL_NUMBER, new DecimalType(-3));
        // roller shutter fails since -3 is invalid value (not between 0...100)
        // assertThatStateContains(state, ModbusBindingConstants.CHANNEL_ROLLERSHUTTER, new PercentType(1));
        assertSingleStateUpdate(dataHandler, ModbusBindingConstants.CHANNEL_STRING, new StringType("-3"));
        // no datetime, conversion not possible without transformation
    }

    private void mockTransformation(String name, TransformationService service) throws InvalidSyntaxException {
        doReturn(Arrays.asList(new Object[] { null })).when(bundleContext)
                .getServiceReferences(TransformationService.class, "(smarthome.transform=" + name + ")");
        doReturn(service).when(bundleContext).getService(any());
    }

    @Test
    public void testOnRegistersRealTransformation() throws InvalidSyntaxException {
        mockTransformation("MULTIPLY", new TransformationService() {

            @Override
            public String transform(String function, String source) throws TransformationException {
                return String.valueOf(Integer.parseInt(function) * Integer.parseInt(source));
            }
        });
        ModbusDataThingHandler dataHandler = testReadHandlingGeneric(ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS,
                "0", "MULTIPLY(10)", ModbusConstants.ValueType.INT16, null,
                new ModbusRegisterArrayImpl(new ModbusRegister[] { new ModbusRegisterImpl((byte) 0xff, (byte) 0xfd) }),
                null, bundleContext);

        assertSingleStateUpdate(dataHandler, ModbusBindingConstants.CHANNEL_LAST_READ_SUCCESS,
                is(notNullValue(State.class)));
        assertSingleStateUpdate(dataHandler, ModbusBindingConstants.CHANNEL_LAST_READ_ERROR,
                is(nullValue(State.class)));

        // -3 converts to "true"
        assertSingleStateUpdate(dataHandler, ModbusBindingConstants.CHANNEL_CONTACT, is(nullValue(State.class)));
        assertSingleStateUpdate(dataHandler, ModbusBindingConstants.CHANNEL_SWITCH, is(nullValue(State.class)));
        assertSingleStateUpdate(dataHandler, ModbusBindingConstants.CHANNEL_DIMMER, is(nullValue(State.class)));
        assertSingleStateUpdate(dataHandler, ModbusBindingConstants.CHANNEL_NUMBER, new DecimalType(-30));
        // roller shutter fails since -3 is invalid value (not between 0...100)
        assertSingleStateUpdate(dataHandler, ModbusBindingConstants.CHANNEL_ROLLERSHUTTER, is(nullValue(State.class)));
        assertSingleStateUpdate(dataHandler, ModbusBindingConstants.CHANNEL_STRING, new StringType("-30"));
        // no datetime, conversion not possible without transformation
    }

    @Test
    public void testOnRegistersRealTransformation2() throws InvalidSyntaxException {
        mockTransformation("ONOFF", new TransformationService() {

            @Override
            public String transform(String function, String source) throws TransformationException {
                return Integer.parseInt(source) != 0 ? "ON" : "OFF";
            }
        });
        ModbusDataThingHandler dataHandler = testReadHandlingGeneric(ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS,
                "0", "ONOFF(10)", ModbusConstants.ValueType.INT16, null,
                new ModbusRegisterArrayImpl(new ModbusRegister[] { new ModbusRegisterImpl((byte) 0xff, (byte) 0xfd) }),
                null, bundleContext);

        assertSingleStateUpdate(dataHandler, ModbusBindingConstants.CHANNEL_LAST_READ_SUCCESS,
                is(notNullValue(State.class)));
        assertSingleStateUpdate(dataHandler, ModbusBindingConstants.CHANNEL_LAST_READ_ERROR,
                is(nullValue(State.class)));

        assertSingleStateUpdate(dataHandler, ModbusBindingConstants.CHANNEL_CONTACT, is(nullValue(State.class)));
        assertSingleStateUpdate(dataHandler, ModbusBindingConstants.CHANNEL_SWITCH, is(equalTo(OnOffType.ON)));
        assertSingleStateUpdate(dataHandler, ModbusBindingConstants.CHANNEL_DIMMER, is(equalTo(OnOffType.ON)));
        assertSingleStateUpdate(dataHandler, ModbusBindingConstants.CHANNEL_NUMBER, is(nullValue(State.class)));
        assertSingleStateUpdate(dataHandler, ModbusBindingConstants.CHANNEL_ROLLERSHUTTER, is(nullValue(State.class)));
        assertSingleStateUpdate(dataHandler, ModbusBindingConstants.CHANNEL_STRING, is(equalTo(new StringType("ON"))));
    }

    @Test
    public void testWriteRealTransformation() throws InvalidSyntaxException {
        mockTransformation("MULTIPLY", new TransformationService() {

            @Override
            public String transform(String function, String source) throws TransformationException {
                return String.valueOf(Integer.parseInt(function) * Integer.parseInt(source));
            }
        });
        ModbusDataThingHandler dataHandler = testWriteHandlingGeneric(50, "MULTIPLY(10)",
                ModbusConstants.ValueType.INT16, "coil", ModbusWriteFunctionCode.WRITE_COIL, "number",
                new DecimalType("2"), null, bundleContext);

        assertSingleStateUpdate(dataHandler, ModbusBindingConstants.CHANNEL_LAST_WRITE_SUCCESS,
                is(notNullValue(State.class)));
        assertSingleStateUpdate(dataHandler, ModbusBindingConstants.CHANNEL_LAST_WRITE_ERROR,
                is(nullValue(State.class)));
        assertThat(writeTasks.size(), is(equalTo(1)));
        WriteTask writeTask = writeTasks.get(0);
        assertThat(writeTask.getRequest().getFunctionCode(), is(equalTo(ModbusWriteFunctionCode.WRITE_COIL)));
        assertThat(writeTask.getRequest().getReference(), is(equalTo(50)));
        assertThat(((ModbusWriteCoilRequestBlueprint) writeTask.getRequest()).getCoils().size(), is(equalTo(1)));
        // Since transform output is non-zero, it is mapped as "true"
        assertThat(((ModbusWriteCoilRequestBlueprint) writeTask.getRequest()).getCoils().getBit(0), is(equalTo(true)));
    }

    @Test
    public void testWriteRealTransformation2() throws InvalidSyntaxException {
        mockTransformation("ZERO", new TransformationService() {

            @Override
            public String transform(String function, String source) throws TransformationException {
                return "0";
            }
        });
        ModbusDataThingHandler dataHandler = testWriteHandlingGeneric(50, "ZERO(foobar)",
                ModbusConstants.ValueType.INT16, "coil", ModbusWriteFunctionCode.WRITE_COIL, "number",
                new DecimalType("2"), null, bundleContext);

        assertSingleStateUpdate(dataHandler, ModbusBindingConstants.CHANNEL_LAST_WRITE_SUCCESS,
                is(notNullValue(State.class)));
        assertSingleStateUpdate(dataHandler, ModbusBindingConstants.CHANNEL_LAST_WRITE_ERROR,
                is(nullValue(State.class)));
        assertThat(writeTasks.size(), is(equalTo(1)));
        WriteTask writeTask = writeTasks.get(0);
        assertThat(writeTask.getRequest().getFunctionCode(), is(equalTo(ModbusWriteFunctionCode.WRITE_COIL)));
        assertThat(writeTask.getRequest().getReference(), is(equalTo(50)));
        assertThat(((ModbusWriteCoilRequestBlueprint) writeTask.getRequest()).getCoils().size(), is(equalTo(1)));
        // Since transform output is zero, it is mapped as "false"
        assertThat(((ModbusWriteCoilRequestBlueprint) writeTask.getRequest()).getCoils().getBit(0), is(equalTo(false)));
    }

    @Test
    public void testWriteRealTransformation3() throws InvalidSyntaxException {
        mockTransformation("RANDOM", new TransformationService() {

            @Override
            public String transform(String function, String source) throws TransformationException {
                return "5";
            }
        });
        ModbusDataThingHandler dataHandler = testWriteHandlingGeneric(50, "RANDOM(foobar)",
                ModbusConstants.ValueType.INT16, "holding", ModbusWriteFunctionCode.WRITE_SINGLE_REGISTER, "number",
                new DecimalType("2"), null, bundleContext);

        assertSingleStateUpdate(dataHandler, ModbusBindingConstants.CHANNEL_LAST_WRITE_SUCCESS,
                is(notNullValue(State.class)));
        assertSingleStateUpdate(dataHandler, ModbusBindingConstants.CHANNEL_LAST_WRITE_ERROR,
                is(nullValue(State.class)));
        assertThat(writeTasks.size(), is(equalTo(1)));
        WriteTask writeTask = writeTasks.get(0);
        assertThat(writeTask.getRequest().getFunctionCode(),
                is(equalTo(ModbusWriteFunctionCode.WRITE_SINGLE_REGISTER)));
        assertThat(writeTask.getRequest().getReference(), is(equalTo(50)));
        assertThat(((ModbusWriteRegisterRequestBlueprint) writeTask.getRequest()).getRegisters().size(),
                is(equalTo(1)));
        assertThat(
                ((ModbusWriteRegisterRequestBlueprint) writeTask.getRequest()).getRegisters().getRegister(0).getValue(),
                is(equalTo(5)));
    }

    @Test
    public void testWriteRealTransformation4() throws InvalidSyntaxException {
        // assertThat(WriteRequestJsonUtilities.fromJson(55, "[{"//
        // + "\"functionCode\": 15,"//
        // + "\"address\": 5412,"//
        // + "\"value\": [1, 0, 5]"//
        // + "}]").toArray(),
        // arrayContaining((Matcher) new CoilMatcher(55, 5412, ModbusWriteFunctionCode.WRITE_MULTIPLE_COILS, true,
        // false, true)));
        mockTransformation("JSON", new TransformationService() {

            @Override
            public String transform(String function, String source) throws TransformationException {
                return "[{"//
                        + "\"functionCode\": 16,"//
                        + "\"address\": 5412,"//
                        + "\"value\": [1, 0, 5]"//
                        + "},"//
                        + "{"//
                        + "\"functionCode\": 6,"//
                        + "\"address\": 555,"//
                        + "\"value\": [3]"//
                        + "}]";
            }
        });
        ModbusDataThingHandler dataHandler = testWriteHandlingGeneric(50, "JSON(foobar)",
                ModbusConstants.ValueType.INT16, "holding", ModbusWriteFunctionCode.WRITE_MULTIPLE_REGISTERS, "number",
                new DecimalType("2"), null, bundleContext);

        assertSingleStateUpdate(dataHandler, ModbusBindingConstants.CHANNEL_LAST_WRITE_SUCCESS,
                is(notNullValue(State.class)));
        assertSingleStateUpdate(dataHandler, ModbusBindingConstants.CHANNEL_LAST_WRITE_ERROR,
                is(nullValue(State.class)));
        assertThat(writeTasks.size(), is(equalTo(2)));
        {
            WriteTask writeTask = writeTasks.get(0);
            assertThat(writeTask.getRequest().getFunctionCode(),
                    is(equalTo(ModbusWriteFunctionCode.WRITE_MULTIPLE_REGISTERS)));
            assertThat(writeTask.getRequest().getReference(), is(equalTo(5412)));
            assertThat(((ModbusWriteRegisterRequestBlueprint) writeTask.getRequest()).getRegisters().size(),
                    is(equalTo(3)));
            assertThat(((ModbusWriteRegisterRequestBlueprint) writeTask.getRequest()).getRegisters().getRegister(0)
                    .getValue(), is(equalTo(1)));
            assertThat(((ModbusWriteRegisterRequestBlueprint) writeTask.getRequest()).getRegisters().getRegister(1)
                    .getValue(), is(equalTo(0)));
            assertThat(((ModbusWriteRegisterRequestBlueprint) writeTask.getRequest()).getRegisters().getRegister(2)
                    .getValue(), is(equalTo(5)));
        }
        {
            WriteTask writeTask = writeTasks.get(1);
            assertThat(writeTask.getRequest().getFunctionCode(),
                    is(equalTo(ModbusWriteFunctionCode.WRITE_SINGLE_REGISTER)));
            assertThat(writeTask.getRequest().getReference(), is(equalTo(555)));
            assertThat(((ModbusWriteRegisterRequestBlueprint) writeTask.getRequest()).getRegisters().size(),
                    is(equalTo(1)));
            assertThat(((ModbusWriteRegisterRequestBlueprint) writeTask.getRequest()).getRegisters().getRegister(0)
                    .getValue(), is(equalTo(3)));
        }
    }

    private void testValueTypeGeneric(ModbusReadFunctionCode functionCode, ValueType valueType,
            ThingStatus expectedStatus) {
        ModbusSlaveEndpoint endpoint = new ModbusTCPSlaveEndpoint("thisishost", 502);

        // Minimally mocked request
        ModbusReadRequestBlueprint request = Mockito.mock(ModbusReadRequestBlueprint.class);
        doReturn(3).when(request).getDataLength();
        doReturn(functionCode).when(request).getFunctionCode();

        PollTask task = Mockito.mock(PollTask.class);
        doReturn(endpoint).when(task).getEndpoint();
        doReturn(request).when(task).getRequest();

        Bridge poller = createPoller("readwrite1", "poller1", task);

        Configuration dataConfig = new Configuration();
        dataConfig.put("readStart", "1");
        dataConfig.put("readTransform", "default");
        dataConfig.put("readValueType", valueType.getConfigValue());
        dataConfig.put("writeValueType", valueType.getConfigValue());
        ModbusDataThingHandler dataHandler = createDataHandler("data1", poller,
                builder -> builder.withConfiguration(dataConfig));
        assertThat(dataHandler.getThing().getStatus(), is(equalTo(expectedStatus)));
    }

    @Test
    public void testCoilDoesNotAcceptFloat32ValueType() {
        testValueTypeGeneric(ModbusReadFunctionCode.READ_COILS, ModbusConstants.ValueType.FLOAT32, ThingStatus.OFFLINE);
    }

    @Test
    public void testCoilAcceptsBitValueType() {
        testValueTypeGeneric(ModbusReadFunctionCode.READ_COILS, ModbusConstants.ValueType.BIT, ThingStatus.ONLINE);
    }

    @Test
    public void testDiscreteInputDoesNotAcceptFloat32ValueType() {
        testValueTypeGeneric(ModbusReadFunctionCode.READ_INPUT_DISCRETES, ModbusConstants.ValueType.FLOAT32,
                ThingStatus.OFFLINE);
    }

    @Test
    public void testDiscreteInputAcceptsBitValueType() {
        testValueTypeGeneric(ModbusReadFunctionCode.READ_INPUT_DISCRETES, ModbusConstants.ValueType.BIT,
                ThingStatus.ONLINE);
    }
}
