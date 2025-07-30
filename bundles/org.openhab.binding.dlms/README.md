# DLMS/COSEM Smart Meter Binding

This binding retrieves readings from Smart Meters that support the DLMS/COSEM standard via an IEC 62056-21 optical read head.

## Supported Things

This binding supports only one Thing: `meter`

## Discovery

Discovery is not available.

## Thing Configuration

The `meter` thing requires the address of the serial port where the IEC 62056-21 optical read head is connected, and optionally a refresh interval.

| Parameter   |  Description                                                                                                      | Required |
|-------------|-------------------------------------------------------------------------------------------------------------------|----------|
| `port`      | The serial port to which the optical read head is attached, e.g. `/dev/ttyUSB0`, `rfc2217://xxx.xxx.xxx.xxx:3002` | Yes      |
| `refresh`   | The refresh interval in seconds. Default is 60 seconds.                                                           | No       |

## Channels

The `meter` Thing creates channels automatically to match all the values measured by the respective meter.
