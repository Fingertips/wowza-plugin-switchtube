# Wowza SWITCHtube Integration

Wowza module that implements a `Callback` module that authorizes incoming RTMP connections and send events when the stream connects, publishes, and disconnects.

## Installation

Download the latest release, unpack the zip, and copy the JAR to the Wowza Streaming Server lib directory.

```sh
# Make sure you change the release version in the URL to the most recent release.
curl -L -O https://github.com/Fingertips/wowza-plugin-switchtube/releases/download/v0.0.3/wowza-plugin-switchtube.zip
unzip wowza-plugin-switchtube.zip
mv wowza-plugin-switchtube.jar /usr/local/WowzaStreamingEngine/lib
```

After this you may have to restart the server before it's picked up.

## Configuration

In order to integrate with SWITCHtube an application needs a number of settings and features.

When you want to install the module for all applications, you can add it to `Application.xml` in the `conf` directory. If you want to install it for a specific application, use the `Application.xml` specific for the application. You generally use one application for a SWITCHtube install, so application specific configuration is preferred.

* `/usr/local/WowzaStreamingEngine/lib/conf/Application.xml`
* `/usr/local/WowzaStreamingEngine/lib/conf/application_name/Application.xml`

Find the `<Modules>` element and add this module to the end. Keep all the existing module definitions or they will no longer load.

```xml
<Modules>
  <Module>
    <Name>wowza-plugin-switchtube</Name>
    <Description>SWITCHtube authorization and callbacks</Description>
    <Class>com.fngtps.wowza.tube.Callbacks</Class>
  </Module>
</Modules>
```

Then find the `<Properties>` element and configure SWITCHtube specific details:

```xml
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

The properties are:

* **SWITCHtubeCallbackUrl**: URL to the SWITCHtube install, usually `https://tube.switch.ch/streaming/events`
* **SWITCHtubeCallbackSecret**: Shared secret between the module and SWITCHtube, used to authenticate the Wowza server.

In order to successfully use the application with SWITCHtube, you need to make sure that the following settings are configured for the application:

* Apple HLS is enabled
* nDVR is enabled
    * Live and DVR streaming
    * Start recording on startup
    * Archive Method is Append

You don't need DASH or any of the other playback formats.

## Development

### Dependencies

In order to build the module you will need to install the Wowza Streaming Server or at the very least have a copy of the JARs it ships with. The default search path for the JARs is `/usr/local/WowzaStreamingEngine/lib`. Wowza Streaming Server will not install nor run without a valid license.

To build you need a recent version of Ant and JDK 11 (target API version 55).

### Building

Build by running `ant` in the root of the checkout. That should create a `wowza-plugin-switchtube.jar` in the `dist` directory.

You can look at the files in `.github/workflows` to see an example of how we build on CI.

### Installing

Copy the JAR to the Wowza Streaming Server library directory (eg. `/usr/local/WowzaStreamingEngine/lib`).

### Releases

GitHub workflows will take care of creating the release as soon and you create a new tag and push it to the repository.

```
# Fetch all tags so you know the version number of the latest release.
git fetch
git tag

# Create an push a new tag, make sure you choose a non-existent tag with
# a higher version than the most recent.
git tag "v1.0.0"
git push origin "v1.0.0"
```

You must release within 3 days of merging a branch into main or GitHub will have removed the build artifacts.

## Copyright

See LICENSE.