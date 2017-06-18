package org.openhab.binding.modbus;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.assertThat;

import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusInfo;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.eclipse.smarthome.core.thing.binding.ThingHandlerCallback;
import org.eclipse.smarthome.core.thing.binding.builder.BridgeBuilder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;
import org.openhab.binding.modbus.handler.ModbusTcpThingHandler;
import org.openhab.io.transport.modbus.ModbusManager;
import org.openhab.io.transport.modbus.endpoint.EndpointPoolConfiguration;
import org.openhab.io.transport.modbus.endpoint.ModbusSlaveEndpoint;
import org.openhab.io.transport.modbus.endpoint.ModbusTCPSlaveEndpoint;

@RunWith(MockitoJUnitRunner.class)
public class ModbusTcpThingHandlerTest {

    @Mock
    private ModbusManager modbusManager;
    private Bridge thing;

    private static BridgeBuilder createTcpThingBuilder(String id) {
        return BridgeBuilder.create(ModbusBindingConstants.THING_TYPE_MODBUS_TCP,
                new ThingUID(ModbusBindingConstants.THING_TYPE_MODBUS_TCP, id));
    }

    @Test
    public void testInitializeAndSlaveEndpoint() {
        Configuration thingConfig = new Configuration();
        thingConfig.put("host", "thisishost");
        thingConfig.put("port", 44);
        thingConfig.put("id", 9);
        thingConfig.put("timeBetweenTransactionsMillis", 1);
        thingConfig.put("timeBetweenReconnectMillis", 2);
        thingConfig.put("connectMaxTries", 3);
        thingConfig.put("reconnectAfterMillis", 4);
        thingConfig.put("connectTimeoutMillis", 5);

        EndpointPoolConfiguration expectedPoolConfiguration = new EndpointPoolConfiguration();
        expectedPoolConfiguration.setConnectMaxTries(3);
        expectedPoolConfiguration.setConnectTimeoutMillis(5);
        expectedPoolConfiguration.setInterConnectDelayMillis(2);
        expectedPoolConfiguration.setPassivateBorrowMinMillis(1);
        expectedPoolConfiguration.setReconnectAfterMillis(4);

        thing = createTcpThingBuilder("tcpendpoint").withConfiguration(thingConfig).build();
        ThingHandlerCallback thingCallback = Mockito.mock(ThingHandlerCallback.class);
        Mockito.doAnswer(invocation -> {
            thing.setStatusInfo(invocation.getArgumentAt(1, ThingStatusInfo.class));
            return null;
        }).when(thingCallback).statusUpdated(Matchers.same(thing), Matchers.any());

        ModbusTcpThingHandler thingHandler = new ModbusTcpThingHandler(thing, () -> modbusManager);
        thingHandler.setCallback(thingCallback);
        thingHandler.initialize();

        assertThat(thing.getStatus(), is(equalTo(ThingStatus.ONLINE)));

        ModbusSlaveEndpoint slaveEndpoint = thingHandler.asSlaveEndpoint();
        assertThat(slaveEndpoint, is(equalTo(new ModbusTCPSlaveEndpoint("thisishost", 44))));
        assertThat(thingHandler.getSlaveId(), is(9));

        InOrder orderedVerify = Mockito.inOrder(modbusManager);
        orderedVerify.verify(modbusManager).addListener(thingHandler);
        orderedVerify.verify(modbusManager).setEndpointPoolConfiguration(thingHandler.asSlaveEndpoint(),
                expectedPoolConfiguration);

    }

}
