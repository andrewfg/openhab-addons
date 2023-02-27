# Philips Hue Binding Configuration for API v2

[Back to Overview](../README.md#philips-hue-binding)

## Supported Things

The binding supports *bridge*, *light*, *button*, and *sensor* devices.
Lights can be of any type from a simple on/off light, through dimmable monochrome lights, to full colour dimmable lights.
Buttons are devices that contain one or more push buttons.
Sensors can be (for example) light level sensors, temperature sensors, or motion sensors.

## Thing Configuration

### Bridge

The Hue Bridge requires the IP address as a configuration value in order for the binding to know where to access it.
In the thing file, this looks e.g. like

```java
Bridge hue:bridge:1 [ ipAddress="192.168.0.64" ]
```

An 'application key' to authenticate against the Hue Bridge is automatically generated.
Please note that the generated application key cannot be written automatically to the `.thing` file, and has to be set manually.
The generated application key can be found, after pressing the authentication button on the bridge, with the following console command: `openhab:hue <bridgeUID> applicationkey`.
The application key can be set using the `applicationKey` configuration value, e.g.:

```java
Bridge hue:bridge:1 [ ipAddress="192.168.0.64", applicationKey="qwertzuiopasdfghjklyxcvbnm1234" ]
```

| Parameter                | Description                                                                                        |
|--------------------------|----------------------------------------------------------------------------------------------------|
| ipAddress                | Network address of the Hue Bridge. **Mandatory**.                                                  |
| applicationKey           | A code generated by the bridge that allows to access the API. **Mandatory**                        |
| checkSeconds             | Interval in seconds between retrying the HTTP 2 and SSE connections. Default is 3600. **Advanced** |
| useSelfSignedCertificate | Use self-signed certificate for HTTPS connection to Hue Bridge. Default is `true`. **Advanced**    |

### Devices and Grouped Lights

Apart from the Bridge, there are two other types of thing -- namely Devices `device` and Grouped Lights `groupedlight`.
Device things represent physical hardware devices in the system, whereas Grouped Light things represent logical groups of lights, either in a room or a zone.

All things are identified by a unique Resource Identifier string that the Hue Bridge assigns to them e.g. `d1ae958e-8908-449a-9897-7f10f9b8d4c2`.
Thus, all it needs for manual configuration is this single value like

```java
device officelamp "Lamp 1" @ "Office" [ resourceId="d1ae958e-8908-449a-9897-7f10f9b8d4c2" ]
..
groupedlight kitchenLights "Kitchen Down Lihts" @ "Kitchen" [ resourceId="7f10f9b8-8908-449a-9897-d4c2d1ae958e" ]
```

You can get a list of all devices in the bridge and their respective Resource Ids by entering the following console command: `openhab:hue <bridgeUID> devices`
See [console command](#console-command-for-finding-resourceids)

The configuration of all things (as described above) is the same regardless of whether the device is a light, a button, a sensor, or a grouped light.

### Channels for Devices

Device things support some of the following channels:

| Channel Type ID     | Item Type          | Description                                                                           |
|---------------------|--------------------|---------------------------------------------------------------------------------------|
| switch              | Switch             | This channel supports switching the device on and off.                                |
| color               | Color              | This channel supports full color control with hue, saturation and brightness values.  |
| brightness          | Dimmer             | This channel supports adjusting the brightness value.                                 |
| colorTemperature    | Dimmer             | This channel supports adjusting the color temperature from cold (0%) to warm (100%).  |
| colorTemperatureAbs | Number:Temperature | This channel supports adjusting the color temperature in Kelvin.                      |
| buttonLastEvent     | Number             | This channel shows which button was last pressed in the device.                       |
| rotarySteps         | Number             | This channel shows the number of rotary steps of the last rotary dial movement.       |
| motion              | Switch             | This channel shows if motion has been detected by the sensor.                         |
| motionEnabled       | Switch             | This channel supports enabling / disabling the motion sensor.                         |
| lightLevel          | Number             | This channel shows the current light level measured by the sensor.                    |
| lightLevelEnabled   | Switch             | This channel supports enabling / disabling the light level sensor.                    |
| temperature         | Number:Temperature | This channel shows the current temperature measured by the sensor.                    |
| temperatureEnabled  | Switch             | This channel supports enabling / disabling the temperature sensor.                    |
| lastUpdated         | DateTime           | This channel the date and time when the sensor was last updated.                      |
| batteryLevel        | Number             | This channel shows the battery level.                                                 |
| batteryLow          | Switch             | This channel indicates whether the battery is low or not.                             |
| zigbeeStatus        | String             | This channel provides information about the status of the Zigbee connection.          |

The exact list of channels in a given device is determined at run time when the system is started.
Each device reports its own live list of capabilities, and the respective list of channels is created accordingly.

The `button_last_event` channel value is a number that is calculated from the following formula:

```text
value = (button_id * 1000) + event_id;
```

In a single button device, the `button_id` is 1, whereas in a multi- button device the `button_id` can be either 1, 2, 3, or 4 depending on which button was pressed.
The `event_id` has the following values..

| Event                | Value |
|----------------------|-------|
| INITIAL_PRESS        | 0     |
| REPEAT               | 1     |
| SHORT_RELEASE        | 2     |
| LONG_RELEASE         | 3     |
| DOUBLE_SHORT_RELEASE | 4     |

So (for example) the channel value `1002` ((1 * 1000) + 2) means that the second button in the device had a short release event.

The `rotary_steps` channel value is the number of steps corresponding to the last movement of a rotary dial.
A positive number means the dial was rotated clock-wise, whereas a negative number means it was roated counter-clockwise.

### Channels for Grouped Lights

Grouped Light things support some of the following channels:

| Channel Type ID     | Item Type          | Description                                                                           |
|---------------------|--------------------|---------------------------------------------------------------------------------------|
| switch              | Switch             | This channel supports switching the device on and off.                                |
| color               | Color              | This channel supports full color control with hue, saturation and brightness values.  |
| brightness          | Dimmer             | This channel supports adjusting the brightness value.                                 |
| colorTemperature    | Dimmer             | This channel supports adjusting the color temperature from cold (0%) to warm (100%).  |
| colorTemperatureAbs | Number:Temperature | This channel supports adjusting the color temperature in Kelvin.                      |

The exact list of channels in a given group of lights is determined at run time when the system is started.

### Channels for the Bridge

The API v2 bridge thing supports just one channel as follows:

| Channel Type ID       | Item Type          | Description                                                                                                           |
|-----------------------|--------------------|-----------------------------------------------------------------------------------------------------------------------|
| scene                 | String             | This channel activates the scene with the given ID String. The ID String of each scene is assigned by the Hue Bridge. |

To load a Hue scene inside a rule for example, the ID of the scene will be required.
You can list all the scene IDs with the following console command: `openhab:hue <bridgeUID> scenes`.

## Console Command for finding ResourceIds

The openHAB console has a command named `openhab:hue` that (among other things) lists the `resourceId` of all device things in the bridge.
The console command usage is `openhab:hue <brigeUID> things`.
An exampe of such a console command, and its respective output, is shown below..

```text
openhab> openhab:hue hue:clip2:g24 devices
Bridge hue:clip2:g24 "Philips Hue Bridge" [ipAddress="192.168.1.234", applicationKey="abcdefghijklmnopqrstuvwxyz0123456789ABCD"] {
  Thing device 11111111-2222-3333-4444-555555555555 "Standard Lamp L" [resourceId="11111111-2222-3333-4444-555555555555"] // Hue color lamp
  Thing device 11111111-2222-3333-4444-666666666666 "Kitchen Wallplate Switch" [resourceId="11111111-2222-3333-4444-666666666666"] // Hue wall switch module
  ..
}
```

The `openhab:hue <brigeUID> things` produces an output that can be used to directly create a `.things' file, as shown below..

```text
openhab> openhab:hue hue:clip2:g24 things > myThingsFile.things
```

## Full Example

### demo.things:

```java
Bridge hue:clip2:g24 "Philips Hue Hub" @ "Home" [ipAddress="192.168.1.234", applicationKey="abcdefghijklmnopqrstuvwxyz0123456789ABCD"] {
    Thing device 11111111-2222-3333-4444-555555555555 "Living Room Standard Lamp Left" @ "Living Room" [resourceId="11111111-2222-3333-4444-555555555555"]
    Thing device 11111111-2222-3333-4444-666666666666 "Kitchen Wallplate Switch" @ "Kitchen" [resourceId="11111111-2222-3333-4444-666666666666"]

    Thing groupedlight 11111111-2222-3333-4444-666666666666 "Kitchen Lights" @ "Kitchen" [resourceId="11111111-2222-3333-4444-666666666666"]
}
```

### demo.items:

```java
Color Living_Room_Standard_Lamp_Left_Colour "Living Room Standard Lamp Left Colour" {channel="hue:device:g24:11111111-2222-3333-4444-555555555555:color"}
Dimmer Living_Room_Standard_Lamp_Left_Brightness "Living Room Standard Lamp Left Brightness [%.0f %%]" {channel="hue:device:g24:11111111-2222-3333-4444-555555555555:brightness"}
Switch Living_Room_Standard_Lamp_Left_Switch "Living Room Standard Lamp Left Switch" (g_Lights_On_Count) {channel="hue:device:g24:11111111-2222-3333-4444-555555555555:switch"}

Number Kitchen_Wallplate_Switch_Last_Event "Kitchen Wallplate Switch Last Event" {channel="hue:device:g24:11111111-2222-3333-4444-666666666666:buttonLastEvent"}
Switch Kitchen_Wallplate_Switch_Battery_Low_Alarm "Kitchen Wallplate Switch Battery Low Alarm" {channel="hue:device:g24:11111111-2222-3333-4444-666666666666:batteryLow"}
```

### demo.sitemap:

```perl
sitemap demo label="Hue" {
  Frame label="Standard Lamp" {
    Switch item=Living_Room_Standard_Lamp_Left_Switch
    Slider item=Living_Room_Standard_Lamp_Left_Brightness
    Colorpicker item=Living_Room_Standard_Lamp_Left_Colour
  }
}
```

[Back to Overview](../README.md#philips-hue-binding)