package com.fngtps.wowza.tube;

import com.wowza.wms.application.*;
import com.wowza.wms.amf.*;
import com.wowza.wms.client.*;
import com.wowza.wms.module.*;
import com.wowza.wms.request.*;
import com.wowza.wms.stream.*;
import com.wowza.wms.rtp.model.*;
import com.wowza.wms.httpstreamer.model.*;
import com.wowza.wms.httpstreamer.cupertinostreaming.httpstreamer.*;
import com.wowza.wms.httpstreamer.smoothstreaming.httpstreamer.*;

public class Callbacks extends ModuleBase {
	private String callbackUrl;
	private String callbackSecret;

	public void onAppStart(IApplicationInstance appInstance) {
		this.callbackUrl = appInstance.getProperties().getPropertyStr("SWITCHtubeCallbackUrl");
		this.callbackSecret = appInstance.getProperties().getPropertyStr("SWITCHtubeCallbackSecret");
		getLogger().info("Using " + this.callbackUrl + " to send callbacks to SWITCHtube.");
	}
}
