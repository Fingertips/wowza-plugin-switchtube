# SWITCHtube

Implements a `Callback` module for Wowza to authorize incoming RTMP connections and send events when the stream connects, publishes, and disconnects.

## Configuration

In order to integrate with SWITCHtube an application needs a number of settings and features.

Add the following to `application_name/Application.xml`:

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

Then make sure:

* Apple HLS is enabled
* nDVR is enabled
    * Live and DVR streaming
    * Start recording on startup
    * Archive Method is Append

## Releases

GitHub workflows will take care of creating the release as soon and you create a new tag and push it to the repository.

```
# Fetch all tags so you know the version number of the latest release.
git fetch
git tag

# Create an push a new tag.
git tag "v0.0.1"
git push origin "v0.0.1"
```
