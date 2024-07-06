# Hunter Douglas (Luxaflex) PowerView Binding for Bluetooth

This is an openHAB binding for Bluetooth for [Hunter Douglas PowerView](https://www.hunterdouglas.com/operating-systems/motorized/powerview-motorization/overview) motorized shades via Bluetooth Low Energy (BLE).
In some countries the PowerView system is sold under the brand name [Luxaflex](https://www.luxaflex.com/).

This binding supports Generation 3 shades connected directly via their in built Bluetooth Low Energy interface.
There is a different binding [here](https://www.openhab.org/addons/bindings/hdpowerview/) for shades that are connected via a hub or gateway.

![PowerView](doc/hdpowerview.png)

PowerView shades have motorization control for their vertical position, and some also have vane controls to change the angle of their slats.

## Supported Things

| Thing | Description                                                                        |
|-------|------------------------------------------------------------------------------------|
| shade | A Powerview Generation 3 motorized shade connected via Bluetooth Low Energy (BLE). |

## Bluetooth Bridge

Before you can create `shade` Things, you must first create a Bluetooth Bridge to contain them.
The instructions for creating a Bluetooth Bridge [here](https://www.openhab.org/addons/bindings/bluetooth/).

## Discovery

Make sure your shades are visible via BLE in the PowerView app before attempting discovery.

The discovery process can be started by pressing the `+` button at the lower right of the Main UI Things page, selecting the Bluetooth binding, and pressing `Scan`.
Any discovered shades will be displayed with the name prefix 'Powerview Shade'.

## Configuration

| Configuration Parameter | Type     | Description                                                                                                         |
|-------------------------|----------|---------------------------------------------------------------------------------------------------------------------|
| address                 | Required | The Bluetooth MAC address of the shade.                                                                             |
| bleTimeout              | Optional | The maximum number of seconds to wait before transactions over Bluetooth will time out (default = 6 seconds).       |
| heartbeatDelay          | Optional | The scanning interval in seconds at which the binding checks if the Shade is on- or off- line (default 15 seconds). |
| pollingDelay            | Optional | The scanning interval in seconds at which the binding checks the battery status (default 300 seconds).              |

## Channels

A shade always implements a roller shutter channel `position` which controls the vertical position of the shade's (primary) rail.
If the shade has slats or rotatable vanes, there is also a dimmer channel `tilt` which controls the slat / vane position.
If it is a dual action (top-down plus bottom-up) shade, there is also a roller shutter channel `secondary` which controls the vertical position of the secondary rail.

| Channel      | Item Type      | Description                                           |
|--------------|----------------|-------------------------------------------------------|
| position     | Rollershutter  | The vertical position of the shade's rail.            |
| secondary    | Rollershutter  | The vertical position of the secondary rail (if any). |
| tilt         | Dimmer         | The degree of opening of the slats or vanes (if any). |
| batteryLevel | Number:Percent | Battery level (10% = low, 50% = medium, 100% = high). |
| rssi         | Number:Power   | Received Signal Strength Indication.                  |

Note: the channels `secondary` and `tilt` only exist if the shade physically supports such channels.