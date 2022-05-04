# SWITCHtube

Implements a `Callback` module for Wowza to authorize incoming RTMP connections and send events when the stream connects, publishes, and disconnects.

## Configuration

* **SWITCHtubeCallbackUrl**: URL to the SWITCHtube install, usually `https://tube.switch.ch/streaming/events`
* **SWITCHtubeCallbackSecret**: Shared secret between the module and SWITCHtube, used to authenticate the Wowza server.

These properties have to be set for application that loads the module.

```xml
<Modules>
  <Module>
    <Name>wowza-plugin-switchtube</Name>
    <Description>SWITCHtube authorization and callbacks</Description>
    <Class>com.fngtps.wowza.tube.Callbacks</Class>
  </Module>
</Modules>
<Properties>
  <Property>
    <Name>SWITCHtubeCallbackUrl</Name>
    <Value>https://tube.switch.ch/streaming/events</Value>
    <Type>String</Type>
  </Property>
  <Property>
    <Name>SWITCHtubeCallbackSecret</Name>
    <Value>very-very-secret</Value>
    <Type>String</Type>
  </Property>
</Properties>
```
